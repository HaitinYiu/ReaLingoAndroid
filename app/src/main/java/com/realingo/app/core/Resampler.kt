package com.realingo.app.core

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * Streaming sample-rate conversion to the mono i16 rate the model wants (16 kHz for Qwen,
 * 24 kHz for OpenAI). Direct windowed-sinc: for each output sample we evaluate a
 * Blackman-windowed sinc centred on the fractional input position, with the cutoff pinned to
 * the *lower* of the two Nyquist frequencies — real anti-aliasing for arbitrary ratios
 * (48000/16000 = 3, 44100/16000 = 2.75625), which plain decimation does not give.
 *
 * Ported from ReaLingo's src-tauri/src/resample.rs.
 */
private const val HALF = 16 // 32 taps; plenty for speech

/** Growable short buffer, so the resampler can stream without boxing every sample. */
class ShortQueue(initial: Int = 8192) {
    var data = ShortArray(initial)
        private set
    var size = 0
        private set

    fun add(v: Short) {
        if (size == data.size) data = data.copyOf(data.size * 2)
        data[size++] = v
    }

    fun clear() {
        size = 0
    }

    fun removePrefix(n: Int) {
        if (n <= 0) return
        if (n >= size) {
            size = 0
            return
        }
        System.arraycopy(data, n, data, 0, size - n)
        size -= n
    }
}

class Resampler(private val inRate: Int, private val outRate: Int) {
    private val step = inRate.toDouble() / outRate.toDouble()
    private var pos = HALF.toDouble()
    private val buf = ArrayList<Float>(4096)
    // 0.92 leaves a transition band so the stopband actually attenuates.
    private val cutoff = 0.5 * min(outRate.toDouble() / inRate.toDouble(), 1.0) * 0.92
    private val passthrough = inRate == outRate

    init {
        repeat(HALF) { buf.add(0f) } // left context
    }

    fun push(input: FloatArray, out: ShortQueue) {
        if (passthrough) {
            for (s in input) out.add(toI16(s))
            return
        }
        for (s in input) buf.add(s)

        while (pos.toInt() + HALF < buf.size) {
            val center = pos
            val first = center.toInt() - HALF + 1
            var acc = 0.0
            var wsum = 0.0
            for (k in 0 until 2 * HALF) {
                val idx = first + k
                if (idx < 0) continue
                val dx = center - idx
                val w = sinc(2.0 * cutoff * dx) * blackman(dx / HALF)
                acc += buf[idx] * w
                wsum += w
            }
            // Normalising by the window sum keeps DC gain at 1 for every phase.
            out.add(toI16((acc / maxOf(wsum, 1e-9)).toFloat()))
            pos += step
        }

        // Retire input we will never look at again, keeping HALF samples of left context.
        val keepFrom = maxOf(0, pos.toInt() - HALF)
        if (keepFrom > 0) {
            buf.subList(0, minOf(keepFrom, buf.size)).clear()
            pos -= keepFrom
        }
    }
}

private fun sinc(x: Double): Double {
    if (abs(x) < 1e-9) return 1.0
    val p = PI * x
    return sin(p) / p
}

/** Blackman window over t in [-1, 1]; zero outside. */
private fun blackman(t: Double): Double {
    if (abs(t) >= 1.0) return 0.0
    val p = PI * t
    return 0.42 + 0.5 * cos(p) + 0.08 * cos(2.0 * p)
}

private fun toI16(s: Float): Short = (s.coerceIn(-1f, 1f) * 32767f).toInt().toShort()

/** Downmix interleaved frames to mono, then resample, then emit fixed 100 ms chunks. */
class Chunker(private val srcRate: Int, targetRate: Int, private val onChunk: (ShortArray) -> Unit) {
    private val resampler = Resampler(srcRate, targetRate)
    private val chunkSamples = targetRate / 10
    private val out = ShortQueue(chunkSamples * 4)
    private var peak = 0f
    private var sinceLevel = 0

    fun push(interleaved: FloatArray, channels: Int) {
        if (channels <= 0 || interleaved.isEmpty()) return
        val mono: FloatArray = if (channels == 1) {
            interleaved
        } else {
            val frames = interleaved.size / channels
            FloatArray(frames) { f ->
                var sum = 0f
                for (c in 0 until channels) sum += interleaved[f * channels + c]
                sum / channels
            }
        }
        for (s in mono) {
            val a = abs(s)
            if (a > peak) peak = a
        }
        sinceLevel += mono.size
        resampler.push(mono, out)

        while (out.size >= chunkSamples) {
            val chunk = out.data.copyOfRange(0, chunkSamples)
            out.removePrefix(chunkSamples)
            onChunk(chunk)
        }
        // Report a smoothed level roughly ten times a second.
        if (sinceLevel >= srcRate / 10) {
            levelSink?.invoke(min(1f, peak * 2.2f))
            peak = 0f
            sinceLevel = 0
        }
    }

    /** Set by the engine so the UI can draw a meter. */
    var levelSink: ((Float) -> Unit)? = null
}
