package com.obsidianwidget

import android.content.Context
import android.content.Intent
import android.view.View
import android.widget.RemoteViews
import android.widget.RemoteViewsService

class NotesBrowserService : RemoteViewsService() {
    override fun onGetViewFactory(intent: Intent): RemoteViewsFactory =
        NotesBrowserFactory(applicationContext)
}

/** Flat, most-recently-modified-first list of every note in the vault. */
class NotesBrowserFactory(private val context: Context) : RemoteViewsService.RemoteViewsFactory {

    private var notes = listOf<VaultTools.NoteEntry>()

    override fun onCreate() {}

    override fun onDataSetChanged() {
        val vaultUri = VaultManager(context).vaultUri
        notes = if (vaultUri != null) VaultTools(context, vaultUri).listAllNotesSorted() else emptyList()
    }

    override fun onDestroy() {
        notes = emptyList()
    }

    override fun getCount(): Int = notes.size

    override fun getViewAt(position: Int): RemoteViews {
        val entry = notes[position]
        val views = RemoteViews(context.packageName, R.layout.notes_browser_item)
        val slash = entry.path.lastIndexOf('/')
        val name = entry.path.substring(slash + 1).removeSuffix(".md")
        val folder = if (slash > 0) entry.path.substring(0, slash) else ""

        views.setTextViewText(R.id.notes_item_name, name)
        if (folder.isNotEmpty()) {
            views.setTextViewText(R.id.notes_item_folder, folder)
            views.setViewVisibility(R.id.notes_item_folder, View.VISIBLE)
        } else {
            views.setViewVisibility(R.id.notes_item_folder, View.GONE)
        }

        views.setOnClickFillInIntent(R.id.notes_item_root, Intent().apply {
            putExtra(EditNoteActivity.EXTRA_NOTE_PATH, entry.path)
        })
        return views
    }

    override fun getLoadingView(): RemoteViews? = null
    override fun getViewTypeCount(): Int = 1
    override fun getItemId(position: Int): Long = notes[position].path.hashCode().toLong()
    override fun hasStableIds(): Boolean = true
}
