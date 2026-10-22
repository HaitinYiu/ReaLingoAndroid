package com.realingo.app.core

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Translation history: one JSONL file per session, appended as sentences settle.
 * Ported from ReaLingo's src-tauri/src/history.rs — the id is an ISO timestamp, so it sorts
 * chronologically for free.
 */
object HistoryStore {
    data class SessionMeta(
        val id: String,
        val at: Long,
        val source: String,
        val target: String,
        val model: String,
        val kind: String,
        val count: Int,
        val preview: String,
    )

    data class Entry(val at: Long, val source: String, val target: String)

    private fun dir(context: Context): File =
        File(context.filesDir, "history").apply { mkdirs() }

    fun open(context: Context, settings: Settings, kind: String): String {
        val id = SimpleDateFormat("yyyy-MM-dd'T'HH-mm-ss-SSS", Locale.US).format(Date())
        val meta = JSONObject()
            .put("v", 1)
            .put("at", System.currentTimeMillis())
            .put("source", settings.sourceLang)
            .put("target", settings.targetLang)
            .put("model", settings.model())
            .put("kind", kind)
        runCatching { File(dir(context), "$id.jsonl").writeText(meta.toString() + "\n") }
        return id
    }

    /** Fire and forget: a disk that will not take the line must not stall the subtitle. */
    fun append(context: Context, id: String, at: Long, source: String, target: String) {
        if (id.isEmpty() || (source.isEmpty() && target.isEmpty())) return
        val line = JSONObject().put("at", at).put("source", source).put("target", target)
        runCatching { File(dir(context), "$id.jsonl").appendText(line.toString() + "\n") }
    }

    fun list(context: Context): List<SessionMeta> {
        val files = dir(context).listFiles { f -> f.extension == "jsonl" } ?: return emptyList()
        return files.mapNotNull { f ->
            runCatching {
                val lines = f.readLines().filter { it.isNotBlank() }
                if (lines.isEmpty()) return@runCatching null
                val meta = JSONObject(lines.first())
                val entries = lines.drop(1).mapNotNull { runCatching { JSONObject(it) }.getOrNull() }
                SessionMeta(
                    id = f.nameWithoutExtension,
                    at = meta.optLong("at"),
                    source = meta.optString("source"),
                    target = meta.optString("target"),
                    model = meta.optString("model"),
                    kind = meta.optString("kind"),
                    count = entries.size,
                    preview = entries.lastOrNull()?.optString("target") ?: "",
                )
            }.getOrNull()
        }.sortedByDescending { it.at }
    }

    fun read(context: Context, id: String): List<Entry> {
        val f = File(dir(context), "$id.jsonl")
        if (!f.exists()) return emptyList()
        return f.readLines().drop(1).filter { it.isNotBlank() }.mapNotNull { line ->
            runCatching {
                val o = JSONObject(line)
                Entry(o.optLong("at"), o.optString("source"), o.optString("target"))
            }.getOrNull()
        }
    }

    fun delete(context: Context, id: String) {
        runCatching { File(dir(context), "$id.jsonl").delete() }
    }

    fun search(context: Context, query: String): List<SessionMeta> {
        if (query.isBlank()) return list(context)
        val q = query.trim().lowercase()
        return list(context).filter { s ->
            s.preview.lowercase().contains(q) || read(context, s.id).any {
                it.source.lowercase().contains(q) || it.target.lowercase().contains(q)
            }
        }
    }
}

/** TXT / SRT export, timings derived from arrival time. */
object Export {
    fun txt(entries: List<HistoryStore.Entry>): String = entries.joinToString("\n\n") { e ->
        if (e.source.isNotEmpty()) "${e.source}\n${e.target}" else e.target
    }

    fun srt(entries: List<HistoryStore.Entry>): String {
        if (entries.isEmpty()) return ""
        val t0 = entries.first().at
        return entries.mapIndexed { i, e ->
            val start = e.at - t0
            val end = (entries.getOrNull(i + 1)?.at ?: (e.at + 3000)) - t0
            val body = if (e.source.isNotEmpty()) "${e.source}\n${e.target}" else e.target
            "${i + 1}\n${srtTime(start)} --> ${srtTime(end)}\n$body\n"
        }.joinToString("\n")
    }

    private fun srtTime(ms: Long): String {
        fun pad(n: Long, w: Int = 2) = n.toString().padStart(w, '0')
        val h = ms / 3_600_000
        val m = (ms / 60_000) % 60
        val s = (ms / 1000) % 60
        return "${pad(h)}:${pad(m)}:${pad(s)},${pad(ms % 1000, 3)}"
    }
}
