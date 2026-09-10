package com.obsidianwidget

import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject

/**
 * Two backends, same event vocabulary, same fallback behavior as
 * obsidian-agent's own Obsidian plugin (ported from its plugin/main.js):
 * Claude Code over the agent-host.mjs WebSocket by default, falling back to
 * MiMo — an OpenAI-style HTTP endpoint with its own four vault tools — when
 * the host isn't reachable, and retrying on the other backend if a turn
 * fails partway through.
 */
class AgentChatActivity : AppCompatActivity() {

    private lateinit var agentManager: AgentManager
    private lateinit var statusLabel: TextView
    private lateinit var messagesContainer: LinearLayout
    private lateinit var scrollView: ScrollView
    private lateinit var input: EditText
    private lateinit var sendButton: Button

    private val client = OkHttpClient.Builder().build()
    private var claudeSocket: WebSocket? = null
    private var claudeOpen = false
    private var mimoClient: MimoClient? = null

    private var pendingTurn = false
    private var lastUserText: String = ""
    private var currentAssistantBubble: TextView? = null
    private var currentThinkingBubble: TextView? = null
    private var assistantTextThisTurn = StringBuilder()
    private val pendingToolBubbles = mutableMapOf<String, TextView>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        supportRequestWindowFeature(android.view.Window.FEATURE_NO_TITLE)
        setContentView(R.layout.activity_agent_chat)

        agentManager = AgentManager(this)
        statusLabel = findViewById(R.id.chat_status)
        messagesContainer = findViewById(R.id.chat_messages)
        scrollView = findViewById(R.id.chat_scroll)
        input = findViewById(R.id.chat_input)
        sendButton = findViewById(R.id.chat_send)

        findViewById<View>(R.id.chat_close).setOnClickListener { finish() }
        findViewById<View>(R.id.chat_settings).setOnClickListener { showSettingsDialog() }
        findViewById<View>(R.id.chat_history).setOnClickListener { showSessionHistoryDialog() }

        sendButton.setOnClickListener {
            if (pendingTurn) {
                cancelCurrentTurn()
            } else {
                val text = input.text.toString().trim()
                if (text.isNotEmpty()) {
                    input.setText("")
                    sendPrompt(text)
                }
            }
        }

