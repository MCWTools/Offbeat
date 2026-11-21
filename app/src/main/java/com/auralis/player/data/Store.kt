package com.auralis.player.data

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** Persistent app data (playlists, favourites, play counts, settings). SharedPreferences + JSON, backed by Compose state. */
class Store(context: Context) {
    val prefs = context.getSharedPreferences("auralis", Context.MODE_PRIVATE)

    var playlists by mutableStateOf(loadPlaylists()); private set
    var favorites by mutableStateOf(prefs.getStringSet("fav", emptySet())!!.toSet()); private set
    var playCounts by mutableStateOf(loadCounts()); private set
    var imported by mutableStateOf(loadImported()); private set

    var accent by mutableIntStateOf(prefs.getInt("accent", 0)); private set
    var themeMode by mutableIntStateOf(prefs.getInt("theme", 0)); private set
    var crossfadeSec by mutableIntStateOf(prefs.getInt("cf", 0)); private set
    var sortMode by mutableStateOf(SortMode.entries.getOrElse(prefs.getInt("sort", 0)) { SortMode.NAME }); private set
    var sortAsc by mutableStateOf(prefs.getBoolean("asc", true)); private set
    var onlyFav by mutableStateOf(prefs.getBoolean("onlyFav", false)); private set
    /** For Sort: Source. "" = all app downloads first (Youtube, TikTok, Video), otherwise that one source first. */
    var sourceFirst by mutableStateOf(prefs.getString("srcFirst", "") ?: ""); private set

    // ---- player background look (blur / colour adjustments / effect). Plain vars: set the value, then call saveBg().
    var bgBlur by mutableFloatStateOf(prefs.getFloat("bgBlur", 22f))
    var bgSat by mutableFloatStateOf(prefs.getFloat("bgSat", 1f))
    var bgBright by mutableFloatStateOf(prefs.getFloat("bgBright", 0f))
    var bgContrast by mutableFloatStateOf(prefs.getFloat("bgContrast", 1f))
    var bgDim by mutableFloatStateOf(prefs.getFloat("bgDim", 0.35f))
    /** 0 none, 1 rain, 2 snow, 3 stars, 4 fireflies, 5 bubbles */
    /** true = blurred cover, false = colour mix from the cover's palette */
    var bgBlurOn by mutableStateOf(prefs.getBoolean("bgBlurOn", true))
    var bgZoom by mutableFloatStateOf(prefs.getFloat("bgZoom", 1.1f))
    fun saveBg() {
        prefs.edit().putFloat("bgBlur", bgBlur).putFloat("bgSat", bgSat).putFloat("bgBright", bgBright)
            .putFloat("bgContrast", bgContrast).putFloat("bgDim", bgDim).putBoolean("bgBlurOn", bgBlurOn).putFloat("bgZoom", bgZoom).apply()
    }
    fun resetBg() { bgBlur = 22f; bgSat = 1f; bgBright = 0f; bgContrast = 1f; bgDim = 0.35f; bgBlurOn = true; bgZoom = 1.1f; saveBg() }

    var vizOn by mutableStateOf(prefs.getBoolean("vizOn", true))
    fun saveViz() { prefs.edit().putBoolean("vizOn", vizOn).apply() }

    // ---- volume in decibels / auto-level
    var useDb by mutableStateOf(prefs.getBoolean("useDb", false))
    var volumeDb by mutableFloatStateOf(prefs.getFloat("volumeDb", -18f))
    var dynVol by mutableStateOf(prefs.getBoolean("dynVol", false))
    var dynSegs by mutableFloatStateOf(prefs.getFloat("dynSegs", 24f))
    fun saveVol() { prefs.edit().putBoolean("useDb", useDb).putFloat("volumeDb", volumeDb).putBoolean("dynVol", dynVol).putFloat("dynSegs", dynSegs).apply() }

