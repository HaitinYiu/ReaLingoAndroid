package com.realingo.app.core

import android.util.Base64
import org.json.JSONObject
import java.io.ByteArrayOutputStream

/**
 * Turns raw server frames into one normalised event stream.
 * Ported 1:1 from ReaLingo's src-tauri/src/realtime.rs (TextStream / classify).
 */

data class RtEvent(
    val kind: String,
    val text: String = "",
    /** Unconfirmed tail: worth showing, but greyed out — it can still change. */
    val stash: String = "",
    val lang: String = "",
    val id: String = "",
    val done: Boolean = false,
)

/** Past this many characters a turn with no sentence end is cut anyway. */
private const val MAX_TURN_CHARS = 150

private fun Event(kind: String, text: String = "", stash: String = "", lang: String = "", id: String = "", done: Boolean = false) =
    RtEvent(kind, text, stash, lang, id, done)

class TextStream {
    /** key = kind + '\u0000' + item id -> accumulated delta text. */
    private val partial = HashMap<String, String>()
    private var turn = 0
    private var open = false
    private var source = ""
    private var target = ""

    fun next(v: JSONObject): List<RtEvent> {
        v.optJSONObject("serverContent")?.let { content ->
            // Gemini: the same two append-only streams as OpenAI, untyped, and one frame may
            // carry either or both alongside the audio.
            val out = ArrayList<RtEvent>(2)
            content.optJSONObject("inputTranscription")?.optString("text")?.let {
                if (it.isNotEmpty()) out.addAll(continuous(false, it))
            }
            content.optJSONObject("outputTranscription")?.optString("text")?.let {
                if (it.isNotEmpty()) out.addAll(continuous(true, it))
            }
            return out
        }
        return when (val ty = v.optString("type")) {
            "session.input_transcript.delta" ->
                continuous(false, v.optString("delta"))

            "session.output_transcript.delta" ->
                continuous(true, v.optString("delta"))

            else -> {
                if (ty == "conversation.item.input_audio_transcription.delta" ||
                    ty == "response.text.delta" ||
                    ty == "response.audio_transcript.delta"
                ) itemised(v) ?: emptyList() else classify(v) ?: emptyList()
            }
        }
    }

    /**
     * OpenAI and Gemini send two bare append-only streams: no turns, no ids, no "done".
     * Both streams share a turn id, a sentence end in the translation closes the turn, and
     * the transcript is cut at its own last sentence end with the rest carried over.
     */
    private fun continuous(isTarget: Boolean, delta: String): List<RtEvent> {
        val out = ArrayList<RtEvent>(3)
        if (isTarget) target += delta else source += delta
        if (isTarget) {
            val cut = lastSentenceEnd(target)
                ?: if (target.length > MAX_TURN_CHARS) target.length else null
            if (cut != null) {
                val targetRest = target.substring(cut)
                target = target.substring(0, cut)
                val sourceCut = lastSentenceEnd(source) ?: source.length
                val sourceRest = source.substring(sourceCut)
                source = source.substring(0, sourceCut)
                emitTurn(out, true)
                target = targetRest.trimStart()
                source = sourceRest.trimStart()
                turn++
                open = false
                if (target.isEmpty() && source.isEmpty()) return out
            }
        }
        emitTurn(out, false)
        return out
    }

    private fun emitTurn(out: MutableList<RtEvent>, done: Boolean) {
        val id = "oa$turn"
        // The UI files transcripts by link; announcing the turn first gives it a home even
        // when the transcript runs ahead of the translation, which it usually does.
        if (!open) {
            open = true
            out.add(Event("link", text = id, id = id))
        }
        // Deltas carry their own leading space (" Welcome"), which is noise at a row start.
        out.add(Event("target", text = target.trimStart(), id = id, done = done))
        out.add(Event("source", text = source.trimStart(), id = id, done = done))
    }

    private fun itemised(v: JSONObject): List<RtEvent>? {
        val kind = when (v.optString("type")) {
            "conversation.item.input_audio_transcription.delta" -> "source"
            "response.text.delta", "response.audio_transcript.delta" -> "target"
            else -> return classify(v)
        }
        val id = v.optString("item_id")
        if (id.isEmpty()) return null
        val key = kind + "\u0000" + id
        val text = (partial[key] ?: "") + v.optString("delta")
        partial[key] = text
        return listOf(Event(kind, text = text, id = id))
    }

