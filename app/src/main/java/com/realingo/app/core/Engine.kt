package com.realingo.app.core

import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.net.Uri
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

/** Where the audio comes from. */
sealed class SourceSpec(val kind: String) {
    class Mic : SourceSpec("mic")
    class Playback(val resultCode: Int, val data: Intent) : SourceSpec("system")
    class File(val uri: Uri) : SourceSpec("file")
}

/**
 * The session state machine plus the turn model, ported from ReaLingo's src/stream.ts.
 *
 * A turn is one utterance: its transcript and its translation arrive as two independent
 * streams and finish at different times — in practice the translation is done first and the
 * transcript lands seconds later, while the *next* turn is already streaming. They are keyed
 * by the assistant item id the server puts on every translation frame; transcripts carry the
 * input item's id instead, which a `link` event maps onto that key.
 */
object Engine {
    enum class Status { IDLE, CONNECTING, CONNECTED, STOPPING, CLOSED, ERROR }

    data class Line(val id: Long, val source: String, val target: String, val at: Long)
    data class Live(
        val source: String = "",
        val sourceStash: String = "",
        val target: String = "",
        val targetStash: String = "",
    )

    val status = MutableStateFlow(Status.IDLE)
    val lines = MutableStateFlow<List<Line>>(emptyList())
    val live = MutableStateFlow(Live())
    val detectedLang = MutableStateFlow("")
    val errorMsg = MutableStateFlow("")
    val warnMsg = MutableStateFlow("")
    val speaking = MutableStateFlow(false)
    val rawLog = MutableStateFlow<List<String>>(emptyList())
    val level = MutableStateFlow(0f)
    val fileProgress = MutableStateFlow<Float?>(null)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val client = Session.newClient()
    private val stopFlag = AtomicBoolean(false)
    private val lock = Any()

    private var job: Job? = null
    private var capture: AudioSource? = null
    private var chunks: Channel<ShortArray>? = null
    private var player: AudioPlayer? = null
    private var sessionId = ""
    private var nextLineId = 1L

    private class Turn(val key: String, val at: Long) {
        var input = ""
        var source = ""
        var sourceStash = ""
        var target = ""
        var targetStash = ""
        var sourceDone = false
        var targetDone = false
        var lineId: Long? = null
    }

    private val turns = LinkedHashMap<String, Turn>()
    private val byInput = HashMap<String, String>()

    val isRunning: Boolean
        get() = status.value == Status.CONNECTING ||
            status.value == Status.CONNECTED ||
            status.value == Status.STOPPING

