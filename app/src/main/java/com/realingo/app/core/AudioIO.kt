package com.realingo.app.core

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaRecorder
import android.media.projection.MediaProjection
import android.net.Uri
import android.os.Build
import androidx.core.content.ContextCompat
import java.util.concurrent.LinkedBlockingQueue
import kotlin.math.abs

/** Every source hands interleaved float samples to the same chunker. */
interface AudioSource {
    fun start()
    fun stop()
}

private const val CAPTURE_RATE = 48_000

/**
 * Microphone capture: 48 kHz mono PCM16 where the device allows it, 44.1 kHz otherwise,
 * downmixed and resampled downstream.
 */
class MicCapture(
    private val context: Context,
    private val onPcm: (FloatArray, Int, Int) -> Unit,
) : AudioSource {
    private var record: AudioRecord? = null
    private var thread: Thread? = null
    @Volatile private var running = false

    @SuppressLint("MissingPermission")
    override fun start() {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            throw IllegalStateException("RECORD_AUDIO permission not granted")
        }
        // Not every device can open 48 kHz for capture; 44.1 kHz is the usual fallback and
        // the resampler handles the fractional ratio.
        val rate = if (AudioRecord.getMinBufferSize(
                CAPTURE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
            ) > 0
        ) CAPTURE_RATE else 44_100
        val minBuf = AudioRecord.getMinBufferSize(
            rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        if (minBuf <= 0) throw IllegalStateException("this device offers no usable capture rate")
        val bufSize = maxOf(minBuf * 2, rate / 5 * 2)

        // UNPROCESSED avoids the platform's own AGC/NS eating speech detail; not every
        // device offers it, so fall back through the usual suspects.
        val sources = intArrayOf(
            MediaRecorder.AudioSource.UNPROCESSED,
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            MediaRecorder.AudioSource.MIC,
        )
        var rec: AudioRecord? = null
        for (src in sources) {
            try {
                val r = AudioRecord(src, rate, AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT, bufSize)
                if (r.state == AudioRecord.STATE_INITIALIZED) {
                    rec = r
                    break
                }
                r.release()
            } catch (_: Exception) {
                // try the next source
            }
        }
        val r = rec ?: throw IllegalStateException("no usable microphone")
        record = r
        r.startRecording()
        running = true
        thread = Thread({
            val buf = ShortArray(1024)
            while (running) {
                val n = r.read(buf, 0, buf.size)
                if (n <= 0) {
                    if (n == AudioRecord.ERROR_INVALID_OPERATION || n == AudioRecord.ERROR_DEAD_OBJECT) break
                    continue
                }
                onPcm(FloatArray(n) { buf[it] / 32768f }, 1, rate)
            }
        }, "mic-capture").apply { isDaemon = true; start() }
    }

    override fun stop() {
        running = false
        thread?.interrupt()
        thread = null
        record?.let {
            runCatching { it.stop() }
            it.release()
        }
        record = null
    }
}

/**
 * System audio: Android's answer to loopback is AudioPlaybackCapture, which records what
 * other apps play as long as they allow capture (media apps usually do) and excludes this
 * app's own playback — so the spoken translation is never recorded back in.
 */
