package com.obsidianwidget

import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

/**
 * Full-note plain-text editor, opened from the widget's edit (pencil) button.
 * Loads the whole note, lets you edit it freely, and replaces the file on
 * save. No markdown rendering — this is a text box, not an Obsidian clone.
 */
class EditNoteActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        supportRequestWindowFeature(android.view.Window.FEATURE_NO_TITLE)
        setContentView(R.layout.activity_edit_note)

        val widgetId = intent.getIntExtra(ObsidianWidgetProvider.EXTRA_WIDGET_ID, -1)
        val vaultManager = if (widgetId >= 0) VaultManager(this, widgetId) else VaultManager(this)

        val titleView = findViewById<TextView>(R.id.edit_title)
        val input = findViewById<EditText>(R.id.edit_input)

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