    private fun classify(v: JSONObject): List<RtEvent>? {
        val ty = v.optString("type")
        if (ty.isEmpty()) return null
        fun field(k: String) = v.optString(k)

        val ev = when (ty) {
            // Pairs the assistant item that will carry the translation with the input item
            // whose transcript belongs to it. Input items are announced through the same
            // event, and *their* previous_item_id points at the preceding assistant item.
            "conversation.item.created" -> {
                val item = v.optJSONObject("item") ?: return null
                val isInput = item.optJSONArray("content")?.optJSONObject(0)
                    ?.optString("type") == "input_audio"
                val prev = v.optString("previous_item_id")
                if (isInput || prev.isEmpty()) null
                else Event("link", text = prev, id = item.optString("id"))
            }

            "conversation.item.input_audio_transcription.text" -> Event(
                "source", text = field("text"), stash = field("stash"),
                lang = field("language"), id = field("item_id")
            )

            "conversation.item.input_audio_transcription.completed" -> Event(
                "source", text = field("transcript"), lang = field("language"),
                id = field("item_id"), done = true
            )

            "response.text.text", "response.audio_transcript.text" -> Event(
                "target", text = field("text"), stash = field("stash"), id = field("item_id")
            )

            // The audio-modality event names its payload `transcript`, not `text`.
            "response.text.done" -> Event("target", text = field("text"), id = field("item_id"), done = true)
            "response.audio_transcript.done" -> Event("target", text = field("transcript"), id = field("item_id"), done = true)

            "input_audio_buffer.speech_started" -> Event("speech", text = "start")
            "input_audio_buffer.speech_stopped" -> Event("speech", text = "stop")
            "session.created", "session.updated" -> Event("status", text = "connected")
            "session.finished", "session.closed" -> Event("status", text = "closed")

            // A server error frame does not end the session — surfacing it as fatal would
            // wrongly stop the stream.
            "error" -> Event(
                "warn",
                text = v.optJSONObject("error")?.optString("message")
                    ?: v.optString("message").ifEmpty { "unknown error" }
            )

            else -> null
        }
        // A done target/source retires its accumulated deltas.
        if (ty == "response.text.done" || ty == "response.audio_transcript.done") {
            partial.remove("target\u0000" + ev?.id)
        }
        if (ty == "conversation.item.input_audio_transcription.completed") {
            partial.remove("source\u0000" + ev?.id)
        }
        return ev?.let { listOf(it) }
    }
}

/**
 * Byte offset just past the last sentence end. A CJK mark ends a sentence outright; a
 * "." "!" "?" only once whitespace follows it, so "3.5" stays whole and a final "." waits
 * for the next delta to say whether it was one.
 */
fun lastSentenceEnd(s: String): Int? {
    var last: Int? = null
    for (i in s.indices) {
        val c = s[i]
        val spaced = i + 1 < s.length && s[i + 1].isWhitespace()
        if (c == '。' || c == '！' || c == '？' || c == '…' ||
            ((c == '.' || c == '!' || c == '?') && spaced)
        ) {
            last = i + 1
        }
    }
    return last
}

/** Pulls the PCM out of an audio delta and leaves its byte count behind for the raw log. */
fun takeAudio(v: JSONObject): ByteArray? {
    val out = ByteArrayOutputStream()
    var found = false
    val ty = v.optString("type")
    if (ty == "response.audio.delta" || ty == "session.output_audio.delta") {
        val b = decodeB64(v.optString("delta")) ?: return null
        out.write(b)
        v.put("delta", "<${b.size} bytes>")
        found = true
    } else {
        val parts = v.optJSONObject("serverContent")
            ?.optJSONObject("modelTurn")
            ?.optJSONArray("parts") ?: return null
        for (i in 0 until parts.length()) {
            val inline = parts.optJSONObject(i)?.optJSONObject("inlineData") ?: continue
            val b = decodeB64(inline.optString("data")) ?: continue
            out.write(b)
            inline.put("data", "<${b.size} bytes>")
            found = true
        }
    }
    return if (found) out.toByteArray() else null
}

fun decodeB64(s: String): ByteArray? {
    if (s.isEmpty()) return null
    return runCatching { Base64.decode(s, Base64.DEFAULT) }.getOrNull()
}

fun encodeB64(b: ByteArray): String = Base64.encodeToString(b, Base64.NO_WRAP)

/** DashScope refuses a session with a bare {code, message} frame — no `type`. */
fun rejection(v: JSONObject): String? {
    if (v.has("type")) return null
    val code = v.optString("code")
    if (code.isEmpty()) return null
    return "$code: ${v.optString("message")}"
}
