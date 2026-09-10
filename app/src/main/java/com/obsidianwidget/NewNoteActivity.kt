package com.obsidianwidget

import android.os.Bundle
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity

/**
 * A dialog with nothing behind it: asks for a name, creates the note via
 * VaultTools, opens it in EditNoteActivity, and finishes either way — there
 * is no "screen" here, just the prompt the notes widget's + button needs.
 */
class NewNoteActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val vaultUri = VaultManager(this).vaultUri
        if (vaultUri == null) {
            Toast.makeText(this, R.string.vault_not_configured, Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        val view = layoutInflater.inflate(R.layout.dialog_new_note, null)
        val nameField = view.findViewById<EditText>(R.id.new_note_name)

        AlertDialog.Builder(this)
            .setTitle("New note")
            .setView(view)
            .setPositiveButton("Create") { _, _ ->
                val raw = nameField.text.toString().trim()
                if (raw.isEmpty()) {
                    finish()
                    return@setPositiveButton
                }
                val path = if (raw.endsWith(".md")) raw else "$raw.md"
                val tools = VaultTools(this, vaultUri)
                val result = tools.writeNote(path, "")
                Toast.makeText(this, result, Toast.LENGTH_SHORT).show()
                NotesWidgetProvider.updateAllWidgets(this)
                startActivity(android.content.Intent(this, EditNoteActivity::class.java).apply {
                    flags = android.content.Intent.FLAG_ACTIVITY_NEW_TASK
                    putExtra(EditNoteActivity.EXTRA_NOTE_PATH, path)
                })
                finish()
            }
            .setNegativeButton("Cancel") { _, _ -> finish() }
            .setOnCancelListener { finish() }
            .show()
    }
}
