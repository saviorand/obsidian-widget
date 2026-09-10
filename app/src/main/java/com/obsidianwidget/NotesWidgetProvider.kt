package com.obsidianwidget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.view.View
import android.widget.RemoteViews

/**
 * A flat, most-recently-modified-first list of every note in the vault —
 * the "file tree" wish scaled down to what a home-screen widget can
 * actually do well: browse and jump in, not navigate folders. Replaces the
 * note widget's +Add/Note buttons, which this supersedes.
 */
class NotesWidgetProvider : AppWidgetProvider() {

    companion object {
        private const val ACTION_REFRESH = "com.obsidianwidget.notes.ACTION_REFRESH"
        private const val ACTION_NEW_NOTE = "com.obsidianwidget.notes.ACTION_NEW_NOTE"

        fun updateAllWidgets(context: Context) {
            val appWidgetManager = AppWidgetManager.getInstance(context)
            val widgetIds = appWidgetManager.getAppWidgetIds(
                ComponentName(context, NotesWidgetProvider::class.java)
            )
            for (id in widgetIds) updateWidget(context, appWidgetManager, id)
        }

        private fun updateWidget(context: Context, appWidgetManager: AppWidgetManager, appWidgetId: Int) {
            val views = RemoteViews(context.packageName, R.layout.notes_widget_layout)
            val vaultConfigured = VaultManager(context).vaultUri != null

            if (!vaultConfigured) {
                views.setViewVisibility(R.id.notes_widget_list, View.GONE)
                views.setViewVisibility(R.id.notes_widget_empty, View.VISIBLE)
                val pickVaultIntent = Intent(context, MainActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
                views.setOnClickPendingIntent(
                    R.id.notes_widget_empty,
                    PendingIntent.getActivity(
                        context, appWidgetId, pickVaultIntent,
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                    )
                )
            } else {
                views.setViewVisibility(R.id.notes_widget_list, View.VISIBLE)
                views.setViewVisibility(R.id.notes_widget_empty, View.GONE)

                val serviceIntent = Intent(context, NotesBrowserService::class.java).apply {
                    data = Uri.parse(toUri(Intent.URI_INTENT_SCHEME))
                }
                views.setRemoteAdapter(R.id.notes_widget_list, serviceIntent)

                val openNoteIntent = Intent(context, EditNoteActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                }
                val openNotePendingIntent = PendingIntent.getActivity(
                    context, appWidgetId, openNoteIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
                )
                views.setPendingIntentTemplate(R.id.notes_widget_list, openNotePendingIntent)
                appWidgetManager.notifyAppWidgetViewDataChanged(appWidgetId, R.id.notes_widget_list)
            }

            views.setOnClickPendingIntent(R.id.notes_widget_new, createActionIntent(context, ACTION_NEW_NOTE, appWidgetId))
            views.setOnClickPendingIntent(R.id.notes_widget_refresh, createActionIntent(context, ACTION_REFRESH, appWidgetId))

            val colors = VaultManager(context).getThemeColors()
            views.setInt(R.id.notes_widget_root, "setBackgroundResource",
                if (VaultManager(context).widgetTheme == "dark") R.drawable.widget_background else R.drawable.widget_background_light)
            views.setTextColor(R.id.notes_widget_title, colors.text)
            views.setInt(R.id.notes_widget_new, "setColorFilter", colors.text)
            views.setInt(R.id.notes_widget_refresh, "setColorFilter", colors.text)

            appWidgetManager.updateAppWidget(appWidgetId, views)
        }

        private fun createActionIntent(context: Context, action: String, appWidgetId: Int): PendingIntent {
            val intent = Intent(context, NotesWidgetProvider::class.java).apply {
                this.action = action
                putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
            }
            return PendingIntent.getBroadcast(
                context, action.hashCode() + appWidgetId, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        }
    }

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        for (id in appWidgetIds) updateWidget(context, appWidgetManager, id)
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        when (intent.action) {
            ACTION_REFRESH -> updateAllWidgets(context)
            ACTION_NEW_NOTE -> {
                context.startActivity(Intent(context, NewNoteActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                })
            }
        }
    }
}
