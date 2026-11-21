package com.auralis.player.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import com.auralis.player.Graph
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * The .offbeat file is a plain ZIP:
 *   manifest.json  — index: songs (title/artist/album/duration), playlists (lists of song numbers), favourites
 *   m/<n>.<ext>    — audio, only for songs that were ticked in the Music tab
 *   a/<n>.jpg      — small cover (256px, JPEG 72%)
 */
object Archive {
    class Result(val playlists: Int, val added: Int, val matched: Int, val missing: Int)

    fun keyOf(s: Song) = s.title.trim().lowercase() + "|" + s.artist.trim().lowercase()

    private fun smallCover(ctx: Context, s: Song): ByteArray? {
        val bmp = runCatching { kotlinx.coroutines.runBlocking { ArtLoader.load(ctx, s, 256) } }.getOrNull() ?: return null
        return ByteArrayOutputStream().use { o -> bmp.compress(Bitmap.CompressFormat.JPEG, 72, o); o.toByteArray() }
    }

    /**
     * @param indexSongs every song that appears in the index (playlist members + music with files)
     * @param withFiles  ids whose audio file goes inside
     */
    suspend fun write(
        ctx: Context, out: OutputStream, indexSongs: List<Song>, playlists: List<Playlist>, withFiles: Set<String>,
        compressAudio: Boolean, onProgress: (Float, String) -> Unit,
    ) = withContext(Dispatchers.IO) {
        val order = indexSongs.distinctBy { it.id }
        val num = order.withIndex().associate { it.value.id to it.index }
        val manifest = JSONObject().put("app", "Offbeat").put("v", 1)
        manifest.put("songs", JSONArray().also { a ->
            order.forEachIndexed { i, s ->
                val ext = (s.path?.substringAfterLast('.', "") ?: "").takeIf { it.isNotEmpty() && it.length <= 5 } ?: "mp3"
                a.put(JSONObject().put("n", i).put("title", s.title).put("artist", s.artist).put("album", s.album).put("dur", s.durationMs)
                    .put("file", s.id in withFiles).put("ext", if (compressAudio) "m4a" else ext))
            }
        })
        manifest.put("playlists", JSONArray().also { a ->
            playlists.forEach { p -> a.put(JSONObject().put("name", p.name).put("songs", JSONArray(p.songIds.mapNotNull { num[it] }))) }
        })
        manifest.put("favs", JSONArray(order.filter { Graph.store.isFav(it.id) }.map { num[it.id]!! }))

        ZipOutputStream(out.buffered(256 * 1024)).use { zos ->
            zos.setLevel(Deflater.BEST_COMPRESSION)
            zos.putNextEntry(ZipEntry("manifest.json")); zos.write(manifest.toString().toByteArray()); zos.closeEntry()
            val total = order.size.coerceAtLeast(1)
            order.forEachIndexed { i, s ->
                onProgress(i.toFloat() / total, s.title)
                if (s.id in withFiles) {
                    val ext = if (compressAudio) "m4a" else ((s.path?.substringAfterLast('.', "") ?: "").takeIf { it.isNotEmpty() && it.length <= 5 } ?: "mp3")
                    var tmp: File? = null
                    try {
                        var useUri = Uri.parse(s.uri); var asM4a = false
                        if (compressAudio) {
                            val t = File(ctx.cacheDir, "tc_$i.m4a").also { it.delete() }; tmp = t
                            val orig = runCatching { ctx.contentResolver.openFileDescriptor(Uri.parse(s.uri), "r")?.use { it.statSize } ?: 0L }.getOrDefault(0L)
                            if (Transcode.toAac(ctx, Uri.parse(s.uri), t) && t.length() > 0 && (orig == 0L || t.length() < orig)) { useUri = Uri.fromFile(t); asM4a = true }
                        }
                        // the manifest said m4a if compressing; keep that name even when transcoding failed (players sniff the real format)
                        zos.setLevel(Deflater.NO_COMPRESSION)
                        zos.putNextEntry(ZipEntry("m/$i.${if (compressAudio) "m4a" else ext}"))
                        (if (useUri.scheme == "file") File(useUri.path!!).inputStream() else ctx.contentResolver.openInputStream(useUri))?.use { it.copyTo(zos, 128 * 1024) }
                        zos.closeEntry()
                        if (compressAudio && !asM4a) { /* original bytes under .m4a name; ExoPlayer detects by content */ }
                    } finally { tmp?.delete(); zos.setLevel(Deflater.BEST_COMPRESSION) }
                }
                smallCover(ctx, s)?.let { b -> zos.setLevel(Deflater.NO_COMPRESSION); zos.putNextEntry(ZipEntry("a/$i.jpg")); zos.write(b); zos.closeEntry(); zos.setLevel(Deflater.BEST_COMPRESSION) }
            }
            onProgress(1f, "")
        }
    }

