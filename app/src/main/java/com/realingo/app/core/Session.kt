package com.realingo.app.core

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.coroutineScope
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * One live translation session: connect, push 100 ms PCM chunks, read text + audio back.
 * Ported from ReaLingo's src-tauri/src/realtime.rs `connect_and_pump`.
 */
class Session(
    private val client: OkHttpClient,
    private val settings: Settings,
    private val speak: Boolean,
    private val onEvent: (RtEvent) -> Unit,
    private val onConnected: () -> Unit,
    private val onError: (String) -> Unit,
    private val onAudio: (ByteArray) -> Unit,
    private val onRaw: (String) -> Unit,
) {
    private sealed class Frame {
        class Text(val json: String) : Frame()
        class Closed(val reason: String?) : Frame()
        class Failed(val error: Throwable) : Frame()
    }

    /** How long a server that never announces the end (Gemini) must stay quiet. */
    private val quietEndMs = 4_000L

    suspend fun run(audio: ReceiveChannel<ShortArray>, stop: AtomicBoolean, onReady: () -> Unit) {
        if (settings.key().isBlank()) {
            onError("API key is empty")
            return
        }

        val frames = Channel<Frame>(Channel.UNLIMITED)
        val opened = CompletableDeferred<Boolean>()
        val ready = CompletableDeferred<Unit>()

        val ws = client.newWebSocket(buildRequest(settings), object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                opened.complete(true)
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                frames.trySend(Frame.Text(text))
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                // Gemini sends its JSON in binary frames.
                frames.trySend(Frame.Text(bytes.utf8()))
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                // Gemini reports a bad key or setup by closing with a reason, not a frame.
                if (code != 1000 && reason.isNotEmpty()) frames.trySend(Frame.Closed(reason))
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                frames.trySend(Frame.Closed(null))
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                opened.complete(false)
                frames.trySend(Frame.Failed(t))
            }
        })

        try {
            val ok = withTimeoutOrNull(15_000) { opened.await() }
            if (ok != true) {
                onError(if (ok == null) "connection timed out after 15s" else "connect failed")
                ws.cancel()
                return
            }

            ws.send(settings.sessionUpdate(speak).toString())
            onConnected()
            // Capture may start now: the uplink still waits for `ready`, so nothing is sent
            // before the server has taken the session setup.
            onReady()
            // Gemini drops audio sent before `setupComplete`; the others take it at once.
            if (!settings.waitsForSetup()) ready.complete(Unit)

            coroutineScope {
                val uplink = launch(Dispatchers.IO) {
                    ready.await()
                    while (true) {
                        val chunk = audio.receiveCatching().getOrNull() ?: break
                        if (stop.get()) break
                        if (!ws.send(settings.audioFrame(pcmToBase64(chunk)).toString())) return@launch
                    }
                    // Input is over: tell the reader it can switch to the short wait.
                    stop.set(true)
                    // Gemini withholds the last clause until it hears a pause.
                    val chunkSamples = settings.sampleRate() / 10
                    if (settings.flushSilenceSamples() > 0) {
                        val silence = settings.audioFrame(
                            encodeB64(ByteArray(chunkSamples * 2))
                        ).toString()
                        var sent = 0
                        while (sent < settings.flushSilenceSamples()) {
                            if (!ws.send(silence)) return@launch
                            sent += chunkSamples
                            kotlinx.coroutines.delay(80)
                        }
                    }
                    // Not closed here: a close frame makes the server hang up before the
                    // last sentence is out. The reader closes once everything has arrived.
                    ws.send(settings.finishFrame().toString())
                }

                val stream = TextStream()
                var heard = System.currentTimeMillis()

                downlink@ while (true) {
                    if (stop.get() && !settings.signalsFinish() &&
                        System.currentTimeMillis() - heard > quietEndMs
                    ) break
                    val idle = if (stop.get()) 15_000L else 90_000L
                    val frame = withTimeoutOrNull(idle) { frames.receive() } ?: break
                    when (frame) {
                        is Frame.Failed -> {
                            onError("socket error: ${frame.error.message ?: frame.error.javaClass.simpleName}")
                            break@downlink
                        }

                        is Frame.Closed -> {
                            if (frame.reason != null) {
                                onError("closed by server: ${frame.reason}")
                                break@downlink
                            }
                            break@downlink
                        }

                        is Frame.Text -> {
                            val v = runCatching { JSONObject(frame.json) }.getOrNull() ?: continue
                            if (v.has("setupComplete")) ready.complete(Unit)
                            takeAudio(v)?.let { pcm ->
                                if (pcm.any { it.toInt() != 0 }) heard = System.currentTimeMillis()
                                onAudio(pcm)
                            }
                            onRaw(v.toString())
                            val rejected = rejection(v)
                            if (rejected != null) {
                                onError(rejected)
                                break@downlink
                            }
                            for (ev in stream.next(v)) {
                                if (ev.text.isNotEmpty()) heard = System.currentTimeMillis()
                                onEvent(ev)
                            }
                            if (settings.isFinished(v)) break@downlink
                        }
                    }
                }

                stop.set(true)
                // A setup that never completed would leave the uplink parked forever.
                ready.complete(Unit)
                withTimeoutOrNull(3_000) { uplink.join() } ?: uplink.cancel()
            }
        } finally {
            frames.close()
            stop.set(true)
            runCatching { ws.close(1000, null) }
        }
    }

    private fun pcmToBase64(chunk: ShortArray): String {
        val bytes = ByteArray(chunk.size * 2)
        for (i in chunk.indices) {
            val v = chunk[i].toInt()
            bytes[i * 2] = (v and 0xFF).toByte()
            bytes[i * 2 + 1] = ((v shr 8) and 0xFF).toByte()
        }
        return encodeB64(bytes)
    }

    companion object {
        fun newClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .pingInterval(20, TimeUnit.SECONDS)
            .build()
    }
}
