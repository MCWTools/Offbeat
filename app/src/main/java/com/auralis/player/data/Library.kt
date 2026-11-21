package com.auralis.player.data

import android.content.ContentUris
import android.content.Context
import android.database.ContentObserver
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.provider.OpenableColumns
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Music on the device: MediaStore scan + files imported through the picker (+ future downloaded files). */
class Library(private val ctx: Context, private val store: Store, private val scope: CoroutineScope) {
    var songs by mutableStateOf<List<Song>>(emptyList()); private set
    var loaded by mutableStateOf(false); private set
    @Volatile var byId: Map<String, Song> = emptyMap(); private set

    private val mutex = Mutex()
    private var debounce: Job? = null
    private var observing = false

    /** Re-scan. New files appear, deleted files vanish. */
    suspend fun refresh() = mutex.withLock {
        val result = withContext(Dispatchers.IO) {
            val local = runCatching { scanMediaStore() }.getOrDefault(emptyList())
            val dead = mutableSetOf<String>()
            val extra = store.imported.filter { s ->
                val ok = isReadable(s.uri)
                if (!ok) dead += s.id
                ok
            }
            Triple(local, extra, dead)
        }
        store.removeImported(result.third)
        val all = (result.first + result.second).distinctBy { it.id }
        raw = all
        applyTitles()
        loaded = true
    }

    private var raw: List<Song> = emptyList()
    /** call after a rename */
    fun applyTitles() {
        val t = store.titles
        val l = raw.map { s -> t[s.id]?.let { s.copy(title = it) } ?: s }
        byId = l.associateBy { it.id }
        songs = l
    }

    fun startObserving() {
        if (observing) return
        observing = true
        val obs = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                debounce?.cancel()
                debounce = scope.launch { delay(1200); refresh() }
            }
        }
        ctx.contentResolver.registerContentObserver(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, true, obs)
    }

    private fun isReadable(uri: String) = runCatching {
        ctx.contentResolver.openFileDescriptor(Uri.parse(uri), "r")?.use { true } ?: false
    }.getOrDefault(false)

    @Suppress("DEPRECATION")
    private fun scanMediaStore(): List<Song> {
        val out = ArrayList<Song>()
        val proj = arrayOf(
            MediaStore.Audio.Media._ID, MediaStore.Audio.Media.TITLE, MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.ALBUM, MediaStore.Audio.Media.DURATION, MediaStore.Audio.Media.DATE_ADDED,
            MediaStore.Audio.Media.DISPLAY_NAME, MediaStore.Audio.Media.DATA,
        )
        val sel = "${MediaStore.Audio.Media.DURATION} >= 5000 AND ${MediaStore.Audio.Media.IS_RINGTONE}=0 AND " +
            "${MediaStore.Audio.Media.IS_ALARM}=0 AND ${MediaStore.Audio.Media.IS_NOTIFICATION}=0"
        ctx.contentResolver.query(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, proj, sel, null, null)?.use { c ->
            val iId = c.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
            val iT = c.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
            val iA = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
            val iAl = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM)
            val iD = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
            val iDa = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DATE_ADDED)
            val iN = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DISPLAY_NAME)
            val iP = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DATA)
            while (c.moveToNext()) {
                val id = c.getLong(iId)
                val name = c.getString(iN) ?: ""
                var title = c.getString(iT)?.takeIf { it.isNotBlank() } ?: name.substringBeforeLast('.')
                if (title.isBlank()) title = "Unknown"
                val artist = c.getString(iA)?.takeIf { it.isNotBlank() && it != "<unknown>" } ?: "Unknown artist"
                val album = c.getString(iAl)?.takeIf { it.isNotBlank() && it != "<unknown>" } ?: ""
                out += Song(
                    id = "ms:$id",
                    uri = ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id).toString(),
                    title = title, artist = artist, album = album,
                    durationMs = c.getLong(iD), dateAdded = c.getLong(iDa) * 1000,
                    path = c.getString(iP),
                )
            }
        }
        return out
    }

    /** Import files chosen through the system picker (mp3 / ogg / wav / ...). */
    suspend fun importUris(uris: List<Uri>) {
        val made = withContext(Dispatchers.IO) {
            uris.mapNotNull { uri ->
                runCatching {
                    runCatching {
                        ctx.contentResolver.takePersistableUriPermission(uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    buildSong(uri)
                }.getOrNull()
            }
        }
        store.addImported(made)
        refresh()
    }

    private fun buildSong(uri: Uri): Song {
        var display = ""
        ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) display = it.getString(0) ?: ""
        }
        val r = MediaMetadataRetriever()
        var title = ""; var artist = ""; var album = ""; var dur = 0L
        try {
            r.setDataSource(ctx, uri)
            title = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE) ?: ""
            artist = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST) ?: ""
            album = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM) ?: ""
            dur = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
        } finally { runCatching { r.release() } }
        if (title.isBlank()) title = display.substringBeforeLast('.').ifBlank { "Unknown" }
        return Song(
            id = "im:" + uri.toString().hashCode().toUInt().toString(16), uri = uri.toString(),
            title = title, artist = artist.ifBlank { "Unknown artist" }, album = album,
            durationMs = dur, dateAdded = System.currentTimeMillis(),
        )
    }
}
