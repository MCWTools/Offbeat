package com.auralis.player.data

import android.content.Context
import android.net.Uri
import java.io.ByteArrayOutputStream
import java.io.FileInputStream
import java.nio.charset.Charset

/** Reads lyrics that are stored INSIDE the audio file: ID3v2 USLT / SYLT (mp3), MP4 ©lyr (m4a), Vorbis LYRICS= (ogg / flac). Returns LRC-style text. */
object EmbeddedLyrics {
    fun read(ctx: Context, s: Song): String? = runCatching {
        val head = readRange(ctx, s, 0, 6 * 1024 * 1024) ?: return null
        id3(head) ?: vorbis(head) ?: mp4(head) ?: run {
            // m4a often keeps its metadata at the end of the file
            val size = fileSize(ctx, s)
            if (size > head.size) readRange(ctx, s, maxOf(0L, size - 4 * 1024 * 1024), 4 * 1024 * 1024)?.let { mp4(it) } else null
        }
    }.getOrNull()

    private fun fileSize(ctx: Context, s: Song): Long =
        ctx.contentResolver.openFileDescriptor(Uri.parse(s.uri), "r")?.use { it.statSize } ?: 0L

    private fun readRange(ctx: Context, s: Song, from: Long, max: Int): ByteArray? {
        val pfd = ctx.contentResolver.openFileDescriptor(Uri.parse(s.uri), "r") ?: return null
        pfd.use {
            FileInputStream(it.fileDescriptor).use { ins ->
                val ch = ins.channel; ch.position(from)
                val out = ByteArrayOutputStream(); val buf = java.nio.ByteBuffer.allocate(64 * 1024); var total = 0
                while (total < max) { buf.clear(); val n = ch.read(buf); if (n <= 0) break; out.write(buf.array(), 0, minOf(n, max - total)); total += n }
                return out.toByteArray()
            }
        }
    }

    // ---------------------------------------------------------------- ID3v2
    private fun syncsafe(b: ByteArray, o: Int) = (b[o].toInt() and 0x7F shl 21) or (b[o + 1].toInt() and 0x7F shl 14) or (b[o + 2].toInt() and 0x7F shl 7) or (b[o + 3].toInt() and 0x7F)
    private fun be32(b: ByteArray, o: Int) = (b[o].toInt() and 0xFF shl 24) or (b[o + 1].toInt() and 0xFF shl 16) or (b[o + 2].toInt() and 0xFF shl 8) or (b[o + 3].toInt() and 0xFF)

    private fun charset(enc: Int): Charset = when (enc) { 1 -> Charsets.UTF_16; 2 -> Charsets.UTF_16BE; 3 -> Charsets.UTF_8; else -> Charsets.ISO_8859_1 }
    private fun termLen(enc: Int) = if (enc == 1 || enc == 2) 2 else 1

    /** index just after the terminator that ends the string starting at [from] */
    private fun skipString(b: ByteArray, from: Int, end: Int, enc: Int): Int {
        val t = termLen(enc); var i = from
        while (i + t <= end) { if (t == 1 && b[i].toInt() == 0) return i + 1; if (t == 2 && b[i].toInt() == 0 && b[i + 1].toInt() == 0) return i + 2; i += t }
        return end
    }

