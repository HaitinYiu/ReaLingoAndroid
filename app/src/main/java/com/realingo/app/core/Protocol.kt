package com.realingo.app.core

import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject

/**
 * The wire protocol, one branch per message format.
 * Ported 1:1 from ReaLingo's src-tauri/src/config.rs — field names here were probed against
 * the live endpoints, so they are not guesswork; do not "tidy" them.
 */

private const val GEMINI_URL =
    "wss://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent"

/** Adds `name=value` unless that exact parameter is already present, keeping any fragment. */
fun withParam(url: String, name: String, value: String): String {
    val hash = url.indexOf('#')
    val base = if (hash >= 0) url.substring(0, hash) else url
    val fragment = if (hash >= 0) url.substring(hash) else ""
    val q = base.indexOf('?')
    if (q >= 0) {
        val query = base.substring(q + 1)
        val present = query.split('&').any { it.substringBefore('=') == name }
        if (present) return url
    }
    val sep = when {
        q < 0 -> "?"
        base.substring(q + 1).isEmpty() -> ""
        base.endsWith("&") -> ""
        else -> "&"
    }
    return "$base$sep$name=$value$fragment"
}

/** OpenAI rejects anything but 24 kHz; the rest take 16 kHz. */
fun Settings.sampleRate(): Int = if (protocol == Protocol.OPENAI) 24_000 else 16_000

fun Settings.targetCode(): String = when {
    protocol == Protocol.GEMINI && targetLang == "zh" -> "zh-Hans"
    protocol == Protocol.GEMINI && targetLang == "pt" -> "pt-BR"
    else -> targetLang
}

fun Settings.wsUrl(): String {
    val model = model()
    val custom = url.trim()
    if (custom.isNotEmpty()) {
        return if (protocol == Protocol.GEMINI) custom else withParam(custom, "model", model)
    }
    when (protocol) {
        Protocol.OPENAI -> return "wss://api.openai.com/v1/realtime/translations?model=$model"
        // No key in this string: it is displayed in Settings.
        Protocol.GEMINI -> return GEMINI_URL
        else -> Unit
    }
    val ws = workspaceId.trim()
    val host = if (ws.isEmpty()) region.sharedHost else "$ws.${region.id}.maas.aliyuncs.com"
    return "wss://$host/api-ws/v1/realtime?model=$model"
}

/** The handshake; Gemini takes its key as a query parameter, the others as a header. */
fun buildRequest(settings: Settings): Request {
    val key = settings.key().trim()
    val builder = Request.Builder()
    if (settings.protocol == Protocol.GEMINI) {
        builder.url(withParam(settings.wsUrl(), "key", key))
    } else {
        builder.url(settings.wsUrl())
        builder.header("Authorization", "Bearer $key")
    }
    return builder.build()
}

fun Settings.sessionUpdate(speak: Boolean): JSONObject {
    if (protocol == Protocol.GEMINI) {
        return JSONObject().put(
            "setup",
            JSONObject()
                .put("model", "models/" + model().removePrefix("models/"))
                .put(
                    "generationConfig",
                    JSONObject()
                        .put("responseModalities", JSONArray().put("AUDIO"))
                        .put(
                            "translationConfig",
                            JSONObject()
                                .put("targetLanguageCode", targetCode())
                                .put("echoTargetLanguage", false)
                        )
                )
                // Field layout probed against the live endpoint: the transcription switches
                // belong on `setup`, `translationConfig` inside `generationConfig`.
                .put("inputAudioTranscription", JSONObject())
                .put("outputAudioTranscription", JSONObject())
        )
    }

    if (protocol == Protocol.OPENAI) {
        val out = JSONObject().put("language", targetLang)
        val input = JSONObject().put("transcription", JSONObject().put("model", OPENAI_ASR_MODEL))
        return JSONObject()
            .put("type", "session.update")
            .put(
                "session",
                JSONObject().put(
                    "audio",
                    JSONObject().put("input", input).put("output", out)
                )
            )
    }

    val translation = JSONObject().put("language", targetLang)
    if (hotwords.isNotEmpty()) {
        translation.put("corpus", JSONObject().put("phrases", JSONObject().apply {
            hotwords.forEach { (k, v) -> put(k, v) }
        }))
    }

    if (protocol == Protocol.QWEN38) {
        return JSONObject()
            .put("type", "session.update")
            .put(
                "session",
                JSONObject()
                    .put(
                        "output_modalities",
                        if (speak) JSONArray().put("text").put("audio") else JSONArray().put("text")
                    )
                    .put("translation", translation)
                    .put(
                        "audio",
                        JSONObject()
                            .put(
                                "input",
                                JSONObject().put(
                                    "turn_detection",
                                    JSONObject().put("type", "speaker_detection").put("threshold", 0.5)
                                )
                            )
                            // The public endpoint can inherit a voice that rejects text-only
                            // sessions, so a voice is always named.
                            .put("output", JSONObject().put("voice", voice.ifEmpty { DEFAULT_VOICE }))
                    )
            )
    }

    val transcription = JSONObject().put("model", ASR_MODEL)
    // Omitting `language` is what tells the model to auto-detect.
    if (sourceLang != "auto" && sourceLang.isNotEmpty()) transcription.put("language", sourceLang)

    val session = JSONObject()
        .put("modalities", if (speak) JSONArray().put("text").put("audio") else JSONArray().put("text"))
        .put("input_audio_format", "pcm")
        .put("translation", translation)
        .put("input_audio_transcription", transcription)
        // An empty object leaves VAD unconfigured: the server then never closes a turn.
        .put(
            "turn_detection",
            JSONObject()
                .put("type", "server_vad")
                .put("threshold", 0.2)
                .put("silence_duration_ms", 800)
        )
    if (speak) {
        session.put("voice", voice)
        session.put("output_audio_format", "pcm")
    }
    return JSONObject().put("type", "session.update").put("session", session)
}

fun Settings.audioFrame(b64: String): JSONObject = when (protocol) {
    Protocol.QWEN35, Protocol.QWEN38 ->
        JSONObject().put("type", "input_audio_buffer.append").put("audio", b64)

    Protocol.OPENAI ->
        JSONObject().put("type", "session.input_audio_buffer.append").put("audio", b64)

    Protocol.GEMINI -> JSONObject().put(
        "realtimeInput",
        JSONObject().put(
            "audio",
            JSONObject().put("data", b64).put("mimeType", "audio/pcm;rate=${sampleRate()}")
        )
    )
}

fun Settings.finishFrame(): JSONObject = when (protocol) {
    Protocol.QWEN35, Protocol.QWEN38 -> JSONObject().put("type", "session.finish")
    Protocol.OPENAI -> JSONObject().put("type", "session.close")
    Protocol.GEMINI -> JSONObject().put("realtimeInput", JSONObject().put("audioStreamEnd", true))
}

fun Settings.isFinished(v: JSONObject): Boolean = when (protocol) {
    Protocol.QWEN35, Protocol.QWEN38 -> v.optString("type") == "session.finished"
    Protocol.OPENAI -> v.optString("type") == "session.closed"
    Protocol.GEMINI -> false
}

/** Gemini never announces the end: it keeps pushing silent frames after `audioStreamEnd`. */
fun Settings.signalsFinish(): Boolean = protocol != Protocol.GEMINI

/** Gemini holds the final clause back until it hears a pause; ~3 s of silence releases it. */
fun Settings.flushSilenceSamples(): Int = if (protocol == Protocol.GEMINI) sampleRate() * 3 else 0

fun Settings.waitsForSetup(): Boolean = protocol == Protocol.GEMINI