    private fun sha(s: String) = MessageDigest.getInstance("SHA-1").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }.take(16)

    /** Reads an .offbeat file: restores audio into the app, links playlists to songs already on the phone, recreates playlists + favourites. */
    suspend fun read(ctx: Context, uri: Uri, onProgress: (String) -> Unit): Result = withContext(Dispatchers.IO) {
        val store = Graph.store; val lib = Graph.library; val root = Graph.downloader.root
        val existing = lib.songs
        fun findExisting(title: String, artist: String, dur: Long): Song? =
            existing.firstOrNull { it.title.trim().equals(title.trim(), true) && it.artist.trim().equals(artist.trim(), true) && Math.abs(it.durationMs - dur) <= 1500 }
                ?: existing.firstOrNull { it.title.trim().equals(title.trim(), true) && Math.abs(it.durationMs - dur) <= 1500 }

        var manifest: JSONObject? = null
        val ids = HashMap<Int, String>()           // n -> song id in this app
        val newSongs = ArrayList<Song>()
        var matched = 0
        val pendingFiles = HashMap<Int, File>(); val pendingArt = HashMap<Int, File>()
        val work = File(root, "ob_" + System.currentTimeMillis()).apply { mkdirs() }

        ctx.contentResolver.openInputStream(uri)!!.buffered(256 * 1024).use { ins ->
            val zis = ZipInputStream(ins)
            var e = zis.nextEntry
            while (e != null) {
                val name = e.name
                when {
                    name == "manifest.json" -> manifest = JSONObject(String(zis.readBytes()))
                    name.startsWith("m/") || name.startsWith("a/") -> {
                        val n = name.substringAfter('/').substringBefore('.').toIntOrNull()
                        val m = manifest
                        if (n != null && m != null) {
                            val so = m.getJSONArray("songs").optJSONObject(n)
                            val skip = so != null && name.startsWith("m/") && findExisting(so.optString("title"), so.optString("artist"), so.optLong("dur")) != null
                            if (!skip) {
                                val f = File(work, name.replace('/', '_'))
                                f.outputStream().use { zis.copyTo(it, 128 * 1024) }
                                if (name.startsWith("m/")) pendingFiles[n] = f else pendingArt[n] = f
                                onProgress(so?.optString("title") ?: "")
                            }
                        }
                    }
                }
                zis.closeEntry(); e = zis.nextEntry
            }
        }
        val m = manifest ?: run { work.deleteRecursively(); throw IllegalArgumentException("Not an Offbeat file") }
        val sa = m.getJSONArray("songs")
        var missing = 0
        for (i in 0 until sa.length()) {
            val so = sa.getJSONObject(i); val n = so.getInt("n")
            val title = so.optString("title"); val artist = so.optString("artist"); val dur = so.optLong("dur")
            val ex = findExisting(title, artist, dur)
            if (ex != null) { ids[n] = ex.id; matched++; continue }
            val f = pendingFiles[n]
            if (f == null) { missing++; continue }
            val dir = File(root, "ob" + sha("$title|$artist|$dur")).apply { deleteRecursively(); mkdirs() }
            val ext = f.name.substringAfterLast('.', "mp3")
            val audio = File(dir, "audio.$ext"); f.copyTo(audio, true); f.delete()
            val thumb = pendingArt[n]?.let { a -> File(dir, "thumb.jpg").also { t -> a.copyTo(t, true); a.delete() } }
            val s = Song("dl:ob" + sha("$title|$artist|$dur"), Uri.fromFile(audio).toString(), title, artist, so.optString("album"), dur,
                System.currentTimeMillis(), Sources.LOCAL, audio.absolutePath, thumb?.absolutePath)
            newSongs += s; ids[n] = s.id
        }
        work.deleteRecursively()
        withContext(Dispatchers.Main) {
            if (newSongs.isNotEmpty()) store.addImported(newSongs)
            lib.refresh()
            val pls = m.optJSONArray("playlists") ?: JSONArray()
            for (i in 0 until pls.length()) {
                val po = pls.getJSONObject(i); val arr = po.getJSONArray("songs")
                val list = (0 until arr.length()).mapNotNull { ids[arr.getInt(it)] }
                var name = po.optString("name").ifBlank { "Imported" }
                if (store.playlists.any { it.name == name }) name += " (imported)"
                store.createPlaylist(name, list)
            }
            val fv = m.optJSONArray("favs")
            if (fv != null) store.setFav((0 until fv.length()).mapNotNull { ids[fv.getInt(it)] }, true)
        }
        Result(m.optJSONArray("playlists")?.length() ?: 0, newSongs.size, matched, missing)
    }
}