    // ---- settings
    fun changeAccent(i: Int) { accent = i; prefs.edit().putInt("accent", i).apply() }
    fun changeThemeMode(i: Int) { themeMode = i; prefs.edit().putInt("theme", i).apply() }
    fun setCrossfade(s: Int) { crossfadeSec = s; prefs.edit().putInt("cf", s).apply() }
    fun setSort(m: SortMode, asc: Boolean) {
        sortMode = m; sortAsc = asc
        prefs.edit().putInt("sort", m.ordinal).putBoolean("asc", asc).apply()
    }
    fun changeSourceFirst(v: String) { sourceFirst = v; prefs.edit().putString("srcFirst", v).apply() }
    fun changeOnlyFav(v: Boolean) { onlyFav = v; prefs.edit().putBoolean("onlyFav", v).apply() }

    // ---- favourites
    fun isFav(id: String) = id in favorites
    /** when each song was favourited (ms) - drives the "Last favourited" auto playlist */
    var favTimes by mutableStateOf(loadFavTimes()); private set
    private fun loadFavTimes(): Map<String, Long> = runCatching {
        val o = JSONObject(prefs.getString("favT", "{}")); o.keys().asSequence().associateWith { o.getLong(it) }
    }.getOrDefault(emptyMap())
    private fun saveFav() {
        prefs.edit().putStringSet("fav", favorites).putString("favT", JSONObject(favTimes as Map<*, *>).toString()).apply()
    }
    fun toggleFav(id: String) = setFav(listOf(id), id !in favorites)
    fun setFav(ids: Collection<String>, fav: Boolean) {
        val now = System.currentTimeMillis()
        if (fav) { favorites = favorites + ids; favTimes = favTimes + ids.mapIndexed { i, s -> s to now + i } }
        else { favorites = favorites - ids.toSet(); favTimes = favTimes - ids.toSet() }
        saveFav()
    }

    // ---- play counts
    fun incPlay(id: String) {
        playCounts = playCounts + (id to ((playCounts[id] ?: 0) + 1))
        val o = JSONObject(); playCounts.forEach { (k, v) -> o.put(k, v) }
        prefs.edit().putString("counts", o.toString()).apply()
    }

    // ---- playlists
    fun createPlaylist(name: String, songIds: List<String> = emptyList()): Playlist {
        val p = Playlist(UUID.randomUUID().toString(), name.trim().ifBlank { "Playlist" }, songIds.distinct())
        playlists = playlists + p; savePlaylists(); return p
    }
    fun renamePlaylist(id: String, name: String) {
        playlists = playlists.map { if (it.id == id) it.copy(name = name.trim().ifBlank { it.name }) else it }; savePlaylists()
    }
    fun deletePlaylist(id: String) { playlists = playlists.filter { it.id != id }; savePlaylists() }
    fun addToPlaylist(id: String, songIds: Collection<String>) {
        playlists = playlists.map { p ->
            if (p.id == id) p.copy(songIds = p.songIds + songIds.filter { it !in p.songIds }) else p
        }; savePlaylists()
    }
    fun removeFromPlaylist(id: String, songId: String) {
        playlists = playlists.map { p -> if (p.id == id) p.copy(songIds = p.songIds - songId) else p }; savePlaylists()
    }

