package com.auralis.player.data

import android.content.Context
import android.content.Intent
import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.auralis.player.playback.DownloadService
import com.yausername.ffmpeg.FFmpeg
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.UUID

/**
 * Paste a link (YouTube / TikTok / any direct video or audio URL) -> the app downloads it, extracts the audio as mp3,
 * grabs the thumbnail + uploader name and adds it to the music list as "[Youtube] Title" / "[TikTok] ..." / "[Video] ...".
 * Powered by yt-dlp + ffmpeg running inside the app.
 */
class Downloader(private val ctx: Context, private val store: Store, private val library: Library, private val scope: CoroutineScope) {
    enum class State { QUEUED, WORKING, DONE, FAILED }
    data class Job(
        val id: String, val url: String, val title: String = "", val progress: Float = 0f,
        val state: State = State.QUEUED, val message: String = "Waiting…", val elapsedSec: Int = 0,
        val hidden: Boolean = false,
    )

    var jobs by mutableStateOf<List<Job>>(emptyList()); private set
    var engineMessage by mutableStateOf(""); private set
    val busy: Boolean get() = jobs.any { it.state == State.QUEUED || it.state == State.WORKING }

    private val lock = Mutex()
    private var engineReady = false
    val root = File(ctx.filesDir, "downloads").apply { mkdirs() }

