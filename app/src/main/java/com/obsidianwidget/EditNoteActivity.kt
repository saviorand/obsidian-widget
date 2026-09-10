package com.obsidianwidget

import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

/**
 * Full-note plain-text editor. Two ways in: from a widget's edit (pencil)
 * button, which edits that widget's configured note (daily/pinned) via
 * VaultManager; or from the notes-browser widget with EXTRA_NOTE_PATH, which
 * edits an arbitrary vault-relative path via VaultTools instead. No markdown
 * rendering — this is a text box, not an Obsidian clone.
 */
class EditNoteActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_NOTE_PATH = "extra_note_path"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        supportRequestWindowFeature(android.view.Window.FEATURE_NO_TITLE)
        setContentView(R.layout.activity_edit_note)

        val titleView = findViewById<TextView>(R.id.edit_title)
        val input = findViewById<EditText>(R.id.edit_input)
        val notePath = intent.getStringExtra(EXTRA_NOTE_PATH)

        if (notePath != null) {
            val vaultUri = VaultManager(this).vaultUri
            if (vaultUri == null) {
                Toast.makeText(this, R.string.vault_not_configured, Toast.LENGTH_SHORT).show()
                finish()
                return
            }
            val tools = VaultTools(this, vaultUri)
            titleView.text = "Edit: ${notePath.substringAfterLast('/').removeSuffix(".md")}"
            val existing = tools.readNote(notePath)
            input.setText(if (existing.startsWith("no note at ")) "" else existing)
            input.setSelection(input.text.length)

            findViewById<Button>(R.id.btn_cancel).setOnClickListener { finish() }
            findViewById<Button>(R.id.btn_save).setOnClickListener {
                val result = tools.writeNote(notePath, input.text.toString())
                Toast.makeText(this, result, Toast.LENGTH_SHORT).show()
                NotesWidgetProvider.updateAllWidgets(this)
                finish()
            }
            return
        }

        val widgetId = intent.getIntExtra(ObsidianWidgetProvider.EXTRA_WIDGET_ID, -1)
        val vaultManager = if (widgetId >= 0) VaultManager(this, widgetId) else VaultManager(this)

        titleView.text = "Edit: ${vaultManager.getWidgetTitle()}"

        if (!vaultManager.isVaultConfigured) {
            Toast.makeText(this, R.string.vault_not_configured, Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        input.setText(vaultManager.readWidgetNote() ?: "")
        input.setSelection(input.text.length)

        findViewById<Button>(R.id.btn_cancel).setOnClickListener {
            finish()
        }

        findViewById<Button>(R.id.btn_save).setOnClickListener {
            val success = vaultManager.writeWidgetNote(input.text.toString())
            if (success) {
                Toast.makeText(this, R.string.note_saved, Toast.LENGTH_SHORT).show()
                ObsidianWidgetProvider.updateAllWidgets(this)
            } else {
                Toast.makeText(this, R.string.error_saving, Toast.LENGTH_SHORT).show()
            }
            finish()
        }
    }
}
