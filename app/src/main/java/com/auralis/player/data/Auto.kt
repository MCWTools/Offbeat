package com.auralis.player.data

/** Auto-generated playlists. They are computed live from play counts / favourites, so they re-sort by themselves. */
object Auto {
    const val TOP = "auto:top"
    const val FAV = "auto:fav"
    const val MIN_LEN_MS = 30_000L          // shorter clips (sound effects...) never enter an auto playlist
    fun isAuto(id: String?) = id != null && id.startsWith("auto:")

    fun songs(id: String, store: Store, all: List<Song>): List<Song> = when (id) {
        TOP -> all.filter { it.durationMs > MIN_LEN_MS && (store.playCounts[it.id] ?: 0) > 0 }
            .sortedWith(compareByDescending<Song> { store.playCounts[it.id] ?: 0 }.thenBy { it.title.lowercase() }).take(10)
        FAV -> all.filter { it.durationMs > MIN_LEN_MS && it.id in store.favorites }
            .sortedByDescending { store.favTimes[it.id] ?: 0L }.take(10)
        else -> emptyList()
    }

    fun playlists(store: Store, all: List<Song>): List<Playlist> = listOf(
        Playlist(TOP, "Top 10 most played", songs(TOP, store, all).map { it.id }),
        Playlist(FAV, "Last 10 favorited", songs(FAV, store, all).map { it.id }),
    )
}