    // ---- virtual folders (no nesting) + renamed titles
    var folders by mutableStateOf(loadFolders()); private set
    var titles by mutableStateOf(loadTitles()); private set
    fun createFolder(name: String, ids: Collection<String> = emptyList()): Folder {
        val f = Folder(UUID.randomUUID().toString(), name.trim().ifBlank { "Folder" }, emptyList())
        folders = folders + f; saveFolders()
        if (ids.isNotEmpty()) moveToFolder(f.id, ids)
        return f
    }
    fun renameFolder(id: String, name: String) { folders = folders.map { if (it.id == id) it.copy(name = name.trim().ifBlank { it.name }) else it }; saveFolders() }
    fun deleteFolder(id: String) { folders = folders.filter { it.id != id }; saveFolders() }
    /** target "root" = take out of any folder */
    fun moveToFolder(target: String, ids: Collection<String>) {
        val set = ids.toSet()
        folders = folders.map { f ->
            val kept = f.songIds.filter { it !in set }
            if (f.id == target) f.copy(songIds = kept + ids.filter { it in set }.distinct()) else f.copy(songIds = kept)
        }
        saveFolders()
    }
    fun renameSong(id: String, title: String) {
        val t = title.trim(); if (t.isEmpty()) return
        titles = titles + (id to t); prefs.edit().putString("titles", JSONObject(titles as Map<*, *>).toString()).apply()
    }
    /** a song file is gone for good: drop every trace of it */
    fun forgetSongs(ids: Set<String>) {
        if (ids.isEmpty()) return
        favorites = favorites - ids; favTimes = favTimes - ids; saveFav()
        playlists = playlists.map { it.copy(songIds = it.songIds.filter { s -> s !in ids }) }; savePlaylists()
        folders = folders.map { it.copy(songIds = it.songIds.filter { s -> s !in ids }) }; saveFolders()
        removeImported(ids)
    }
    private fun saveFolders() {
        val a = JSONArray()
        folders.forEach { f -> a.put(JSONObject().put("id", f.id).put("name", f.name).put("songs", JSONArray(f.songIds))) }
        prefs.edit().putString("folders", a.toString()).apply()
    }
    private fun loadFolders(): List<Folder> = runCatching {
        val a = JSONArray(prefs.getString("folders", "[]"))
        List(a.length()) { i -> val o = a.getJSONObject(i); val s = o.getJSONArray("songs"); Folder(o.getString("id"), o.getString("name"), List(s.length()) { s.getString(it) }) }
    }.getOrDefault(emptyList())
    private fun loadTitles(): Map<String, String> = runCatching {
        val o = JSONObject(prefs.getString("titles", "{}")); o.keys().asSequence().associateWith { o.getString(it) }
    }.getOrDefault(emptyMap())

    // ---- imported (SAF) songs / downloaded songs
    fun addImported(songs: List<Song>) {
        val known = imported.map { it.id }.toSet()
        imported = imported + songs.filter { it.id !in known }; saveImported()
    }
    fun removeImported(ids: Set<String>) {
        if (ids.isEmpty()) return
        imported = imported.filter { it.id !in ids }; saveImported()
    }

    private fun savePlaylists() {
        val a = JSONArray()
        playlists.forEach { p ->
            a.put(JSONObject().put("id", p.id).put("name", p.name).put("songs", JSONArray(p.songIds)))
        }
        prefs.edit().putString("playlists", a.toString()).apply()
    }
    private fun loadPlaylists(): List<Playlist> = runCatching {
        val a = JSONArray(prefs.getString("playlists", "[]"))
        List(a.length()) { i ->
            val o = a.getJSONObject(i); val s = o.getJSONArray("songs")
            Playlist(o.getString("id"), o.getString("name"), List(s.length()) { s.getString(it) })
        }
    }.getOrDefault(emptyList())

    private fun loadCounts(): Map<String, Int> = runCatching {
        val o = JSONObject(prefs.getString("counts", "{}")); o.keys().asSequence().associateWith { o.getInt(it) }
    }.getOrDefault(emptyMap())

    private fun saveImported() {
        val a = JSONArray()
        imported.forEach { s ->
            a.put(JSONObject().put("id", s.id).put("uri", s.uri).put("title", s.title).put("artist", s.artist)
                .put("album", s.album).put("dur", s.durationMs).put("date", s.dateAdded).put("source", s.source)
                .put("path", s.path ?: JSONObject.NULL).put("thumb", s.thumbPath ?: JSONObject.NULL))
        }
        prefs.edit().putString("imported", a.toString()).apply()
    }
    private fun loadImported(): List<Song> = runCatching {
        val a = JSONArray(prefs.getString("imported", "[]"))
        List(a.length()) { i ->
            val o = a.getJSONObject(i)
            Song(o.getString("id"), o.getString("uri"), o.getString("title"), o.getString("artist"), o.getString("album"),
                o.getLong("dur"), o.getLong("date"), o.optString("source", Sources.LOCAL),
                if (o.isNull("path")) null else o.getString("path"), if (o.isNull("thumb")) null else o.getString("thumb"))
        }
    }.getOrDefault(emptyList())
}
