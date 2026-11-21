package com.auralis.player.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.util.LruCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/** Cover art: downloaded thumbnail if present, otherwise the picture embedded in the audio tags. null => placeholder. */
object ArtLoader {
    private val cache = object : LruCache<String, Bitmap>(28 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }
    private val missing = ConcurrentHashMap.newKeySet<String>()
    private val gate = Semaphore(3)

    private fun key(s: Song, px: Int) = "${s.id}@$px"

    fun peek(s: Song?, px: Int): Bitmap? = s?.let { cache.get(key(it, px)) }

    suspend fun load(ctx: Context, s: Song, px: Int): Bitmap? {
        val k = key(s, px)
        cache.get(k)?.let { return it }
        if (k in missing) return null
        return gate.withPermit {
            withContext(Dispatchers.IO) {
                cache.get(k)?.let { return@withContext it }
                val raw = rawBytes(ctx, s)
                val bmp = raw?.let { decode(it, px) }
                if (bmp == null) missing += k else cache.put(k, bmp)
                bmp
            }
        }
    }

    /** JPEG bytes (<= 512px) for the notification / lock-screen. */
    suspend fun notificationBytes(ctx: Context, s: Song): ByteArray? = withContext(Dispatchers.IO) {
        val raw = rawBytes(ctx, s) ?: return@withContext null
        val bmp = decode(raw, 512) ?: return@withContext null
        ByteArrayOutputStream().use { out -> bmp.compress(Bitmap.CompressFormat.JPEG, 90, out); out.toByteArray() }
    }

    private fun rawBytes(ctx: Context, s: Song): ByteArray? {
        s.thumbPath?.let { p ->
            val f = File(p)
            if (f.exists()) return runCatching { f.readBytes() }.getOrNull()
        }
        val r = MediaMetadataRetriever()
        return try {
            r.setDataSource(ctx, Uri.parse(s.uri))
            r.embeddedPicture
        } catch (_: Throwable) { null } finally { runCatching { r.release() } }
    }

    private fun decode(bytes: ByteArray, px: Int): Bitmap? {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, o)
        if (o.outWidth <= 0) return null
        var sample = 1
        while (o.outWidth / (sample * 2) >= px && o.outHeight / (sample * 2) >= px) sample *= 2
        val d = BitmapFactory.Options().apply { inSampleSize = sample }
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, d)
    }
}