    private fun id3(b: ByteArray): String? {
        if (b.size < 10 || b[0] != 'I'.code.toByte() || b[1] != 'D'.code.toByte() || b[2] != '3'.code.toByte()) return null
        val ver = b[3].toInt(); val flags = b[5].toInt()
        val size = syncsafe(b, 6); val endTag = minOf(b.size, 10 + size)
        var body = b.copyOfRange(10, endTag)
        if (flags and 0x80 != 0 && ver < 4) { // unsynchronisation: FF 00 -> FF
            val o = ByteArrayOutputStream(); var i = 0
            while (i < body.size) { o.write(body[i].toInt()); if (body[i].toInt() == -1 && i + 1 < body.size && body[i + 1].toInt() == 0) i++; i++ }
            body = o.toByteArray()
        }
        var p = 0
        if (flags and 0x40 != 0 && body.size >= 4) p = if (ver == 4) syncsafe(body, 0) else be32(body, 0) + 4
        var uslt: String? = null; var sylt: String? = null
        while (p + 10 <= body.size) {
            val id = String(body, p, 4, Charsets.ISO_8859_1)
            if (body[p].toInt() == 0) break
            val fsz = if (ver == 4) syncsafe(body, p + 4) else be32(body, p + 4)
            val ds = p + 10; val de = ds + fsz
            if (fsz <= 0 || de > body.size) break
            if (id == "USLT" && uslt == null && fsz > 5) {
                val enc = body[ds].toInt() and 0xFF
                val textStart = skipString(body, ds + 4, de, enc)
                uslt = String(body, textStart, de - textStart, charset(enc)).replace("\r\n", "\n").replace('\r', '\n').trim('\u0000', ' ', '\n')
            } else if (id == "SYLT" && sylt == null && fsz > 6) {
                val enc = body[ds].toInt() and 0xFF
                val fmtTs = body[ds + 4].toInt()
                var q = skipString(body, ds + 6, de, enc)
                val sb = StringBuilder(); val cs = charset(enc)
                while (q < de) {
                    val st = q; val tEnd = skipString(body, q, de, enc)
                    val textEnd = tEnd - termLen(enc)
                    if (tEnd + 4 > de + 0 && tEnd + 4 > body.size) break
                    val ts = if (tEnd + 4 <= de) be32(body, tEnd) else 0
                    val text = String(body, st, maxOf(0, textEnd - st), cs).replace("\n", "").trim()
                    if (fmtTs == 2 && text.isNotEmpty()) { val m = ts / 60000; val sec = (ts / 1000) % 60; val cc = (ts % 1000) / 10; sb.append("[%02d:%02d.%02d]%s\n".format(m, sec, cc, text)) }
                    q = tEnd + 4
                }
                if (sb.isNotEmpty()) sylt = sb.toString()
            }
            p = de
        }
        return sylt ?: uslt?.takeIf { it.isNotBlank() }
    }

    // ---------------------------------------------------------------- Vorbis comments (ogg / flac / opus)
    private fun vorbis(b: ByteArray): String? {
        for (key in listOf("UNSYNCEDLYRICS=", "LYRICS=", "SYNCEDLYRICS=")) {
            val k = key.toByteArray(Charsets.US_ASCII)
            var i = 4
            while (i < b.size - k.size) {
                var ok = true
                for (j in k.indices) { val c = b[i + j].toInt(); val u = if (c in 97..122) c - 32 else c; if (u != k[j].toInt()) { ok = false; break } }
                if (ok) {
                    val len = (b[i - 4].toInt() and 0xFF) or (b[i - 3].toInt() and 0xFF shl 8) or (b[i - 2].toInt() and 0xFF shl 16) or (b[i - 1].toInt() and 0xFF shl 24)
                    if (len in k.size..2_000_000 && i + len <= b.size) return String(b, i + k.size, len - k.size, Charsets.UTF_8).replace("\r\n", "\n").trim().takeIf { it.isNotEmpty() }
                }
                i++
            }
        }
        return null
    }

    // ---------------------------------------------------------------- MP4 / m4a  (©lyr -> data atom)
    private fun mp4(b: ByteArray): String? {
        val tag = byteArrayOf(0xA9.toByte(), 'l'.code.toByte(), 'y'.code.toByte(), 'r'.code.toByte())
        var i = 4
        while (i < b.size - 24) {
            if (b[i] == tag[0] && b[i + 1] == tag[1] && b[i + 2] == tag[2] && b[i + 3] == tag[3]) {
                val atomSize = be32(b, i - 4)
                val d = i + 4                                  // child "data" atom: size, 'data', flags(4), locale(4), text
                if (d + 16 <= b.size && String(b, d + 4, 4, Charsets.ISO_8859_1) == "data") {
                    val dsz = be32(b, d)
                    val ts = d + 16; val te = minOf(b.size, d + dsz)
                    if (te > ts && atomSize > 0) return String(b, ts, te - ts, Charsets.UTF_8).replace("\r\n", "\n").trim().takeIf { it.isNotEmpty() }
                }
            }
            i++
        }
        return null
    }
}
