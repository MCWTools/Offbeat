package com.auralis.player.data

data class Song(
    val id: String,
    val uri: String,
    val title: String,
    val artist: String,
    val album: String,
    val durationMs: Long,
    val dateAdded: Long,
    /** Local, YouTube, TikTok, Video ... (downloaded-from-link feature will set this) */
    val source: String = Sources.LOCAL,
    val path: String? = null,
    val thumbPath: String? = null,
)

data class Playlist(val id: String, val name: String, val songIds: List<String>)

/** Virtual folder shown in the song list. Never touches real files; a song lives in at most one folder; folders do not nest. */
data class Folder(val id: String, val name: String, val songIds: List<String>)

object Sources {
    const val LOCAL = "Local"
    const val YOUTUBE = "Youtube"
    const val TIKTOK = "TikTok"
    const val VIDEO = "Video"
}

enum class SortMode(val label: String, val defaultAsc: Boolean) {
    NAME("Name", true),
    DATE("Date", false),
    ARTIST("Artist", true),
    PLAYLIST("Playlist", true),
    LENGTH("Length", false),
    MOST("Most listened", false),
    SOURCE("Source", true),
}
