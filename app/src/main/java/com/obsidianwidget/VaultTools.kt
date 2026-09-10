package com.obsidianwidget

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter

/**
 * The four vault tools the MiMo fallback exposes to the model — same tools,
 * same names, same behavior as obsidian-agent's plugin/main.js MiMo client,
 * so a conversation reads the same regardless of which fallback answered it.
 * There it's Obsidian's own Vault API; here it's the SAF tree VaultManager
 * already holds (vaultUri), since this runs outside Obsidian entirely.
 */
class VaultTools(private val context: Context, private val vaultUri: Uri) {

    private fun root(): DocumentFile? = DocumentFile.fromTreeUri(context, vaultUri)

    private fun resolveDir(path: String, createDirs: Boolean): DocumentFile? {
        var current = root() ?: return null
        if (path.isBlank()) return current
        for (segment in path.trim('/').split("/").filter { it.isNotEmpty() }) {
            val existing = current.findFile(segment)
            current = when {
                existing != null && existing.isDirectory -> existing
                existing == null && createDirs -> current.createDirectory(segment) ?: return null
                else -> return null
            }
        }
        return current
    }

    private fun splitPath(path: String): Pair<String, String> {
        val trimmed = path.trim('/')
        val slash = trimmed.lastIndexOf('/')
        return if (slash < 0) "" to trimmed else trimmed.substring(0, slash) to trimmed.substring(slash + 1)
    }

    private fun resolveFile(path: String): DocumentFile? {
        val (parentPath, leaf) = splitPath(path)
        return resolveDir(parentPath, createDirs = false)?.findFile(leaf)
    }

    /**
     * Public resolution by vault-relative path, for callers that need the
     * DocumentFile itself (its Uri) rather than its contents — e.g. pinning
     * a note by path needs a Uri to store, the same shape VaultManager
     * already persists for a note picked through the system file picker.
     * No new SAF grant involved: this only navigates within the
     * already-permitted vault tree.
     */
    fun findFile(path: String): DocumentFile? = resolveFile(path)

    fun readNote(path: String): String {
        val file = resolveFile(path) ?: return "no note at $path"
        return try {
            context.contentResolver.openInputStream(file.uri)?.use {
                BufferedReader(InputStreamReader(it)).readText()
            } ?: "no note at $path"
        } catch (e: Exception) {
            "no note at $path"
        }
    }

    fun writeNote(path: String, content: String): String {
        val (parentPath, leaf) = splitPath(path)
        val existing = resolveDir(parentPath, createDirs = false)?.findFile(leaf)
        if (existing != null) {
            return try {
                writeContent(existing.uri, content)
                "updated $path"
            } catch (e: Exception) {
                "error writing $path: ${e.message}"
            }
        }
        val parent = resolveDir(parentPath, createDirs = true)
            ?: return "error: could not create folder for $path"
        val created = parent.createFile("text/markdown", leaf)
            ?: return "error: could not create $path"
        return try {
            writeContent(created.uri, content)
            "created $path"
        } catch (e: Exception) {
            "error writing $path: ${e.message}"
        }
    }

    private fun writeContent(uri: Uri, content: String) {
        context.contentResolver.openOutputStream(uri, "wt")?.use { os ->
            OutputStreamWriter(os).use { it.write(content) }
        }
    }

    private fun walk(dir: DocumentFile, prefix: String, out: MutableList<Pair<String, DocumentFile>>) {
        for (child in dir.listFiles()) {
            val name = child.name ?: continue
            if (child.isDirectory) {
                // Any dot-directory, not just .obsidian/.git — .claude alone
                // was a quarter of one real vault's directory count, all SAF
                // round-trips wasted on non-notes (and .claude's own files
                // would otherwise show up in the notes list as if they were
                // vault content).
                if (name.startsWith(".")) continue
                walk(child, if (prefix.isEmpty()) name else "$prefix/$name", out)
            } else {
                out.add((if (prefix.isEmpty()) name else "$prefix/$name") to child)
            }
        }
    }

    data class NoteEntry(val path: String, val lastModified: Long)

    /** All .md notes, most recently modified first — what the notes-browser widget lists. */
    fun listAllNotesSorted(limit: Int = 200): List<NoteEntry> {
        val root = root() ?: return emptyList()
        val all = mutableListOf<Pair<String, DocumentFile>>()
        walk(root, "", all)
        return all
            .filter { it.first.endsWith(".md") }
            .map { NoteEntry(it.first, it.second.lastModified()) }
            .sortedByDescending { it.lastModified }
            .take(limit)
    }

    fun listNotes(folder: String?): String {
        val root = root() ?: return "(no notes)"
        val all = mutableListOf<Pair<String, DocumentFile>>()
        walk(root, "", all)
        val filtered = if (folder.isNullOrBlank()) all else all.filter { it.first.startsWith(folder) }
        val paths = filtered.map { it.first }.take(200)
        return if (paths.isEmpty()) "(no notes)" else paths.joinToString("\n")
    }

    fun searchNotes(query: String): String {
        if (query.isBlank()) return "(empty query)"
        val root = root() ?: return "(no matches)"
        val all = mutableListOf<Pair<String, DocumentFile>>()
        walk(root, "", all)
        val needle = query.lowercase()
        val results = mutableListOf<String>()
        for ((path, file) in all) {
            if (results.size >= 40) break
            if (!path.endsWith(".md")) continue
            val content = try {
                context.contentResolver.openInputStream(file.uri)?.use {
                    BufferedReader(InputStreamReader(it)).readText()
                }
            } catch (e: Exception) {
                null
            } ?: continue
            val line = content.lines().firstOrNull { it.lowercase().contains(needle) } ?: continue
            results.add("$path: ${line.trim().take(160)}")
        }
        return if (results.isEmpty()) "(no matches)" else results.joinToString("\n")
    }

    fun run(name: String, args: JSONObject): String = when (name) {
        "read_note" -> readNote(args.optString("path"))
        "write_note" -> writeNote(args.optString("path"), args.optString("content"))
        "list_notes" -> listNotes(args.optString("folder", "").ifBlank { null })
        "search_notes" -> searchNotes(args.optString("query"))
        else -> "unknown tool $name"
    }
}