    private fun sha(s: String) = MessageDigest.getInstance("SHA-1").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }.take(16)

    @Synchronized
    private fun update(id: String, f: (Job) -> Job) { jobs = jobs.map { if (it.id == id) f(it) else it } }

    fun dismiss(id: String) { jobs = jobs.filter { it.id != id } }

    /** Accepts pasted text that contains one or more links. Returns how many links were queued. */
    fun enqueue(raw: String, wholePlaylist: Boolean = true): Int {
        val urls = Regex("https?://\\S+").findAll(raw).map { it.value.trimEnd(')', ',', '.', ']') }.distinct().toList()
        urls.forEach { url ->
            val pl = if (wholePlaylist) playlistUrl(url) else null
            if (pl != null) { startPlaylist(pl); return@forEach }
            val job = Job(UUID.randomUUID().toString(), url)
            jobs = jobs + job
            runCatching { ctx.startForegroundService(Intent(ctx, DownloadService::class.java)) }
            scope.launch(Dispatchers.IO) { lock.withLock { run(job.id) } }
        }
        return urls.size
    }

    private suspend fun run(id: String) {
        val job = jobs.firstOrNull { it.id == id } ?: return
        update(id) { it.copy(state = State.WORKING, message = if (engineReady) "Reading video info…" else "Preparing engine (first time only)…") }
        val t0 = System.currentTimeMillis()
        val ticker = scope.launch { while (true) { delay(1000); update(id) { it.copy(elapsedSec = ((System.currentTimeMillis() - t0) / 1000).toInt()) } } }
        try {
            ensureEngine()
            val songId = "dl:" + sha(job.url)
            if (library.byId.containsKey(songId)) { update(id) { it.copy(state = State.DONE, progress = 1f, message = "Already in your list") }; childFinished(id, songId, true); autoClear(id); return }
            val work = File(root, sha(job.url)).apply { deleteRecursively(); mkdirs() }
            val song = try {
                download(job, work, songId)
            } catch (e: CancellationException) { throw e } catch (e: Exception) {
                val m = e.message.orEmpty()
                if ("Unsupported URL" in m || "rivate" in m || "Unable to resolve host" in m || "No audio" in m) throw e
                // yt-dlp breaks often when sites change: update it once and retry
                update(id) { it.copy(message = "Updating downloader, retrying…", progress = 0f) }
                runCatching { YoutubeDL.getInstance().updateYoutubeDL(ctx, YoutubeDL.UpdateChannel._STABLE) }
                work.deleteRecursively(); work.mkdirs()
                download(job, work, songId)
            }
            withContext(Dispatchers.Main) { store.addImported(listOf(song)); library.refresh() }
            update(id) { it.copy(state = State.DONE, progress = 1f, title = song.title, message = "Added to your music") }
            childFinished(id, songId, true)
            autoClear(id)
        } catch (e: CancellationException) { throw e } catch (e: Throwable) {
            update(id) { it.copy(state = State.FAILED, message = friendly(e)) }
            childFinished(id, "", false)
        } finally { ticker.cancel() }
    }

    private fun download(job: Job, work: File, songId: String): Song {
        val req = YoutubeDLRequest(job.url).apply {
            addOption("--no-playlist")
            // audio-only stream when the site has one (YouTube), then just copy the audio out: no slow mp3 re-encode
            addOption("-f", "bestaudio[ext=m4a]/bestaudio/best")
            addOption("-x"); addOption("--audio-format", "best")
            addOption("-N", "4")
            addOption("--write-thumbnail"); addOption("--convert-thumbnails", "jpg")
            addOption("--write-info-json")
            addOption("--no-mtime")
            addOption("-o", work.absolutePath + "/media.%(ext)s")
        }
        update(job.id) { it.copy(message = "Downloading…") }
        YoutubeDL.getInstance().execute(req, job.id) { progress, _, line ->
            update(job.id) { j ->
                when {
                    progress in 0f..99.9f -> j.copy(progress = progress / 100f, message = "Downloading ${progress.toInt()}%")
                    "ExtractAudio" in line -> j.copy(progress = 1f, message = "Extracting audio…")
                    "Thumbnail" in line -> j.copy(message = "Getting cover…")
                    "Extracting URL" in line || "Downloading webpage" in line || "player" in line -> j.copy(message = "Reading video info…")
                    else -> j
                }
            }
        }
        update(job.id) { it.copy(message = "Finishing…") }

        val files = work.listFiles()?.toList() ?: emptyList()
        val audio = files.firstOrNull { it.name == "media.mp3" }
            ?: files.firstOrNull { it.extension.lowercase() in setOf("mp3", "m4a", "opus", "ogg", "wav", "aac", "flac") }
            ?: error("No audio was produced")
        val thumb = files.firstOrNull { it.name == "media.jpg" }
            ?: files.firstOrNull { it.extension.lowercase() in setOf("jpg", "jpeg", "png", "webp") }
        val info = files.firstOrNull { it.name.endsWith(".info.json") }?.let { runCatching { JSONObject(it.readText()) }.getOrNull() }
        files.filter { it.name.endsWith(".info.json") }.forEach { it.delete() }

        val extractor = (info?.optString("extractor_key").orEmpty() + " " + info?.optString("extractor").orEmpty()).lowercase()
        val source = when {
            "youtube" in extractor -> Sources.YOUTUBE
            "tiktok" in extractor -> Sources.TIKTOK
            else -> Sources.VIDEO
        }
        val rawTitle = info?.optString("title").orEmpty().ifBlank { info?.optString("fulltitle").orEmpty() }
            .ifBlank { job.url.substringAfterLast('/').substringBefore('?').substringBeforeLast('.').ifBlank { "Untitled" } }
        val artist = info?.optString("uploader").orEmpty().ifBlank { info?.optString("channel").orEmpty() }
            .ifBlank { info?.optString("creator").orEmpty() }.ifBlank { "Unknown artist" }
        var durMs = (info?.optDouble("duration", 0.0) ?: 0.0).times(1000).toLong()
        if (durMs <= 0) {
            val r = MediaMetadataRetriever()
            durMs = try { r.setDataSource(audio.absolutePath); r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L } catch (_: Throwable) { 0L } finally { runCatching { r.release() } }
        }
        return Song(
            id = songId, uri = Uri.fromFile(audio).toString(), title = "[$source] $rawTitle", artist = artist, album = "",
            durationMs = durMs, dateAdded = System.currentTimeMillis(), source = source,
            path = audio.absolutePath, thumbPath = thumb?.absolutePath,
        )
    }

    fun deleteDownload(song: Song) {
        scope.launch(Dispatchers.IO) {
            song.path?.let { File(it).parentFile?.takeIf { d -> d.parentFile == root }?.deleteRecursively() }
            withContext(Dispatchers.Main) { store.removeImported(setOf(song.id)); library.refresh() }
        }
    }

    // ---------------------------------------------------------------- whole playlists
    private class Group(val parent: String, val playlistId: String, val total: Int, var done: Int = 0, var failed: Int = 0)
    private val groups = HashMap<String, Group>()          // child job id -> its group

    /** a link that points at a (non-"mix") YouTube playlist -> canonical playlist URL, else null */
    private fun playlistUrl(url: String): String? {
        if (!url.contains("youtube.com") && !url.contains("youtu.be")) return null
        val id = Regex("[?&]list=([A-Za-z0-9_-]+)").find(url)?.groupValues?.get(1) ?: return null
        if (id.startsWith("RD") || id.startsWith("UL")) return null      // auto-generated mixes are endless
        return "https://www.youtube.com/playlist?list=$id"
    }

    private fun startPlaylist(url: String) {
        val parent = Job(UUID.randomUUID().toString(), url, title = "YouTube playlist", state = State.WORKING, message = "Reading the playlist…")
        jobs = jobs + parent
        runCatching { ctx.startForegroundService(Intent(ctx, DownloadService::class.java)) }
        scope.launch(Dispatchers.IO) {
            try {
                ensureEngine()
                val req = YoutubeDLRequest(url).apply { addOption("--flat-playlist"); addOption("-J"); addOption("--no-warnings") }
                val json = JSONObject(YoutubeDL.getInstance().execute(req).out)
                val title = json.optString("title").ifBlank { "YouTube playlist" }
                val arr = json.optJSONArray("entries")
                val urls = ArrayList<String>()
                if (arr != null) for (i in 0 until arr.length()) {
                    val e = arr.optJSONObject(i) ?: continue
                    val u = e.optString("url").takeIf { it.startsWith("http") } ?: e.optString("id").takeIf { it.isNotBlank() }?.let { "https://www.youtube.com/watch?v=$it" } ?: continue
                    urls += u
                }
                if (urls.isEmpty()) { update(parent.id) { it.copy(state = State.FAILED, message = "No videos found in that playlist (private or empty?)") }; return@launch }
                val pl = withContext(Dispatchers.Main) { store.createPlaylist(title) }
                val g0 = Group(parent.id, pl.id, urls.size)
                update(parent.id) { it.copy(title = "Playlist: $title", message = "0 / ${urls.size} downloaded") }
                urls.forEach { u ->
                    val child = Job(UUID.randomUUID().toString(), u, hidden = true)
                    synchronized(groups) { groups[child.id] = g0 }
                    jobs = jobs + child
                    scope.launch(Dispatchers.IO) { lock.withLock { run(child.id) } }
                }
            } catch (e: CancellationException) { throw e } catch (e: Throwable) {
                update(parent.id) { it.copy(state = State.FAILED, message = friendly(e)) }
            }
        }
    }

    private fun childFinished(childId: String, songId: String, ok: Boolean) {
        val g = synchronized(groups) { groups[childId] } ?: return
        scope.launch(Dispatchers.Main) {
            if (ok && songId.isNotEmpty()) store.addToPlaylist(g.playlistId, listOf(songId))
            if (ok) g.done++ else g.failed++
            val finished = g.done + g.failed >= g.total
            update(g.parent) {
                it.copy(progress = (g.done + g.failed).toFloat() / g.total,
                    message = "${g.done} / ${g.total} downloaded" + (if (g.failed > 0) " · ${g.failed} failed" else ""),
                    state = if (finished) (if (g.done > 0) State.DONE else State.FAILED) else State.WORKING)
            }
            synchronized(this@Downloader) { jobs = jobs.filter { !(it.hidden && it.id == childId) } }
            if (finished) autoClear(g.parent)
        }
    }

    private fun autoClear(id: String) { scope.launch { delay(3500); dismiss(id) } }

    private fun ensureEngine() {
        if (engineReady) return
        YoutubeDL.getInstance().init(ctx)
        FFmpeg.getInstance().init(ctx)
        engineReady = true
    }

    /** Settings -> "Update downloader": pulls the newest yt-dlp (needed now and then when YouTube / TikTok change). */
    fun updateEngine() {
        engineMessage = "Updating…"
        scope.launch(Dispatchers.IO) {
            val msg = try {
                ensureEngine()
                when (YoutubeDL.getInstance().updateYoutubeDL(ctx, YoutubeDL.UpdateChannel._STABLE)) {
                    YoutubeDL.UpdateStatus.DONE -> "Updated to the newest version"
                    YoutubeDL.UpdateStatus.ALREADY_UP_TO_DATE -> "Already up to date"
                    else -> "Done"
                } + " (" + (YoutubeDL.getInstance().versionName(ctx) ?: "?") + ")"
            } catch (e: Throwable) { "Update failed: " + friendly(e) }
            withContext(Dispatchers.Main) { engineMessage = msg }
        }
    }

    private fun friendly(e: Throwable): String {
        val m = (e.message ?: e.toString())
        return when {
            "Unsupported URL" in m -> "This link isn't supported"
            "Sign in" in m || "confirm you" in m -> "YouTube asked for sign-in for this video. Try another video or update the downloader"
            "429" in m -> "Too many requests. Try again in a bit"
            "Private" in m || "private" in m -> "This video is private"
            "Unable to resolve host" in m || "Network is unreachable" in m || "timed out" in m -> "No internet connection"
            else -> m.lineSequence().firstOrNull { it.contains("ERROR") }?.take(160) ?: m.take(160)
        }
    }
}