class PlaybackCapture(
    private val context: Context,
    private val projection: MediaProjection,
    private val onPcm: (FloatArray, Int, Int) -> Unit,
) : AudioSource {
    private var record: AudioRecord? = null
    private var thread: Thread? = null
    @Volatile private var running = false

    @SuppressLint("MissingPermission")
    override fun start() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            throw IllegalStateException("system audio capture needs Android 10+")
        }
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            throw IllegalStateException("RECORD_AUDIO permission not granted")
        }
        val config = AudioPlaybackCaptureConfiguration.Builder(projection)
            .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
            .addMatchingUsage(AudioAttributes.USAGE_GAME)
            .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
            .build()
        val format = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(CAPTURE_RATE)
            .setChannelMask(AudioFormat.CHANNEL_IN_STEREO)
            .build()
        val r = AudioRecord.Builder()
            .setAudioPlaybackCaptureConfig(config)
            .setAudioFormat(format)
            .setBufferSizeInBytes(CAPTURE_RATE * 2 * 2)
            .build()
        if (r.state != AudioRecord.STATE_INITIALIZED) {
            r.release()
            throw IllegalStateException("could not open system audio capture")
        }
        record = r
        r.startRecording()
        running = true
        thread = Thread({
            val buf = ShortArray(2048)
            while (running) {
                val n = r.read(buf, 0, buf.size)
                if (n <= 0) {
                    if (n == AudioRecord.ERROR_INVALID_OPERATION || n == AudioRecord.ERROR_DEAD_OBJECT) break
                    continue
                }
                onPcm(FloatArray(n) { buf[it] / 32768f }, 2, CAPTURE_RATE)
            }
        }, "playback-capture").apply { isDaemon = true; start() }
    }

    override fun stop() {
        running = false
        thread?.interrupt()
        thread = null
        record?.let {
            runCatching { it.stop() }
            it.release()
        }
        record = null
    }
}

/**
 * Local audio file: MediaExtractor + MediaCodec to PCM, then paced at real time so the
 * server sees the same rhythm it would from a live speaker.
 */
class FileCapture(
    private val context: Context,
    private val uri: Uri,
    private val onPcm: (FloatArray, Int, Int) -> Unit,
    private val onProgress: (Float) -> Unit = {},
    private val onDone: () -> Unit,
    private val onError: (String) -> Unit,
) : AudioSource {
    private var thread: Thread? = null
    @Volatile private var running = false
    private var codec: MediaCodec? = null
    private var extractor: MediaExtractor? = null

    override fun start() {
        running = true
        thread = Thread({ decode() }, "file-capture").apply { isDaemon = true; start() }
    }

    private fun decode() {
        var c: MediaCodec? = null
        var ex: MediaExtractor? = null
        try {
            ex = MediaExtractor().apply { setDataSource(context, uri, null) }
            var track = -1
            var fmt: MediaFormat? = null
            for (i in 0 until ex.trackCount) {
                val f = ex.getTrackFormat(i)
                if (f.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true) {
                    track = i
                    fmt = f
                    break
                }
            }
            if (track < 0) throw IllegalStateException("no audio track in this file")
            val format = fmt ?: throw IllegalStateException("no audio track in this file")
            ex.selectTrack(track)
            val mime = format.getString(MediaFormat.KEY_MIME)!!
            var rate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT).coerceAtLeast(1)
            var encoding = if (format.containsKey(MediaFormat.KEY_PCM_ENCODING))
                format.getInteger(MediaFormat.KEY_PCM_ENCODING) else AudioFormat.ENCODING_PCM_16BIT

            c = MediaCodec.createDecoderByType(mime)
            c.configure(format, null, null, 0)
            c.start()
            codec = c
            extractor = ex

            val info = MediaCodec.BufferInfo()
            var inputDone = false
            var outputDone = false
            var framesOut = 0L
            val started = System.nanoTime()
            val durationUs = if (format.containsKey(MediaFormat.KEY_DURATION))
                format.getLong(MediaFormat.KEY_DURATION) else 0L

            while (running && !outputDone) {
                if (!inputDone) {
                    val inIdx = c.dequeueInputBuffer(10_000)
                    if (inIdx >= 0) {
                        val ib = c.getInputBuffer(inIdx)!!
                        val size = ex.readSampleData(ib, 0)
                        if (size < 0) {
                            c.queueInputBuffer(inIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            c.queueInputBuffer(inIdx, 0, size, ex.sampleTime, 0)
                            ex.advance()
                        }
                    }
                }
                val outIdx = c.dequeueOutputBuffer(info, 10_000)
                when {
                    outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val of = c.outputFormat
                        rate = of.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                        channels = of.getInteger(MediaFormat.KEY_CHANNEL_COUNT).coerceAtLeast(1)
                        if (of.containsKey(MediaFormat.KEY_PCM_ENCODING)) {
                            encoding = of.getInteger(MediaFormat.KEY_PCM_ENCODING)
                        }
                    }
                    outIdx >= 0 -> {
                        val ob = c.getOutputBuffer(outIdx)
                        if (ob != null && info.size > 0) {
                            ob.position(info.offset)
                            ob.limit(info.offset + info.size)
                            val floats = if (encoding == AudioFormat.ENCODING_PCM_FLOAT) {
                                val fb = ob.asFloatBuffer()
                                FloatArray(fb.remaining()).also { fb.get(it) }
                            } else {
                                val sb = ob.asShortBuffer()
                                FloatArray(sb.remaining()) { sb.get(it) / 32768f }
                            }
                            if (floats.isNotEmpty()) {
                                onPcm(floats, channels, rate)
                                framesOut += floats.size / channels
                                if (durationUs > 0) {
                                    onProgress((framesOut * 1_000_000.0 / rate / durationUs).toFloat().coerceIn(0f, 1f))
                                }
                            }
                        }
                        c.releaseOutputBuffer(outIdx, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                        // Pace: a four-minute file must take four minutes to stream.
                        val targetMs = framesOut * 1000 / rate.coerceAtLeast(1)
                        val actualMs = (System.nanoTime() - started) / 1_000_000
                        val sleep = targetMs - actualMs
                        if (sleep in 1..250) Thread.sleep(sleep)
                    }
                }
            }
        } catch (e: Exception) {
            if (running) onError(e.message ?: "could not decode this file")
        } finally {
            runCatching { c?.stop() }
            c?.release()
            ex?.release()
            codec = null
            extractor = null
            if (running) {
                running = false
                onDone()
            }
        }
    }

    override fun stop() {
        running = false
        thread?.interrupt()
        thread = null
    }
}

