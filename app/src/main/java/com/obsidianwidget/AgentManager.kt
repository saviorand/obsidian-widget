package com.obsidianwidget

import android.content.Context
import android.content.SharedPreferences

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
}
