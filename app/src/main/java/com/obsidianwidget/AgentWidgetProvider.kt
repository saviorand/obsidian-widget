package com.obsidianwidget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews

/**
 * A glance surface for the agent chat, not a chat UI in itself — RemoteViews
 * can't host an EditText or a scrolling message list. Tapping it opens
 * AgentChatActivity, a real Activity, where the actual conversation happens.
 */
class AgentWidgetProvider : AppWidgetProvider() {

    companion object {
        /**
         * Everything the chat's own settings dialog can do, reachable
         * without opening it — settings here are global (one agent-host,
         * one MiMo key), so no widget id is needed:
         *
         *   am broadcast -n com.obsidianwidget/.AgentWidgetProvider \
         *     -a com.obsidianwidget.ACTION_CONFIGURE \
         *     [--es host <ip>] [--ei port <port>] \
         *     [--es mimo_key <key>] [--es mimo_base_url <url>] \
         *     [--es mimo_model <model>] [--ez clear_session true]
         *
         * Every extra is optional and applied only if present.
         */
        private const val ACTION_CONFIGURE = "com.obsidianwidget.ACTION_CONFIGURE"

        /** Same reasoning as ObsidianWidgetProvider's — see its own dump. */
        private const val ACTION_DUMP_STATE = "com.obsidianwidget.agent.ACTION_DUMP_STATE"
        private const val STATE_FILE_NAME = ".agent-widget-state.json"

        fun updateAllWidgets(context: Context) {
            val appWidgetManager = AppWidgetManager.getInstance(context)
            val widgetIds = appWidgetManager.getAppWidgetIds(
                ComponentName(context, AgentWidgetProvider::class.java)
            )
            for (id in widgetIds) {
                updateWidget(context, appWidgetManager, id)
            }
        }

        private fun updateWidget(context: Context, appWidgetManager: AppWidgetManager, appWidgetId: Int) {
            val views = RemoteViews(context.packageName, R.layout.agent_widget_layout)
            val agentManager = AgentManager(context)

            val preview = agentManager.lastPreview
            views.setTextViewText(
                R.id.agent_widget_preview,
                if (preview.isNullOrBlank()) context.getString(R.string.agent_widget_placeholder) else preview
            )

            val chatIntent = Intent(context, AgentChatActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            }
            views.setOnClickPendingIntent(
                R.id.agent_widget_root,
                PendingIntent.getActivity(
                    context, appWidgetId,
                    chatIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
            )

            appWidgetManager.updateAppWidget(appWidgetId, views)
        }
    }

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        for (id in appWidgetIds) {
            updateWidget(context, appWidgetManager, id)
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        when (intent.action) {
            ACTION_CONFIGURE -> {
                val agentManager = AgentManager(context)
                intent.getStringExtra("host")?.let { agentManager.host = it }
                if (intent.hasExtra("port")) agentManager.port = intent.getIntExtra("port", AgentManager.DEFAULT_PORT)
                intent.getStringExtra("mimo_key")?.let { agentManager.mimoKey = it }
                intent.getStringExtra("mimo_base_url")?.let { agentManager.mimoBaseUrl = it }
                intent.getStringExtra("mimo_model")?.let { agentManager.mimoModel = it }
                if (intent.getBooleanExtra("clear_session", false)) agentManager.sessionId = null
                updateAllWidgets(context)
            }
            ACTION_DUMP_STATE -> dumpState(context)
        }
    }

    private fun dumpState(context: Context) {
        val vaultUri = VaultManager(context).vaultUri ?: return
        val agentManager = AgentManager(context)
        val appWidgetManager = AppWidgetManager.getInstance(context)
        val ids = appWidgetManager.getAppWidgetIds(ComponentName(context, AgentWidgetProvider::class.java))

        val obj = org.json.JSONObject().apply {
            put("widget_ids", org.json.JSONArray(ids.toList()))
            put("host", agentManager.host)
            put("port", agentManager.port)
            put("mimo_base_url", agentManager.mimoBaseUrl)
            put("mimo_model", agentManager.mimoModel)
            put("mimo_key_set", agentManager.mimoKey.isNotBlank())
            put("session_id", agentManager.sessionId)
            put("last_preview", agentManager.lastPreview)
        }

        val root = androidx.documentfile.provider.DocumentFile.fromTreeUri(context, vaultUri) ?: return
        val file = root.findFile(STATE_FILE_NAME) ?: root.createFile("application/json", STATE_FILE_NAME) ?: return
        try {
            context.contentResolver.openOutputStream(file.uri, "wt")?.use { os ->
                java.io.OutputStreamWriter(os).use { it.write(obj.toString(2)) }
            }
        } catch (e: Exception) {
            // Best-effort — a failed dump just means the agent tries again.
        }
    }
}