        statusLabel.text = "connecting…"
        connectClaude()
    }

    override fun onDestroy() {
        super.onDestroy()
        claudeSocket?.close(1000, null)
        mimoClient?.cancel()
    }

    // ── connection ───────────────────────────────────────────────────────

    private fun connectClaude() {
        val request = Request.Builder().url(agentManager.wsUrl).build()
        val newSocket = client.newWebSocket(request, object : WebSocketListener() {
            // A reconnect (e.g. after changing settings) closes the old
            // socket and opens a new one; the old one's callbacks can still
            // fire afterward (OkHttp closes asynchronously). Without this
            // guard, a stale onClosing/onFailure from the socket we already
            // replaced stomps claudeOpen back to false right after the new
            // one succeeded — which is exactly what got stuck on
            // "reconnecting…": the new socket had opened, but the dying old
            // one's callback ran a moment later and undid it.
            override fun onOpen(webSocket: WebSocket, response: Response) {
                if (webSocket !== claudeSocket) return
                claudeOpen = true
                runOnUiThread {
                    if (!pendingTurn) statusLabel.text = "Claude Code"
                }
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                if (webSocket !== claudeSocket) return
                handleClaudeMessage(text)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                if (webSocket !== claudeSocket) return
                claudeOpen = false
                runOnUiThread {
                    if (!pendingTurn) statusLabel.text = "offline"
                    onBackendFailedMidTurn("Claude Code", t.message ?: "connection failed")
                }
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                if (webSocket === claudeSocket) claudeOpen = false
                webSocket.close(1000, null)
            }
        })
        claudeSocket = newSocket
    }

    private fun showSettingsDialog() {
        val view = layoutInflater.inflate(R.layout.dialog_agent_settings, null)
        view.findViewById<EditText>(R.id.settings_host).setText(agentManager.host)
        view.findViewById<EditText>(R.id.settings_port).setText(agentManager.port.toString())
        view.findViewById<EditText>(R.id.settings_mimo_key).setText(agentManager.mimoKey)
        view.findViewById<EditText>(R.id.settings_mimo_url).setText(agentManager.mimoBaseUrl)
        view.findViewById<EditText>(R.id.settings_mimo_model).setText(agentManager.mimoModel)

        AlertDialog.Builder(this)
            .setTitle("Agent settings")
            .setView(view)
            .setPositiveButton("Save") { _, _ ->
                agentManager.host = view.findViewById<EditText>(R.id.settings_host)
                    .text.toString().trim().ifBlank { AgentManager.DEFAULT_HOST }
                agentManager.port = view.findViewById<EditText>(R.id.settings_port)
                    .text.toString().trim().toIntOrNull() ?: AgentManager.DEFAULT_PORT
                agentManager.mimoKey = view.findViewById<EditText>(R.id.settings_mimo_key).text.toString().trim()
                agentManager.mimoBaseUrl = view.findViewById<EditText>(R.id.settings_mimo_url)
                    .text.toString().trim().ifBlank { AgentManager.DEFAULT_MIMO_BASE_URL }
                agentManager.mimoModel = view.findViewById<EditText>(R.id.settings_mimo_model)
                    .text.toString().trim().ifBlank { AgentManager.DEFAULT_MIMO_MODEL }
                mimoClient = null // rebuilt lazily with the new settings
                addSystemBubble("Reconnecting…")
                claudeSocket?.close(1000, null)
                connectClaude()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showSessionHistoryDialog() {
        val sessions = agentManager.sessionHistory
        val labels = mutableListOf("+ New conversation")
        labels += sessions.map { entry ->
            val relative = android.text.format.DateUtils.getRelativeTimeSpanString(
                entry.timestamp, System.currentTimeMillis(), android.text.format.DateUtils.MINUTE_IN_MILLIS
            )
            val current = if (entry.id == agentManager.sessionId) " (current)" else ""
            "${entry.preview}$current — $relative"
        }

        AlertDialog.Builder(this)
            .setTitle("Sessions")
            .setItems(labels.toTypedArray()) { _, which ->
                if (which == 0) {
                    agentManager.sessionId = null
                    messagesContainer.removeAllViews()
                    addSystemBubble("New conversation")
                } else {
                    val entry = sessions[which - 1]
                    agentManager.sessionId = entry.id
                    messagesContainer.removeAllViews()
                    addSystemBubble("Resumed: ${entry.preview}")
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ── sending ──────────────────────────────────────────────────────────

    private fun sendPrompt(text: String) {
        lastUserText = text
        addBubble(text, isUser = true)
        beginTurn()

        if (claudeOpen) {
            val payload = JSONObject().apply {
                put("type", "prompt")
                put("text", text)
                // "null" (the string) is a stale-corruption sentinel, not a
                // real id — see handleClaudeMessage. Self-heals a device
                // that already stored it before this fix.
                agentManager.sessionId
                    ?.takeIf { it.isNotEmpty() && it != "null" }
                    ?.let { put("sessionId", it) }
                    ?: run { agentManager.sessionId = null }
            }
            if (claudeSocket?.send(payload.toString()) == true) {
                statusLabel.text = "Claude Code"
                return
            }
        }
        runMimoOrFail(text)
    }

    private fun runMimoOrFail(text: String) {
        val vaultUri = VaultManager(this).vaultUri
        val key = agentManager.mimoKey
        if (key.isBlank() || vaultUri == null) {
            statusLabel.text = "offline"
            render(AgentEvent.Error("No agent reachable — start the host, or set a MiMo key in settings"))
            render(AgentEvent.Done())
            return
        }
        statusLabel.text = "MiMo"
        val mimo = mimoClient ?: MimoClient(
            this, vaultUri, key, agentManager.mimoBaseUrl, agentManager.mimoModel
        ).also { mimoClient = it }
        Thread {
            mimo.send(text) { event -> runOnUiThread { render(event) } }
        }.start()
    }

    private fun cancelCurrentTurn() {
        claudeSocket?.send(JSONObject().apply { put("type", "cancel") }.toString())
        mimoClient?.cancel()
    }

    private fun onBackendFailedMidTurn(name: String, reason: String) {
        if (!pendingTurn) return // a connection drop outside a turn isn't an error to show
        val key = agentManager.mimoKey
        val vaultUri = VaultManager(this).vaultUri
        if (key.isNotBlank() && vaultUri != null) {
            addSystemBubble("$name went away ($reason) — retrying on MiMo")
            beginTurn()
            runMimoOrFail(lastUserText)
        } else {
            render(AgentEvent.Error("$name went away: $reason"))
            render(AgentEvent.Done())
        }
    }

    private fun beginTurn() {
        pendingTurn = true
        currentAssistantBubble = null
        currentThinkingBubble = null
        assistantTextThisTurn = StringBuilder()
        pendingToolBubbles.clear()
        updateSendButton()
    }

    private fun finishTurn() {
        pendingTurn = false
        updateSendButton()
        if (assistantTextThisTurn.isNotEmpty()) {
            agentManager.lastPreview = assistantTextThisTurn.toString().take(140)
            AgentWidgetProvider.updateAllWidgets(this)
        }
        // MiMo has no session concept, so there's nothing to record there —
        // only Claude turns leave a resumable id.
        agentManager.sessionId?.let { agentManager.recordSession(it, lastUserText.take(60)) }
    }

    private fun updateSendButton() {
        sendButton.text = getString(if (pendingTurn) R.string.stop else R.string.send)
    }

    // ── Claude event parsing ─────────────────────────────────────────────

    /**
     * Raw `claude -p --output-format stream-json` events forwarded by
     * agent-host.mjs, translated into the same AgentEvent vocabulary MiMo
     * uses — mirrors obsidian-agent's plugin/main.js `consume()`.
     */
    private fun handleClaudeMessage(line: String) {
        val obj = try { JSONObject(line) } catch (e: Exception) { return }

        // obj.optString("session_id", "") would return the literal string
        // "null" if this key is present but JSON-null (a real org.json
        // behavior, not a hypothetical) — has(), then isNull() before
        // reading avoids ever storing that as if it were a real session id.
        if (obj.has("session_id") && !obj.isNull("session_id")) {
            val sessionId = obj.getString("session_id")
            if (sessionId.isNotEmpty()) agentManager.sessionId = sessionId
        }

        when (obj.optString("type")) {
            "host_error" -> runOnUiThread {
                val error = obj.optString("error", "unknown host error")
                if (error.contains("No conversation found") || error.contains("--resume")) {
                    // The session id we tried to resume is gone (deleted,
                    // expired, or from a build before a fix reset it) — not
                    // something retrying the same id will ever fix. Clear it
                    // and retry once as a fresh conversation instead of
                    // failing this way on every future turn too.
                    agentManager.sessionId = null
                    addSystemBubble("Session expired — starting a new conversation")
                    beginTurn()
                    val payload = JSONObject().apply { put("type", "prompt"); put("text", lastUserText) }
                    if (claudeOpen && claudeSocket?.send(payload.toString()) == true) {
                        statusLabel.text = "Claude Code"
                    } else {
                        runMimoOrFail(lastUserText)
                    }
                } else {
                    render(AgentEvent.Error(error))
                }
            }
            "host_turn_end" -> runOnUiThread {
                render(AgentEvent.Done())
            }
            "result" -> {
                val cost = obj.optDouble("total_cost_usd", Double.NaN)
                if (!cost.isNaN()) runOnUiThread { render(AgentEvent.Done(cost)) }
            }
            "assistant", "user" -> {
                val content = obj.optJSONObject("message")?.optJSONArray("content") ?: return
                for (i in 0 until content.length()) {
                    val block = content.optJSONObject(i) ?: continue
                    val event = when (block.optString("type")) {
                        "text" -> block.optString("text").takeIf { it.isNotEmpty() }?.let { AgentEvent.Text(it) }
                        "thinking" -> block.optString("thinking").takeIf { it.isNotEmpty() }?.let { AgentEvent.Thinking(it) }
                        "tool_use" -> AgentEvent.ToolUse(
                            block.optString("id"), block.optString("name"), block.optJSONObject("input") ?: JSONObject()
                        )
                        "tool_result" -> {
                            val c = block.opt("content")
                            AgentEvent.ToolResult(
                                block.optString("tool_use_id"),
                                if (c is String) c else (c?.toString() ?: ""),
                                block.optBoolean("is_error", false)
                            )
                        }
                        else -> null
                    }
                    if (event != null) runOnUiThread { render(event) }
                }
            }
        }
    }

    // ── unified rendering ────────────────────────────────────────────────

    private fun render(event: AgentEvent) {
        when (event) {
            is AgentEvent.Text -> {
                assistantTextThisTurn.append(event.text)
                val bubble = currentAssistantBubble ?: addBubble("", isUser = false).also { currentAssistantBubble = it }
                bubble.text = assistantTextThisTurn.toString()
                scrollToBottom()
            }
            is AgentEvent.Thinking -> {
                val bubble = currentThinkingBubble ?: addBubble("", isUser = false, isThinking = true).also { currentThinkingBubble = it }
                bubble.text = (bubble.text?.toString() ?: "") + event.text
                scrollToBottom()
            }
            is AgentEvent.ToolUse -> {
                val argLines = toolArgLines(event.input)
                val header = "🔧 ${event.name}"
                val body = if (argLines.isEmpty()) header else
                    header + "\n" + argLines.joinToString("\n") { "  $it" }
                val bubble = addSystemBubble(body)
                pendingToolBubbles[event.id] = bubble
            }
            is AgentEvent.ToolResult -> {
                val bubble = pendingToolBubbles.remove(event.id) ?: return
                val preview = event.content.take(300).let { if (event.content.length > 300) "$it…" else it }
                val outcome = when {
                    event.isError -> "⚠ $preview"
                    preview.isNotBlank() -> "→ $preview"
                    else -> "✓ done"
                }
                bubble.text = "${bubble.text}\n$outcome"
            }
            is AgentEvent.Error -> {
                addErrorBubble(event.message)
                finishTurn()
            }
            is AgentEvent.Done -> finishTurn()
        }
    }

    /**
     * Every argument, not just a guessed "the" one — same fields the
     * plugin's own tool-row renderer lists (ported from plugin/main.js's
     * `U()`). Bash's field is `command`, Read/Edit/Write's is `file_path`,
     * Grep's is `pattern` — the previous path/query/folder-only list showed
     * nothing at all for a Bash call, which is the bug being fixed here.
     */
    private fun toolArgLines(input: JSONObject): List<String> {
        val lines = mutableListOf<String>()
        val keys = input.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            if (key == "content" || key == "new_string" || key == "old_string") {
                lines.add("$key: ${input.optString(key, "").length} characters")
                continue
            }
            val raw = input.opt(key)
            val text = if (raw is String) raw else raw?.toString() ?: continue
            lines.add("$key: ${if (text.length > 300) text.take(299) + "…" else text}")
        }
        return lines
    }

    // ── transcript UI ────────────────────────────────────────────────────

    private fun addBubble(text: String, isUser: Boolean, isThinking: Boolean = false): TextView {
        val view = TextView(this).apply {
            this.text = text
            setTextColor(getColor(if (isUser) R.color.white else if (isThinking) R.color.chat_text_secondary_light else R.color.chat_text_light))
            textSize = if (isThinking) 12f else 14f
            setPadding(24, 16, 24, 16)
            background = getDrawable(if (isUser) R.drawable.button_background else R.drawable.card_background_light)
            typeface = when {
                isUser -> Typeface.DEFAULT_BOLD
                isThinking -> Typeface.create(Typeface.DEFAULT, Typeface.ITALIC)
                else -> Typeface.DEFAULT
            }
        }
        val maxWidth = (resources.displayMetrics.widthPixels * 0.8).toInt()
        val params = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            gravity = if (isUser) Gravity.END else Gravity.START
            topMargin = 12
            bottomMargin = 12
            if (view.paint.measureText(text).toInt() > maxWidth) width = maxWidth
        }
        view.layoutParams = params
        messagesContainer.addView(view)
        scrollToBottom()
        return view
    }

    private fun addSystemBubble(text: String): TextView {
        val view = TextView(this).apply {
            this.text = text
            setTextColor(getColor(R.color.chat_text_secondary_light))
            textSize = 12f
            gravity = Gravity.CENTER
        }
        val params = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = 8; bottomMargin = 8 }
        view.layoutParams = params
        messagesContainer.addView(view)
        scrollToBottom()
        return view
    }

    private fun addErrorBubble(text: String) {
        val view = TextView(this).apply {
            this.text = text
            setTextColor(getColor(R.color.obsidian_primary))
            textSize = 13f
            gravity = Gravity.CENTER
        }
        val params = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = 8; bottomMargin = 8 }
        view.layoutParams = params
        messagesContainer.addView(view)
        scrollToBottom()
    }

    private fun scrollToBottom() {
        scrollView.post { scrollView.fullScroll(View.FOCUS_DOWN) }
    }
}