    fun start(context: Context, spec: SourceSpec, speak: Boolean) {
        if (isRunning) return
        val app = context.applicationContext
        ctx = app
        val settings = SettingsStore.value
        val effectiveSpeak = speak && canSpeak(settings.protocol, settings.targetLang)

        errorMsg.value = ""
        warnMsg.value = ""
        lines.value = emptyList()
        detectedLang.value = ""
        rawLog.value = emptyList()
        fileProgress.value = null
        nextLineId = 1
        synchronized(lock) {
            turns.clear()
            byInput.clear()
        }
        live.value = Live()
        stopFlag.set(false)
        status.value = Status.CONNECTING
        sessionId = if (settings.history) HistoryStore.open(app, settings, spec.kind) else ""

        job = scope.launch {
            val channel = Channel<ShortArray>(64)
            chunks = channel
            try {
                if (effectiveSpeak) player = AudioPlayer().also { it.start() }
                val session = Session(
                    client = client,
                    settings = settings,
                    speak = effectiveSpeak,
                    onEvent = ::handleEvent,
                    onConnected = { status.value = Status.CONNECTED },
                    onError = { msg ->
                        errorMsg.value = msg
                        status.value = Status.ERROR
                    },
                    onAudio = { pcm -> player?.push(pcm) },
                    onRaw = { json -> rawLog.value = (rawLog.value + json).takeLast(300) },
                )
                session.run(channel, stopFlag) {
                    startCapture(app, spec, settings.sampleRate(), channel)
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                errorMsg.value = e.message ?: e.toString()
                status.value = Status.ERROR
            } finally {
                capture?.stop()
                capture = null
                channel.close()
                chunks = null
                player?.release()
                player = null
                flushTurns()
                speaking.value = false
                level.value = 0f
                fileProgress.value = null
                if (status.value != Status.ERROR) status.value = Status.CLOSED
            }
        }
    }

    fun stop() {
        if (status.value == Status.CONNECTING || status.value == Status.CONNECTED) {
            status.value = Status.STOPPING
        }
        stopFlag.set(true)
        capture?.stop()
        capture = null
        chunks?.close()
    }

    fun shutdown() {
        stop()
        job?.cancel()
        job = null
        status.value = Status.IDLE
    }

    private fun startCapture(context: Context, spec: SourceSpec, targetRate: Int, channel: Channel<ShortArray>) {
        var chunker: Chunker? = null
        fun feed(samples: FloatArray, channels: Int, rate: Int) {
            val c = chunker ?: Chunker(rate, targetRate) { chunk -> channel.trySend(chunk) }
                .also {
                    it.levelSink = { v -> level.value = v }
                    chunker = it
                }
            c.push(samples, channels)
        }

        capture = when (spec) {
            is SourceSpec.Mic -> MicCapture(context) { s, ch, rate -> feed(s, ch, rate) }

            is SourceSpec.Playback -> {
                val mgr = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                val projection = mgr.getMediaProjection(spec.resultCode, spec.data)
                    ?: throw IllegalStateException("screen-capture permission was not granted")
                // Android 14 wants a callback registered before the projection is used, and
                // the user can revoke the share from the status bar at any time.
                projection.registerCallback(
                    object : MediaProjection.Callback() {
                        override fun onStop() {
                            Engine.stop()
                        }
                    },
                    android.os.Handler(android.os.Looper.getMainLooper())
                )
                PlaybackCapture(context, projection) { s, ch, rate -> feed(s, ch, rate) }
            }

            is SourceSpec.File -> FileCapture(
                context = context,
                uri = spec.uri,
                onPcm = { s, ch, rate -> feed(s, ch, rate) },
                onProgress = { p -> fileProgress.value = p },
                // EOF ends the input, exactly like a dropped microphone: the server then
                // flushes its last sentence and closes the session itself.
                onDone = { channel.close() },
                onError = { msg ->
                    errorMsg.value = msg
                    status.value = Status.ERROR
                },
            )
        }?.also { it.start() }
    }

    // ---------------------------------------------------------------- turn model

    private fun turnFor(key: String): Turn {
        turns[key]?.let { return it }
        val t = Turn(key, System.currentTimeMillis())
        turns[key] = t
        // A turn whose transcript never arrives would sit here forever; two or three are
        // open at once in normal speech.
        if (turns.size > 8) turns.values.firstOrNull()?.let { close(it) }
        return t
    }

    private fun publish(t: Turn) {
        if (t.source.isEmpty() && t.target.isEmpty()) return
        val id = t.lineId
        if (id == null) {
            val lid = nextLineId++
            t.lineId = lid
            lines.value = (lines.value + Line(lid, t.source, t.target, t.at)).takeLast(500)
            return
        }
        lines.value = lines.value.map {
            if (it.id == id) it.copy(source = t.source, target = t.target) else it
        }
    }

    private fun settle(t: Turn) {
        if (t.sourceDone && t.targetDone) close(t)
    }

    private fun close(t: Turn) {
        publish(t)
        if (t.source.isNotEmpty() || t.target.isNotEmpty()) {
            ctx?.let { HistoryStore.append(it, sessionId, t.at, t.source, t.target) }
        }
        turns.remove(t.key)
        if (t.input.isNotEmpty()) byInput.remove(t.input)
        syncLive()
    }

    private var ctx: Context? = null

    private fun flushTurns() {
        synchronized(lock) {
            turns.values.toList().forEach { close(it) }
        }
    }

    private fun syncLive() {
        val t = turns.values.lastOrNull()
        live.value = Live(
            source = t?.source ?: "",
            sourceStash = t?.sourceStash ?: "",
            target = t?.target ?: "",
            targetStash = t?.targetStash ?: "",
        )
    }

    private fun handleEvent(ev: RtEvent) {
        synchronized(lock) {
            when (ev.kind) {
                "status" -> when (ev.text) {
                    "connecting" -> status.value = Status.CONNECTING
                    "connected" -> status.value = Status.CONNECTED
                    "closed" -> {
                        flushTurns()
                        speaking.value = false
                        if (status.value != Status.ERROR) status.value = Status.CLOSED
                        Unit
                    }
                    else -> Unit
                }

                // Arrives before either stream for the turn, so the transcript always has a home.
                "link" -> {
                    byInput[ev.text] = ev.id
                    turnFor(ev.id).input = ev.text
                }

                "source" -> {
                    // Falling back to the newest turn keeps a transcript that outran its
                    // link visible rather than silently dropped.
                    val key = byInput[ev.id] ?: turns.values.lastOrNull()?.key ?: return
                    val t = turnFor(key)
                    t.source = ev.text
                    t.sourceStash = if (ev.done) "" else ev.stash
                    if (ev.lang.isNotEmpty()) detectedLang.value = ev.lang
                    speaking.value = !ev.done
                    if (ev.done) t.sourceDone = true
                    // Only ever patches here: a turn still waiting for its translation
                    // belongs in the live row, not as a second, half-empty row.
                    if (t.lineId != null) publish(t)
                    syncLive()
                    settle(t)
                }

                "target" -> {
                    val t = turnFor(ev.id)
                    t.target = ev.text
                    t.targetStash = if (ev.done) "" else ev.stash
                    speaking.value = !ev.done
                    if (ev.done) {
                        t.targetDone = true
                        // Into the stream now, even though the transcript may still be
                        // seconds away — waiting would leave the row blank.
                        publish(t)
                    }
                    syncLive()
                    settle(t)
                }

                "speech" -> speaking.value = ev.text == "start"

                "warn" -> {
                    warnMsg.value = ev.text
                    scope.launch {
                        delay(6000)
                        if (warnMsg.value == ev.text) warnMsg.value = ""
                    }
                }

                "error" -> {
                    errorMsg.value = ev.text
                    status.value = Status.ERROR
                }

                else -> Unit
            }
        }
    }
}
