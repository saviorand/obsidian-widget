package com.obsidianwidget

import android.content.Context
import android.net.Uri
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

private const val SYSTEM_PROMPT = "You are an assistant working inside an Obsidian vault. " +
    "Use the tools to read and write notes rather than describing what you would write. " +
    "When asked to create or change a note, do it with write_note and then say briefly what " +
    "you did. write_note replaces the whole file, so read it first if you are editing rather " +
    "than creating."

private fun tool(name: String, description: String, properties: JSONObject, required: List<String> = emptyList()): JSONObject =
    JSONObject().apply {
        put("type", "function")
        put("function", JSONObject().apply {
            put("name", name)
            put("description", description)
            put("parameters", JSONObject().apply {
                put("type", "object")
                put("properties", properties)
                if (required.isNotEmpty()) put("required", JSONArray(required))
            })
        })
    }

private fun stringProp(description: String? = null) = JSONObject().apply {
    put("type", "string")
    if (description != null) put("description", description)
}

// Identical to obsidian-agent's plugin/main.js tool list — same names,
// descriptions and schemas, so the model's behavior doesn't depend on which
// front end it's talking through.
private val TOOLS = JSONArray().apply {
    put(tool("read_note", "Read a note's full text.",
        JSONObject().put("path", stringProp("Vault-relative path, e.g. notes/idea.md")),
        listOf("path")))
    put(tool("write_note", "Create a note, or replace an existing one's entire contents.",
        JSONObject().put("path", stringProp()).put("content", stringProp()),
        listOf("path", "content")))
    put(tool("list_notes", "List note paths in the vault, optionally under a folder.",
        JSONObject().put("folder", stringProp("Optional prefix"))))
    put(tool("search_notes", "Find notes whose text contains a string. Returns paths and the matching line.",
        JSONObject().put("query", stringProp()),
        listOf("query")))
}

/**
 * Ported from obsidian-agent's plugin/main.js MiMo client: same system
 * prompt, same tools, same tool-call loop against an OpenAI-style
 * /chat/completions endpoint. Billed per token rather than a subscription,
 * and its tools are these four rather than Claude Code's full tool set.
 *
 * `send` blocks on network calls — run it off the main thread.
 */
class MimoClient(
    context: Context,
    vaultUri: Uri,
    private val apiKey: String,
    private val baseUrl: String,
    private val model: String
) {
    val name = "MiMo"
    private val tools = VaultTools(context, vaultUri)
    private val history = JSONArray().apply {
        put(JSONObject().put("role", "system").put("content", SYSTEM_PROMPT))
    }
    private val client = OkHttpClient.Builder()
        .callTimeout(60, TimeUnit.SECONDS)
        .build()
    @Volatile private var cancelled = false

    fun cancel() {
        cancelled = true
    }

    fun send(text: String, onEvent: (AgentEvent) -> Unit) {
        cancelled = false
        history.put(JSONObject().put("role", "user").put("content", text))

        for (round in 0 until 12) {
            if (cancelled) {
                onEvent(AgentEvent.Error("cancelled"))
                onEvent(AgentEvent.Done())
                return
            }

            val requestBody = JSONObject().apply {
                put("model", model)
                put("messages", history)
                put("tools", TOOLS)
                put("max_tokens", 4000)
            }.toString().toRequestBody("application/json".toMediaType())

            val request = Request.Builder()
                .url("$baseUrl/chat/completions")
                .addHeader("Authorization", "Bearer $apiKey")
                .addHeader("Content-Type", "application/json")
                .post(requestBody)
                .build()

            val choice: JSONObject
            try {
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        val snippet = response.body?.string()?.take(300) ?: ""
                        onEvent(AgentEvent.Error("$name returned ${response.code}: $snippet"))
                        onEvent(AgentEvent.Done())
                        return
                    }
                    val choices = JSONObject(response.body?.string() ?: "{}").optJSONArray("choices")
                    if (choices == null || choices.length() == 0) {
                        onEvent(AgentEvent.Error("$name returned no choices"))
                        onEvent(AgentEvent.Done())
                        return
                    }
                    choice = choices.getJSONObject(0)
                }
            } catch (e: Exception) {
                onEvent(AgentEvent.Error(if (cancelled) "cancelled" else "could not reach $name: ${e.message}"))
                onEvent(AgentEvent.Done())
                return
            }

            val message = choice.getJSONObject("message")
            val content = message.optString("content", "")
            if (content.isNotEmpty()) onEvent(AgentEvent.Text(content))

            val toolCalls = message.optJSONArray("tool_calls")
            if (toolCalls == null || toolCalls.length() == 0) {
                history.put(JSONObject().put("role", "assistant").put("content", content))
                onEvent(AgentEvent.Done())
                return
            }

            history.put(JSONObject().apply {
                put("role", "assistant")
                put("content", if (content.isEmpty()) JSONObject.NULL else content)
                put("tool_calls", toolCalls)
            })

            for (i in 0 until toolCalls.length()) {
                val call = toolCalls.getJSONObject(i)
                val function = call.getJSONObject("function")
                val id = call.optString("id")
                val toolName = function.optString("name")
                val args = try {
                    JSONObject(function.optString("arguments", "{}"))
                } catch (e: Exception) {
                    JSONObject()
                }
                onEvent(AgentEvent.ToolUse(id, toolName, args))

                var result: String
                var isError = false
                try {
                    result = tools.run(toolName, args)
                } catch (e: Exception) {
                    result = e.message ?: "error"
                    isError = true
                }
                onEvent(AgentEvent.ToolResult(id, result, isError))
                history.put(JSONObject().apply {
                    put("role", "tool")
                    put("tool_call_id", id)
                    put("content", result.take(20000))
                })
            }
        }

        onEvent(AgentEvent.Error("gave up after 12 tool rounds"))
        onEvent(AgentEvent.Done())
    }
}
