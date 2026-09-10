package com.obsidianwidget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import android.view.View
import android.widget.RemoteViews

class ObsidianWidgetProvider : AppWidgetProvider() {

    companion object {
        private const val ACTION_REFRESH = "com.obsidianwidget.ACTION_REFRESH"
        private const val ACTION_EDIT = "com.obsidianwidget.ACTION_EDIT"
        private const val ACTION_OPEN = "com.obsidianwidget.ACTION_OPEN"
        private const val ACTION_TOGGLE = "com.obsidianwidget.ACTION_TOGGLE"
        private const val ACTION_NAV_LEFT = "com.obsidianwidget.ACTION_NAV_LEFT"
        private const val ACTION_NAV_RIGHT = "com.obsidianwidget.ACTION_NAV_RIGHT"

        /**
         * Everything WidgetConfigActivity's Save button can do, reachable
         * without tapping through the UI:
         *
         *   am broadcast -n com.obsidianwidget/.ObsidianWidgetProvider \
         *     -a com.obsidianwidget.ACTION_CONFIGURE --ei extra_widget_id <id> \
         *     [--es note_mode daily|pinned] [--es daily_folder <path>] \
         *     [--es date_format <pattern>] \
         *     [--es pin_note_paths "<vault-relative path>[,<path>...]"] \
         *     [--es theme dark|light] [--es accent_color "#RRGGBB"] \
         *     [--ez sort_unchecked true|false] [--ez tap_checkbox_only true|false] \
         *     [--ez show_todo_count true|false] [--ei widget_alpha 0-100]
         *
         * Every extra is optional and applied only if present (a partial
         * update, not a full replace) — omit whatever you're not changing.
         * pin_note_paths resolves each path within the already-granted vault
         * tree (VaultTools.findFile) and replaces the pinned list outright;
         * it implies pinned mode unless note_mode says otherwise. A widget
         * id with no vault configured yet is a no-op — that one step (the
         * first-ever folder grant) needs a human tap in the system picker,
         * same as any Android app's SAF access; everything past it doesn't.
         */
        private const val ACTION_CONFIGURE = "com.obsidianwidget.ACTION_CONFIGURE"

        /**
         * ACTION_CONFIGURE needs a widget id, and there's no way to list
         * placed widget ids from Termux — dumpsys appwidget needs
         * android.permission.DUMP, which a regular (non-shell) app doesn't
         * have. This writes each instance's id and current settings to
         * <vault>/.obsidian-widget-state.json instead, which Termux can
         * just read directly — no special permission needed, it's the same
         * shared-storage access every other tool/skill in this vault uses.
         *
         *   am broadcast -n com.obsidianwidget/.ObsidianWidgetProvider \
         *     -a com.obsidianwidget.ACTION_DUMP_STATE
         */
        private const val ACTION_DUMP_STATE = "com.obsidianwidget.ACTION_DUMP_STATE"
        private const val STATE_FILE_NAME = ".obsidian-widget-state.json"

        const val EXTRA_LINE_INDEX = "extra_line_index"
        const val EXTRA_WIDGET_ID = "extra_widget_id"
        const val EXTRA_URL = "extra_url"

        fun updateAllWidgets(context: Context) {
            val intent = Intent(context, ObsidianWidgetProvider::class.java).apply {
                action = AppWidgetManager.ACTION_APPWIDGET_UPDATE
            }
            val appWidgetManager = AppWidgetManager.getInstance(context)
            val widgetIds = appWidgetManager.getAppWidgetIds(
                ComponentName(context, ObsidianWidgetProvider::class.java)
            )
            intent.putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, widgetIds)
            context.sendBroadcast(intent)
        }
    }

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray
    ) {
        for (appWidgetId in appWidgetIds) {
            updateWidget(context, appWidgetManager, appWidgetId)
        }
    }

    override fun onDeleted(context: Context, appWidgetIds: IntArray) {
        for (id in appWidgetIds) {
            VaultManager.deleteWidgetPrefs(context, id)
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)

        when (intent.action) {
            ACTION_REFRESH -> updateAllWidgets(context)
            ACTION_EDIT -> {
                val widgetId = intent.getIntExtra(EXTRA_WIDGET_ID, -1)
                val notePath = intent.getStringExtra("note_path")
                val editIntent = Intent(context, EditNoteActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                    if (!notePath.isNullOrEmpty()) {
                        // Lets a broadcaster with no bound widget instance (e.g. a
                        // freeform content panel) open an arbitrary vault note by
                        // path, the same way the notes-browser widget does.
                        putExtra(EditNoteActivity.EXTRA_NOTE_PATH, notePath)
                    } else {
                        putExtra(EXTRA_WIDGET_ID, widgetId)
                    }
                }
                context.startActivity(editIntent)
            }
            ACTION_OPEN -> {
                val widgetId = intent.getIntExtra(EXTRA_WIDGET_ID, -1)
                openObsidian(context, widgetId)
            }
            ACTION_TOGGLE -> {
                val url = intent.getStringExtra(EXTRA_URL)
                if (!url.isNullOrEmpty()) {
                    try {
                        val browseIntent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                            flags = Intent.FLAG_ACTIVITY_NEW_TASK
                        }
                        context.startActivity(browseIntent)
                    } catch (_: Exception) { }
                    return
                }
                val lineIndex = intent.getIntExtra(EXTRA_LINE_INDEX, -1)
                val widgetId = intent.getIntExtra(EXTRA_WIDGET_ID, -1)
                if (lineIndex >= 0 && widgetId >= 0) {
                    val vaultManager = VaultManager(context, widgetId)
                    vaultManager.toggleChecklistItem(lineIndex)
                    val appWidgetManager = AppWidgetManager.getInstance(context)
                    updateWidget(context, appWidgetManager, widgetId)
                }
            }
            ACTION_NAV_LEFT, ACTION_NAV_RIGHT -> {
                val widgetId = intent.getIntExtra(EXTRA_WIDGET_ID, -1)
                if (widgetId >= 0) {
                    val vaultManager = VaultManager(context, widgetId)
                    vaultManager.navigateNote(if (intent.action == ACTION_NAV_LEFT) -1 else 1)
                    val appWidgetManager = AppWidgetManager.getInstance(context)
                    updateWidget(context, appWidgetManager, widgetId)
                }
            }
            ACTION_CONFIGURE -> {
                val widgetId = intent.getIntExtra(EXTRA_WIDGET_ID, -1)
                if (widgetId >= 0) {
                    applyConfigure(context, widgetId, intent)
                    updateWidget(context, AppWidgetManager.getInstance(context), widgetId)
                }
            }
            ACTION_DUMP_STATE -> dumpState(context)
        }
    }

    private fun dumpState(context: Context) {
        val vaultUri = VaultManager(context).vaultUri ?: return
        val appWidgetManager = AppWidgetManager.getInstance(context)
        val ids = appWidgetManager.getAppWidgetIds(ComponentName(context, ObsidianWidgetProvider::class.java))

        val arr = org.json.JSONArray()
        for (id in ids) {
            val vm = VaultManager(context, id)
            arr.put(org.json.JSONObject().apply {
                put("widget_id", id)
                put("note_mode", if (vm.noteMode == VaultManager.NoteMode.PINNED) "pinned" else "daily")
                put("daily_folder", vm.dailyFolder)
                put("date_format", vm.dateFormat)
                put("pinned_notes", org.json.JSONArray(vm.pinnedNoteNameList))
                put("current_note_index", vm.currentNoteIndex)
                put("theme", vm.widgetTheme)
                put("accent_color", vm.accentColor)
                put("sort_unchecked", vm.sortUnchecked)
                put("tap_checkbox_only", vm.tapCheckboxOnly)
                put("show_todo_count", vm.showTodoCount)
            })
        }

        val root = androidx.documentfile.provider.DocumentFile.fromTreeUri(context, vaultUri) ?: return
        val file = root.findFile(STATE_FILE_NAME) ?: root.createFile("application/json", STATE_FILE_NAME) ?: return
        try {
            context.contentResolver.openOutputStream(file.uri, "wt")?.use { os ->
                java.io.OutputStreamWriter(os).use { it.write(arr.toString(2)) }
            }
        } catch (e: Exception) {
            // Best-effort — a failed dump just means the agent tries again.
        }
    }

    private fun applyConfigure(context: Context, widgetId: Int, intent: Intent) {
        val vaultManager = VaultManager(context, widgetId)

        intent.getStringExtra("pin_note_paths")?.let { raw ->
            val vaultUri = vaultManager.vaultUri
            if (vaultUri != null) {
                val tools = VaultTools(context, vaultUri)
                val uris = mutableListOf<String>()
                val names = mutableListOf<String>()
                for (path in raw.split(",", "\n").map { it.trim() }.filter { it.isNotEmpty() }) {
                    val file = tools.findFile(path) ?: continue
                    uris.add(file.uri.toString())
                    names.add(path.substringAfterLast('/'))
                }
                vaultManager.pinnedNoteUriList = uris
                vaultManager.pinnedNoteNameList = names
                vaultManager.currentNoteIndex = 0
                if (uris.isNotEmpty() && !intent.hasExtra("note_mode")) {
                    vaultManager.noteMode = VaultManager.NoteMode.PINNED
                }
            }
        }
        intent.getStringExtra("note_mode")?.let {
            vaultManager.noteMode = if (it == "pinned") VaultManager.NoteMode.PINNED else VaultManager.NoteMode.DAILY
        }
        intent.getStringExtra("daily_folder")?.let { vaultManager.dailyFolder = it }
        intent.getStringExtra("date_format")?.let { vaultManager.dateFormat = it }
        intent.getStringExtra("theme")?.let { vaultManager.widgetTheme = if (it == "light") "light" else "dark" }
        intent.getStringExtra("accent_color")?.let { vaultManager.accentColor = it }
        if (intent.hasExtra("sort_unchecked")) vaultManager.sortUnchecked = intent.getBooleanExtra("sort_unchecked", false)
        if (intent.hasExtra("tap_checkbox_only")) vaultManager.tapCheckboxOnly = intent.getBooleanExtra("tap_checkbox_only", false)
        if (intent.hasExtra("show_todo_count")) vaultManager.showTodoCount = intent.getBooleanExtra("show_todo_count", false)
        if (intent.hasExtra("widget_alpha")) vaultManager.widgetAlpha = intent.getIntExtra("widget_alpha", 100)
    }

    private fun updateWidget(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetId: Int
    ) {
        val views = RemoteViews(context.packageName, R.layout.widget_layout)
        val vaultManager = VaultManager(context, appWidgetId)

        // Set title based on mode
        val noteCount = if (vaultManager.noteMode == VaultManager.NoteMode.PINNED) vaultManager.getPinnedNoteCount() else 0
        views.setTextViewText(R.id.widget_date, vaultManager.getWidgetTitle())

        // Check if note has checklist items
        val allItems = vaultManager.parseChecklist()
        val hasChecklist = allItems.any { !it.isPlainText }

        // Show TODO count if enabled
        if (vaultManager.showTodoCount && hasChecklist) {
            val unchecked = allItems.count { !it.isPlainText && !it.isChecked }
            val total = allItems.count { !it.isPlainText }
            views.setTextViewText(R.id.widget_todo_count, "$unchecked of $total remaining")
            views.setViewVisibility(R.id.widget_todo_count, View.VISIBLE)
        } else {
            views.setViewVisibility(R.id.widget_todo_count, View.GONE)
        }

        if (allItems.isNotEmpty()) {
            // Any note with content — checklist or plain prose alike — goes
            // through the ListView. RemoteViews has no ScrollView, so this is
            // also what makes a plain note's preview actually scroll.
            views.setViewVisibility(R.id.widget_checklist, View.VISIBLE)
            views.setViewVisibility(R.id.widget_note_preview, View.GONE)

            // Set up RemoteViews adapter for ListView
            val serviceIntent = Intent(context, ChecklistWidgetService::class.java).apply {
                putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
                data = Uri.parse(toUri(Intent.URI_INTENT_SCHEME))
            }
            views.setRemoteAdapter(R.id.widget_checklist, serviceIntent)

            // Set up pending intent template for item clicks (toggle)
            val toggleIntent = Intent(context, ObsidianWidgetProvider::class.java).apply {
                action = ACTION_TOGGLE
                putExtra(EXTRA_WIDGET_ID, appWidgetId)
            }
            val togglePendingIntent = PendingIntent.getBroadcast(
                context, appWidgetId, toggleIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
            )
            views.setPendingIntentTemplate(R.id.widget_checklist, togglePendingIntent)

            // Notify data changed
            appWidgetManager.notifyAppWidgetViewDataChanged(appWidgetId, R.id.widget_checklist)

        } else {
            // Nothing to list: not configured, no note picked, an empty file,
            // or a read error. parseChecklist() already attempted a read above,
            // so lastReadError (if any) is already set.
            views.setViewVisibility(R.id.widget_checklist, View.GONE)
            views.setViewVisibility(R.id.widget_note_preview, View.VISIBLE)

            val message = when {
                vaultManager.noteMode == VaultManager.NoteMode.PINNED && vaultManager.getWidgetNoteUri() == null ->
                    "No note selected — tap the wrench to configure"
                !vaultManager.isVaultConfigured && vaultManager.noteMode == VaultManager.NoteMode.DAILY ->
                    context.getString(R.string.no_vault_selected)
                vaultManager.lastReadError != null ->
                    "Can't read note:\n${vaultManager.lastReadError}"
                else ->
                    context.getString(R.string.no_daily_note)
            }
            views.setTextViewText(R.id.widget_note_preview, message)
        }

        // Title click always opens note in Obsidian
        views.setOnClickPendingIntent(
            R.id.widget_date,
            createActionIntent(context, ACTION_OPEN, appWidgetId)
        )

        // Cycle note arrow (visible only for multi-note)
        if (noteCount > 1) {
            views.setViewVisibility(R.id.widget_cycle_note, View.VISIBLE)
            views.setOnClickPendingIntent(
                R.id.widget_cycle_note,
                createActionIntent(context, ACTION_NAV_RIGHT, appWidgetId)
            )
        } else {
            views.setViewVisibility(R.id.widget_cycle_note, View.GONE)
        }

        // Settings button opens widget config
        val configIntent = Intent(context, WidgetConfigActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
        }
        views.setOnClickPendingIntent(
            R.id.widget_settings,
            PendingIntent.getActivity(
                context, appWidgetId + 10000, configIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        )

        // Refresh button
        views.setOnClickPendingIntent(
            R.id.widget_refresh,
            createActionIntent(context, ACTION_REFRESH, appWidgetId)
        )

        // Edit (pencil) button — full-note plain-text editor
        views.setOnClickPendingIntent(
            R.id.widget_edit,
            createActionIntent(context, ACTION_EDIT, appWidgetId)
        )

        // Absorb taps on empty space so they don't trigger launcher reconfigure
        views.setOnClickPendingIntent(
            R.id.widget_root,
            createActionIntent(context, ACTION_REFRESH, appWidgetId)
        )

        // Apply widget transparency
        views.setFloat(R.id.widget_root, "setAlpha", vaultManager.widgetAlpha / 100f)

        // Apply theme colors
        val colors = vaultManager.getThemeColors()
        val isDark = vaultManager.widgetTheme == "dark"
        views.setInt(R.id.widget_root, "setBackgroundResource",
            if (isDark) R.drawable.widget_background else R.drawable.widget_background_light)
        views.setTextColor(R.id.widget_date, colors.text)
        views.setTextColor(R.id.widget_note_preview, colors.textSecondary)
        views.setTextColor(R.id.widget_todo_count, colors.textSecondary)

        // Tint header icons to match theme
        views.setInt(R.id.widget_refresh, "setColorFilter", colors.text)
        views.setInt(R.id.widget_settings, "setColorFilter", colors.text)
        views.setInt(R.id.widget_cycle_note, "setColorFilter", colors.text)
        views.setInt(R.id.widget_edit, "setColorFilter", colors.text)

        appWidgetManager.updateAppWidget(appWidgetId, views)
    }

    private fun createActionIntent(context: Context, action: String, appWidgetId: Int): PendingIntent {
        val intent = Intent(context, ObsidianWidgetProvider::class.java).apply {
            this.action = action
            putExtra(EXTRA_WIDGET_ID, appWidgetId)
        }
        return PendingIntent.getBroadcast(
            context, action.hashCode() + appWidgetId, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun openObsidian(context: Context, widgetId: Int) {
        val vaultManager = VaultManager(context, widgetId)
        val vaultName = vaultManager.vaultName
        val vaultUri = vaultManager.vaultUri

        // Try to open the specific note in Obsidian via its URI scheme
        if (vaultName != null) {
            val noteName = when (vaultManager.noteMode) {
                VaultManager.NoteMode.PINNED ->
                    resolvePinnedNotePath(vaultUri, vaultManager)
                VaultManager.NoteMode.DAILY -> {
                    val folder = vaultManager.dailyFolder
                    val date = java.time.LocalDate.now()
                        .format(java.time.format.DateTimeFormatter.ofPattern(vaultManager.dateFormat))
                    if (folder.isNotBlank()) "$folder/$date" else date
                }
            }

            if (noteName != null) {
                try {
                    val obsidianUri = Uri.Builder()
                        .scheme("obsidian")
                        .authority("open")
                        .appendQueryParameter("vault", vaultName)
                        .appendQueryParameter("file", noteName)
                        .build()
                    // Obsidian's MainActivity is launchMode="singleTask" (checked
                    // its manifest), so CLEAR_TOP/SINGLE_TOP alone just resurface
                    // the existing window via onNewIntent — which doesn't actually
                    // navigate to the newly requested file. CLEAR_TASK forces a
                    // full restart so the deep link is processed like a cold start,
                    // at the cost of losing whatever Obsidian had open before.
                    val deepLinkIntent = Intent(Intent.ACTION_VIEW, obsidianUri).apply {
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                                Intent.FLAG_ACTIVITY_CLEAR_TASK
                    }
                    context.startActivity(deepLinkIntent)
                    return
                } catch (_: Exception) {
                    // Obsidian not installed, fall through
                }
            }
        }

        // Fallback: try launching Obsidian app directly
        try {
            val obsidianIntent = context.packageManager
                .getLaunchIntentForPackage("md.obsidian")
            if (obsidianIntent != null) {
                obsidianIntent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
                context.startActivity(obsidianIntent)
                return
            }
        } catch (_: Exception) {
            // Obsidian not installed
        }

        // Final fallback: open our settings
        val fallbackIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        context.startActivity(fallbackIntent)
    }

    private fun resolvePinnedNotePath(vaultUri: Uri?, vaultManager: VaultManager): String? {
        val fallbackName = vaultManager.getCurrentPinnedNoteName()?.removeSuffix(".md")
        val noteUri = vaultManager.getCurrentPinnedNoteUri() ?: return fallbackName
        val rootUri = vaultUri ?: return fallbackName

        return try {
            val treeId = DocumentsContract.getTreeDocumentId(rootUri)
            val docId = DocumentsContract.getDocumentId(noteUri)

            val relativePath = when {
                docId.startsWith("$treeId/") -> docId.removePrefix("$treeId/")
                docId == treeId -> ""
                ':' in docId -> docId.substringAfter(':')
                else -> docId
            }

            relativePath
                .trim('/')
                .takeIf { it.isNotEmpty() }
                ?.removeSuffix(".md")
                ?: fallbackName
        } catch (_: Exception) {
            fallbackName
        }
    }
}
