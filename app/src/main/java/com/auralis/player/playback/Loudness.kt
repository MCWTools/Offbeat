package com.auralis.player.playback

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import com.auralis.player.data.Song
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/** Per-song loudness map: mean-square level (dB) of every 0.5 s window, computed once by decoding the file, cached on disk. */
object Loudness {
    private const val WIN_SEC = 0.5

    class Profile(val db: FloatArray) {
        /** gated average level of the whole song */
        val songDb: Float = run {
            var s = 0.0; var n = 0
            for (v in db) if (v > -55f) { s += 10.0.pow(v / 10.0); n++ }
            if (n == 0) -18f else (10 * log10(s / n)).toFloat()
        }

        /** How many dB each of n equal segments should be nudged toward the song average (only part of the way, so dynamics survive). */
        fun corrections(n: Int): FloatArray {
            val out = FloatArray(n)
            val w = db.size
            for (k in 0 until n) {
                val a = (k.toLong() * w / n).toInt(); val b = max(a + 1, ((k + 1).toLong() * w / n).toInt())
                var s = 0.0; var c = 0
                for (i in a until min(b, w)) if (db[i] > -55f) { s += 10.0.pow(db[i] / 10.0); c++ }
                if (c == 0) continue
                val lk = (10 * log10(s / c)).toFloat()
                val diff = lk - songDb
                if (diff < -20f) continue                      // near-silent passages (fade-outs): leave alone
                out[k] = (-0.6f * diff).coerceIn(-6f, 6f)
            }
            return out
        }
    }

    private val mem = HashMap<String, Profile>()

    suspend fun get(ctx: Context, song: Song): Profile? = withContext(Dispatchers.Default) {
        synchronized(mem) { mem[song.id] }?.let { return@withContext it }
        val f = File(File(ctx.filesDir, "loud").apply { mkdirs() }, (song.id.hashCode().toUInt().toString(16)) + "_" + song.durationMs + ".bin")
        var p: Profile? = null
        if (f.exists()) runCatching {
            val bb = ByteBuffer.wrap(f.readBytes()).order(ByteOrder.LITTLE_ENDIAN)
            val arr = FloatArray(bb.remaining() / 4); bb.asFloatBuffer().get(arr); if (arr.isNotEmpty()) p = Profile(arr)
        }
        if (p == null) {
            p = runCatching { analyze(ctx, song) }.getOrNull()
            p?.let { pr ->
                runCatching {
                    val bb = ByteBuffer.allocate(pr.db.size * 4).order(ByteOrder.LITTLE_ENDIAN)
                    bb.asFloatBuffer().put(pr.db); f.writeBytes(bb.array())
                }
            }
        }
        p?.also { synchronized(mem) { mem[song.id] = it } }
    }

    private suspend fun analyze(ctx: Context, song: Song): Profile? = withContext(Dispatchers.Default) {
        val ex = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            ex.setDataSource(ctx, Uri.parse(song.uri), null)
            var track = -1
            for (i in 0 until ex.trackCount) if (ex.getTrackFormat(i).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true) { track = i; break }
            if (track < 0) return@withContext null
            ex.selectTrack(track)
            val fmt = ex.getTrackFormat(track)
            val mime = fmt.getString(MediaFormat.KEY_MIME) ?: return@withContext null
            codec = MediaCodec.createDecoderByType(mime).also { it.configure(fmt, null, null, 0); it.start() }
            var rate = fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var ch = fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            var enc = AudioFormat.ENCODING_PCM_16BIT
            val info = MediaCodec.BufferInfo()
            val out = ArrayList<Float>()
            var sumSq = 0.0; var frames = 0L
            var inDone = false; var outDone = false
            while (!outDone && isActive) {
                if (!inDone) {
                    val ii = codec.dequeueInputBuffer(5_000)
                    if (ii >= 0) {
                        val buf = codec.getInputBuffer(ii)!!
                        val n = ex.readSampleData(buf, 0)
                        if (n < 0) { codec.queueInputBuffer(ii, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM); inDone = true }
                        else { codec.queueInputBuffer(ii, 0, n, ex.sampleTime, 0); ex.advance() }
                    }
                }
                val oi = codec.dequeueOutputBuffer(info, 5_000)
                if (oi >= 0) {
                    if (info.size > 0) {
                        val ob = codec.getOutputBuffer(oi)!!.order(ByteOrder.nativeOrder())
                        ob.position(info.offset); ob.limit(info.offset + info.size)
                        val winFrames = (rate * WIN_SEC).toLong()
                        if (enc == AudioFormat.ENCODING_PCM_FLOAT) {
                            while (ob.remaining() >= 4 * ch) {
                                var m = 0f; for (c in 0 until ch) m += ob.float; m /= ch
                                sumSq += m * m; frames++
                                if (frames >= winFrames) { out.add(toDb(sumSq / frames)); sumSq = 0.0; frames = 0 }
                            }
                        } else {
                            while (ob.remaining() >= 2 * ch) {
                                var m = 0f; for (c in 0 until ch) m += ob.short / 32768f; m /= ch
                                sumSq += m * m; frames++
                                if (frames >= winFrames) { out.add(toDb(sumSq / frames)); sumSq = 0.0; frames = 0 }
                            }
                        }
                    }
                    codec.releaseOutputBuffer(oi, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outDone = true
                } else if (oi == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    val nf = codec.outputFormat
                    rate = nf.getInteger(MediaFormat.KEY_SAMPLE_RATE); ch = nf.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                    enc = if (nf.containsKey(MediaFormat.KEY_PCM_ENCODING)) nf.getInteger(MediaFormat.KEY_PCM_ENCODING) else AudioFormat.ENCODING_PCM_16BIT
                }
            }
            if (frames > rate * 0.15) out.add(toDb(sumSq / frames))
            if (out.isEmpty()) null else Profile(out.toFloatArray())
        } finally {
            runCatching { codec?.stop() }; runCatching { codec?.release() }; runCatching { ex.release() }
        }
    }

    private fun toDb(meanSq: Double): Float = (10 * log10(meanSq + 1e-12)).toFloat()
}
