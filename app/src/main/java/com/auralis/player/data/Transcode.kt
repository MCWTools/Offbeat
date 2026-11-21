package com.auralis.player.data

import android.content.Context
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import java.io.File

/** Re-encodes any audio the phone can decode into AAC (.m4a). Used to make shared / backed-up files much smaller. */
object Transcode {
    fun toAac(ctx: Context, uri: Uri, out: File, bitrate: Int = 96_000): Boolean {
        val ex = MediaExtractor()
        var dec: MediaCodec? = null
        var enc: MediaCodec? = null
        var mux: MediaMuxer? = null
        var muxStarted = false
        try {
            ex.setDataSource(ctx, uri, null)
            val ti = (0 until ex.trackCount).firstOrNull { ex.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true } ?: return false
            ex.selectTrack(ti)
            val inFmt = ex.getTrackFormat(ti)
            dec = MediaCodec.createDecoderByType(inFmt.getString(MediaFormat.KEY_MIME)!!)
            dec.configure(inFmt, null, null, 0); dec.start()
            mux = MediaMuxer(out.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            var track = -1
            var encDone = false
            var sr = 0; var ch = 0
            var pcmBytes = 0L
            val info = MediaCodec.BufferInfo()
            val einfo = MediaCodec.BufferInfo()

            fun drainEnc(timeoutUs: Long) {
                val e = enc ?: return
                while (true) {
                    val oi = e.dequeueOutputBuffer(einfo, timeoutUs)
                    when {
                        oi == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> { track = mux!!.addTrack(e.outputFormat); mux.start(); muxStarted = true }
                        oi >= 0 -> {
                            val buf = e.getOutputBuffer(oi)!!
                            if (einfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) einfo.size = 0
                            if (einfo.size > 0 && muxStarted) { buf.position(einfo.offset); buf.limit(einfo.offset + einfo.size); mux!!.writeSampleData(track, buf, einfo) }
                            e.releaseOutputBuffer(oi, false)
                            if (einfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) { encDone = true; return }
                        }
                        else -> return
                    }
                }
            }
            fun startEnc(f: MediaFormat) {
                sr = f.getInteger(MediaFormat.KEY_SAMPLE_RATE); ch = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                val ef = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, sr, ch).apply {
                    setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                    setInteger(MediaFormat.KEY_BIT_RATE, bitrate * ch / 2)
                    setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 64 * 1024)
                }
                enc = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC).also { it.configure(ef, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE); it.start() }
            }
            fun feedEnc(src: java.nio.ByteBuffer?, size: Int, eos: Boolean) {
                val e = enc ?: return
                var left = size
                if (src == null || size == 0) {
                    if (eos) while (true) { val ei = e.dequeueInputBuffer(10_000); if (ei >= 0) { e.queueInputBuffer(ei, 0, 0, pcmBytes * 1_000_000 / (sr * ch * 2L), MediaCodec.BUFFER_FLAG_END_OF_STREAM); break }; drainEnc(0) }
                    return
                }
                while (left > 0 || eos) {
                    val ei = e.dequeueInputBuffer(10_000)
                    if (ei < 0) { drainEnc(0); continue }
                    val ib = e.getInputBuffer(ei)!!; ib.clear()
                    val n = minOf(left, ib.capacity())
                    val old = src.limit(); src.limit(src.position() + n); ib.put(src); src.limit(old)
                    val pts = pcmBytes * 1_000_000 / (sr * ch * 2L)
                    pcmBytes += n; left -= n
                    val last = left == 0 && eos
                    e.queueInputBuffer(ei, 0, n, pts, if (last) MediaCodec.BUFFER_FLAG_END_OF_STREAM else 0)
                    if (last) return
                    drainEnc(0)
                    if (left == 0) return
                }
            }

            var inputDone = false
            var decDone = false
            while (!decDone) {
                if (!inputDone) {
                    val ii = dec.dequeueInputBuffer(5_000)
                    if (ii >= 0) {
                        val ib = dec.getInputBuffer(ii)!!
                        val n = ex.readSampleData(ib, 0)
                        if (n < 0) { dec.queueInputBuffer(ii, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM); inputDone = true }
                        else { dec.queueInputBuffer(ii, 0, n, ex.sampleTime, 0); ex.advance() }
                    }
                }
                val oi = dec.dequeueOutputBuffer(info, 5_000)
                if (oi >= 0) {
                    if (enc == null) {
                        val f = dec.outputFormat
                        if (f.getInteger(MediaFormat.KEY_CHANNEL_COUNT) > 2) return false
                        startEnc(f)
                    }
                    val ob = dec.getOutputBuffer(oi)!!
                    ob.position(info.offset); ob.limit(info.offset + info.size)
                    val eos = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                    if (info.size > 0) feedEnc(ob, info.size, eos) else if (eos) feedEnc(null, 0, true)
                    dec.releaseOutputBuffer(oi, false)
                    if (eos) decDone = true
                } else if (oi == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED && enc == null) {
                    val f = dec.outputFormat
                    if (f.getInteger(MediaFormat.KEY_CHANNEL_COUNT) > 2) return false
                    startEnc(f)
                }
            }
            var guard = 0
            while (!encDone && guard++ < 2000) drainEnc(10_000)
            return encDone && muxStarted
        } catch (_: Throwable) {
            return false
        } finally {
            runCatching { dec?.stop() }; runCatching { dec?.release() }
            runCatching { enc?.stop() }; runCatching { enc?.release() }
            runCatching { if (muxStarted) mux?.stop() }; runCatching { mux?.release() }
            runCatching { ex.release() }
        }
    }
}
