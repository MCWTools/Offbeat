package com.auralis.player.playback

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import java.nio.ByteBuffer
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.tanh

/**
 * Applies a smoothly changing gain (can boost AND cut) to the PCM stream, with a soft limiter so boosting never hard-clips.
 * The gain target is set from outside; the real gain glides toward it (~0.35 s time constant), so level changes are never abrupt.
 */
@UnstableApi
class LevelProcessor : BaseAudioProcessor() {
    @Volatile var targetGain = 1f
    /** 0..1 level of BANDS log-spaced frequency bands (bass first), updated ~90x/s from the audio that is really playing */
    @Volatile var spectrum = FloatArray(BANDS)
    private val ring = FloatArray(FFT_N); private var rp = 0; private var since = 0
    private val re = FloatArray(FFT_N); private val im = FloatArray(FFT_N)
    private val win = FloatArray(FFT_N) { (0.5 - 0.5 * kotlin.math.cos(2.0 * Math.PI * it / (FFT_N - 1))).toFloat() }
    private val sm = FloatArray(BANDS)
    private var edges = IntArray(BANDS + 1) { it + 1 }

    companion object { const val BANDS = 48; const val FFT_N = 1024 }

    private fun push(x: Float) { ring[rp] = x; rp = (rp + 1) and (FFT_N - 1); if (++since >= 512) { since = 0; analyze() } }

    private fun analyze() {
        for (i in 0 until FFT_N) { re[i] = ring[(rp + i) and (FFT_N - 1)] * win[i]; im[i] = 0f }
        fft()
        val out = FloatArray(BANDS)
        for (b in 0 until BANDS) {
            var m = 0f; val a = edges[b]; val e = maxOf(edges[b + 1], a + 1)
            for (k in a until minOf(e, FFT_N / 2)) m = maxOf(m, kotlin.math.sqrt(re[k] * re[k] + im[k] * im[k]))
            val db = 20f * kotlin.math.log10(m / (FFT_N / 4f) + 1e-6f)
            val v = ((db + 62f) / 50f + b * 0.004f).coerceIn(0f, 1f)
            sm[b] = if (v > sm[b]) sm[b] * 0.35f + v * 0.65f else sm[b] * 0.86f + v * 0.14f
            out[b] = sm[b]
        }
        spectrum = out
    }

    private fun fft() {
        val n = FFT_N
        var j = 0
        for (i in 1 until n) { var bit = n shr 1; while (j and bit != 0) { j = j xor bit; bit = bit shr 1 }; j = j xor bit
            if (i < j) { val tr = re[i]; re[i] = re[j]; re[j] = tr; val ti = im[i]; im[i] = im[j]; im[j] = ti } }
        var len = 2
        while (len <= n) {
            val ang = (-2.0 * Math.PI / len); val wr = kotlin.math.cos(ang).toFloat(); val wi = kotlin.math.sin(ang).toFloat()
            var i = 0
            while (i < n) {
                var cr = 1f; var ci = 0f
                for (k in 0 until len / 2) {
                    val ur = re[i + k]; val ui = im[i + k]
                    val vr = re[i + k + len / 2] * cr - im[i + k + len / 2] * ci
                    val vi = re[i + k + len / 2] * ci + im[i + k + len / 2] * cr
                    re[i + k] = ur + vr; im[i + k] = ui + vi
                    re[i + k + len / 2] = ur - vr; im[i + k + len / 2] = ui - vi
                    val ncr = cr * wr - ci * wi; ci = cr * wi + ci * wr; cr = ncr
                }
                i += len
            }
            len = len shl 1
        }
    }
    private var cur = 1f
    private var coef = 0.0001f

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT && inputAudioFormat.encoding != C.ENCODING_PCM_FLOAT)
            throw AudioProcessor.UnhandledAudioFormatException(inputAudioFormat)
        coef = 1f - exp(-1f / (inputAudioFormat.sampleRate * 0.35f))
        val binHz = inputAudioFormat.sampleRate.toFloat() / FFT_N
        edges = IntArray(BANDS + 1) { k -> (40f * Math.pow(14000.0 / 40.0, k.toDouble() / BANDS).toFloat() / binHz).toInt().coerceIn(1, FFT_N / 2 - 1) }
        return inputAudioFormat
    }

    private fun lim(x: Float): Float {
        val a = abs(x)
        if (a <= 0.85f) return x
        val y = 0.85f + 0.15f * tanh((a - 0.85f) / 0.15f)
        return if (x < 0) -y else y
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val size = inputBuffer.remaining()
        if (size == 0) return
        val out = replaceOutputBuffer(size)
        val ch = inputAudioFormat.channelCount
        val t = targetGain
        if (inputAudioFormat.encoding == C.ENCODING_PCM_16BIT) {
            while (inputBuffer.remaining() >= 2 * ch) {
                cur += (t - cur) * coef
                var mono = 0f
                for (c in 0 until ch) {
                    val v = inputBuffer.short / 32768f
                    mono += v
                    out.putShort((lim(v * cur) * 32767f).toInt().toShort())
                }
                push(mono / ch)
            }
        } else {
            while (inputBuffer.remaining() >= 4 * ch) {
                cur += (t - cur) * coef
                var mono = 0f
                for (c in 0 until ch) { val v = inputBuffer.float; mono += v; out.putFloat(lim(v * cur)) }
                push(mono / ch)
            }
        }
        // any trailing partial frame is passed through untouched
        while (inputBuffer.hasRemaining()) out.put(inputBuffer.get())
        out.flip()
    }

    override fun onReset() { cur = targetGain }
}
