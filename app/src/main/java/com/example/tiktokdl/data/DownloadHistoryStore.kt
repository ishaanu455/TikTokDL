package com.example.tiktokdl.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

data class HistoryEntry(
    val title: String,
    val platform: String,
    val kind: String,
    val fileName: String,
    val timestamp: Long,
    val fileUri: String? = null,
    val status: String = "done" // "done" | "downloading" | "failed"
)

/** Lightweight local download history — no database dependency needed for this scale. */
object DownloadHistoryStore {

    private const val PREFS = "download_history"
    private const val KEY_ENTRIES = "entries"
    private const val MAX_ENTRIES = 200

    fun add(context: Context, entry: HistoryEntry) {
        val current = load(context).toMutableList()
        current.add(0, entry)
        save(context, current.take(MAX_ENTRIES))
    }

    /** Removes every entry matching [predicate] (e.g. its original file was deleted externally). */
    fun removeWhere(context: Context, predicate: (HistoryEntry) -> Boolean) {
        val current = load(context)
        val kept = current.filterNot(predicate)
        if (kept.size != current.size) {
            save(context, kept)
        }
    }

    private fun save(context: Context, entries: List<HistoryEntry>) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val array = JSONArray()
        entries.forEach { e ->
            array.put(JSONObject().apply {
                put("title", e.title)
                put("platform", e.platform)
                put("kind", e.kind)
                put("fileName", e.fileName)
                put("timestamp", e.timestamp)
                put("fileUri", e.fileUri)
                put("status", e.status)
            })
        }
        prefs.edit().putString(KEY_ENTRIES, array.toString()).apply()
    }

    fun load(context: Context): List<HistoryEntry> {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val raw = prefs.getString(KEY_ENTRIES, null) ?: return emptyList()
        return try {
            val array = JSONArray(raw)
            (0 until array.length()).map { i ->
                val obj = array.getJSONObject(i)
                HistoryEntry(
                    title = obj.optString("title"),
                    platform = obj.optString("platform"),
                    kind = obj.optString("kind"),
                    fileName = obj.optString("fileName"),
                    timestamp = obj.optLong("timestamp"),
                    fileUri = obj.optString("fileUri").ifBlank { null },
                    status = obj.optString("status").ifBlank { "done" }
                )
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun clear(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().apply()
    }
}