/**
 * Plays the spoken translation: the model sends mono PCM16 at 24 kHz, the device gets
 * 48 kHz, so the queue is resampled before it is written.
 */
class AudioPlayer {
    private val outRate = 48_000
    private val queue = LinkedBlockingQueue<ShortArray>()
    private var track: AudioTrack? = null
    private var thread: Thread? = null
    private val resampler = Resampler(24_000, outRate)
    private val converted = ShortQueue(16_384)
    @Volatile private var running = false
    private var scratch = FloatArray(0)

    fun start() {
        val minBuf = AudioTrack.getMinBufferSize(
            outRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        val t = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(outRate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
            )
            .setBufferSizeInBytes(maxOf(minBuf * 2, outRate / 2 * 2))
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        track = t
        t.play()
        running = true
        thread = Thread({
            while (running) {
                val chunk = queue.poll()
                if (chunk == null) {
                    Thread.sleep(10)
                    continue
                }
                var offset = 0
                while (offset < chunk.size && running) {
                    val n = t.write(chunk, offset, chunk.size - offset)
                    if (n <= 0) break
                    offset += n
                }
            }
        }, "audio-player").apply { isDaemon = true; start() }
    }

    /** One `response.audio.delta` payload: little-endian PCM16 at 24 kHz. */
    fun push(bytes: ByteArray) {
        if (bytes.size < 2) return
        val n = bytes.size / 2
        if (scratch.size < n) scratch = FloatArray(n)
        for (i in 0 until n) {
            val lo = bytes[i * 2].toInt() and 0xFF
            val hi = bytes[i * 2 + 1].toInt()
            val v = ((hi shl 8) or lo).toShort()
            scratch[i] = v / 32768f
        }
        converted.clear()
        resampler.push(scratch.copyOf(n), converted)
        if (converted.size > 0) queue.offer(converted.data.copyOfRange(0, converted.size))
    }

    fun release() {
        running = false
        thread?.interrupt()
        thread = null
        // Let the device drain what is already queued, so the last clause is not cut off.
        if (queue.isNotEmpty()) Thread.sleep(200)
        track?.let {
            runCatching { it.stop() }
            it.release()
        }
        track = null
        queue.clear()
    }
}

fun samplePeak(samples: FloatArray): Float {
    var p = 0f
    for (s in samples) {
        val a = abs(s)
        if (a > p) p = a
    }
    return p
}
