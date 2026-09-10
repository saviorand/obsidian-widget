package com.obsidianwidget

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

/**
 * Connection settings and last-turn state for the agent chat widget.
 * Global rather than per-widget-instance — there is one agent-host.mjs to
 * talk to, and one conversation with it, regardless of how many home-screen
 * glance widgets point at it.
 */
class AgentManager(context: Context) {

    companion object {
        private const val PREFS_NAME = "agent_widget_prefs"
        private const val KEY_HOST = "host"
        private const val KEY_PORT = "port"
        private const val KEY_SESSION_ID = "session_id"
        private const val KEY_LAST_PREVIEW = "last_preview"
        private const val KEY_MIMO_KEY = "mimo_key"
        private const val KEY_MIMO_BASE_URL = "mimo_base_url"
        private const val KEY_MIMO_MODEL = "mimo_model"
        private const val KEY_SESSION_HISTORY = "session_history"
        private const val MAX_SESSION_HISTORY = 20
        const val DEFAULT_HOST = "127.0.0.1"
        const val DEFAULT_PORT = 8177
        // Same defaults as obsidian-agent's plugin, so a key copied from
        // its settings works here unchanged.
        const val DEFAULT_MIMO_BASE_URL = "https://token-plan-ams.xiaomimimo.com/v1"
        const val DEFAULT_MIMO_MODEL = "mimo-v2.5-pro"
    }

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    var host: String
        get() = prefs.getString(KEY_HOST, DEFAULT_HOST) ?: DEFAULT_HOST
        set(value) = prefs.edit().putString(KEY_HOST, value).apply()

    var port: Int
        get() = prefs.getInt(KEY_PORT, DEFAULT_PORT)
        set(value) = prefs.edit().putInt(KEY_PORT, value).apply()

    var sessionId: String?
        get() = prefs.getString(KEY_SESSION_ID, null)
        set(value) = prefs.edit().putString(KEY_SESSION_ID, value).apply()

    var lastPreview: String?
        get() = prefs.getString(KEY_LAST_PREVIEW, null)
        set(value) = prefs.edit().putString(KEY_LAST_PREVIEW, value).apply()

    /** Empty means no fallback configured — matches the plugin's own convention. */
    var mimoKey: String
        get() = prefs.getString(KEY_MIMO_KEY, "") ?: ""
        set(value) = prefs.edit().putString(KEY_MIMO_KEY, value).apply()

    var mimoBaseUrl: String
        get() = prefs.getString(KEY_MIMO_BASE_URL, DEFAULT_MIMO_BASE_URL) ?: DEFAULT_MIMO_BASE_URL
        set(value) = prefs.edit().putString(KEY_MIMO_BASE_URL, value).apply()

    var mimoModel: String
        get() = prefs.getString(KEY_MIMO_MODEL, DEFAULT_MIMO_MODEL) ?: DEFAULT_MIMO_MODEL
        set(value) = prefs.edit().putString(KEY_MIMO_MODEL, value).apply()

    val wsUrl: String
        get() = "ws://$host:$port/"

    data class SessionEntry(val id: String, val preview: String, val timestamp: Long)

    /** Most-recently-used first. */
    var sessionHistory: List<SessionEntry>
        get() {
            val raw = prefs.getString(KEY_SESSION_HISTORY, null) ?: return emptyList()
            return try {
                val arr = JSONArray(raw)
                (0 until arr.length()).map {
                    val o = arr.getJSONObject(it)
                    SessionEntry(o.getString("id"), o.getString("preview"), o.getLong("timestamp"))
                }
            } catch (e: Exception) {
                emptyList()
            }
        }
        private set(value) {
            val arr = JSONArray()
            for (entry in value) {
                arr.put(JSONObject().apply {
                    put("id", entry.id)
                    put("preview", entry.preview)
                    put("timestamp", entry.timestamp)
                })
            }
            prefs.edit().putString(KEY_SESSION_HISTORY, arr.toString()).apply()
        }

    /** Upserts by id, most-recent first, capped — called once per completed turn. */
    fun recordSession(id: String, preview: String) {
        if (id.isEmpty() || id == "null") return
        val rest = sessionHistory.filterNot { it.id == id }
        sessionHistory = (listOf(SessionEntry(id, preview, System.currentTimeMillis())) + rest)
            .take(MAX_SESSION_HISTORY)
    }
}
