package com.obsidianwidget

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.documentfile.provider.DocumentFile
import java.io.OutputStreamWriter

/**
 * Generic write-back sink for AIO Launcher scripts: any script can report
 * arbitrary state (e.g. current home-screen widget layout) by broadcasting
 * a string payload here, which gets persisted into the vault for an agent
 * to read — the same role ACTION_DUMP_STATE plays for this app's own
 * widgets, but for state AIO itself owns (like screen layout) that this
 * app has no other way to observe.
 */
class AioBridgeReceiver : BroadcastReceiver() {
    companion object {
        private const val ACTION_REPORT = "com.obsidianwidget.aio.ACTION_REPORT"
        private const val STATE_FILE_NAME = ".aio-layout-state.json"
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_REPORT) return
        val payload = intent.getStringExtra("payload") ?: return

        val vaultUri = VaultManager(context).vaultUri ?: return
        val root = DocumentFile.fromTreeUri(context, vaultUri) ?: return
        val file = root.findFile(STATE_FILE_NAME)
            ?: root.createFile("application/json", STATE_FILE_NAME)
            ?: return
        try {
            context.contentResolver.openOutputStream(file.uri, "wt")?.use { os ->
                OutputStreamWriter(os).use { it.write(payload) }
            }
        } catch (e: Exception) {
            // Best-effort — a failed report just means the agent tries again.
        }
    }
}
