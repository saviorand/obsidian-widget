package com.obsidianwidget

import org.json.JSONObject

/**
 * The event vocabulary both backends speak — ported from obsidian-agent's
 * plugin/main.js, which defines exactly these six kinds for both its Claude
 * (WebSocket) and MiMo (HTTP) clients. Keeping the same shape here means one
 * rendering path serves either backend.
 */
sealed class AgentEvent {
    data class Text(val text: String) : AgentEvent()
    data class Thinking(val text: String) : AgentEvent()
    data class ToolUse(val id: String, val name: String, val input: JSONObject) : AgentEvent()
    data class ToolResult(val id: String, val content: String, val isError: Boolean) : AgentEvent()
    data class Error(val message: String) : AgentEvent()
    data class Done(val cost: Double? = null) : AgentEvent()
}
