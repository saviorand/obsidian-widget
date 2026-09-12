package com.obsidianwidget

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity

/**
 * The only entry point left that can grant (or re-grant) the vault folder.
 *
 * WidgetConfigActivity only ever receives a real AppWidget id through
 * Android's own widget-placement flow, which only happens when the widget
 * declares `android:configure` in its provider info -- and 8bfb262 removed
 * that declaration deliberately, because AIO Launcher refuses to bind any
 * widget that declares a configure activity at all. That traded away the
 * only path to the vault picker without replacing it: WidgetConfigActivity
 * itself still `finish()`s immediately without a valid widget id (see its
 * onCreate), so it's unreachable now regardless of launcher.
 *
 * This picks the vault directly, independent of any specific widget or
 * launcher -- reachable from any launcher's app drawer, since this is a
 * normal LAUNCHER activity. `vaultUri` is a global setting (VaultManager),
 * so granting it here is enough for every already-placed widget too.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var vaultManager: VaultManager
    private lateinit var statusText: TextView

    private val folderPicker = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri: Uri? -> uri?.let { onVaultSelected(it) } }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        vaultManager = VaultManager(this)
        statusText = findViewById(R.id.main_vault_status)

        findViewById<Button>(R.id.main_select_vault).setOnClickListener {
            folderPicker.launch(null)
        }

        refreshStatus()
    }

    override fun onResume() {
        super.onResume()
        refreshStatus()
    }

    private fun refreshStatus() {
        statusText.text = vaultManager.vaultName?.let { "Vault: $it" } ?: "No vault selected yet"
    }

    private fun onVaultSelected(uri: Uri) {
        contentResolver.takePersistableUriPermission(
            uri,
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        )
        vaultManager.vaultUri = uri
        vaultManager.vaultName = uri.lastPathSegment?.substringAfterLast(':') ?: "Vault"
        refreshStatus()

        // Every already-placed widget reads the same global vaultUri --
        // push them all so nothing stays stuck showing its unconfigured state.
        ObsidianWidgetProvider.updateAllWidgets(this)
        NotesWidgetProvider.updateAllWidgets(this)
        AgentWidgetProvider.updateAllWidgets(this)
    }
}
