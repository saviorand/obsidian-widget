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
}
