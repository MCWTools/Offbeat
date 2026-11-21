package com.auralis.player.playback

import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.media.audiofx.BassBoost
import android.media.audiofx.Equalizer
import android.os.SystemClock
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import com.auralis.player.data.ArtLoader
import com.auralis.player.data.Library
import com.auralis.player.data.Song
import com.auralis.player.data.Store
import com.auralis.player.widget.PlayerWidget
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.max
import kotlin.math.min

enum class RepeatKind { OFF, PLAYLIST, ONE }

@UnstableApi
class PlayerManager(
    private val ctx: Context,
    private val store: Store,
    private val library: Library,
    private val scope: CoroutineScope,
) {
    val player: ExoPlayer
    private val songsById = HashMap<String, Song>()
    private val prefs = store.prefs

    // ---- observable state for the UI
    var current by mutableStateOf<Song?>(null); private set
    var isPlaying by mutableStateOf(false); private set
    var positionMs by mutableLongStateOf(0L); private set
    var durationMs by mutableLongStateOf(0L); private set
    var queue by mutableStateOf<List<Song>>(emptyList()); private set
    var queueIndex by mutableIntStateOf(-1); private set
    var repeat by mutableStateOf(RepeatKind.OFF); private set
    var shuffle by mutableStateOf(prefs.getBoolean("shuffle", false)); private set
    var sourcePlaylistId by mutableStateOf<String?>(null); private set

    var volume by mutableFloatStateOf(prefs.getFloat("volume", 1f)); private set
    var muted by mutableStateOf(false); private set

    var speed by mutableFloatStateOf(prefs.getFloat("speed", 1f)); private set
    var pitchLinked by mutableStateOf(prefs.getBoolean("pitchLinked", false)); private set

    // sleep timer
    var sleepActive by mutableStateOf(false); private set
    var sleepRemainingMs by mutableLongStateOf(0L); private set
    var stopAfterSong by mutableStateOf(false)
    var sleepFadeOut by mutableStateOf(prefs.getBoolean("sleepFade", true))
    private var sleepJob: Job? = null
    private var sleepFade = 1f

    // equalizer
    var eqAvailable by mutableStateOf(false); private set
    var eqEnabled by mutableStateOf(prefs.getBoolean("eqOn", false)); private set
    var bandFreqs by mutableStateOf<List<Int>>(emptyList()); private set
    val bandLevels = mutableStateListOf<Int>()
    var levelMin by mutableIntStateOf(-1500); private set
    var levelMax by mutableIntStateOf(1500); private set
    var presets by mutableStateOf<List<String>>(emptyList()); private set
    var bassStrength by mutableIntStateOf(prefs.getInt("bass", 0)); private set
    var bassAvailable by mutableStateOf(false); private set
    private var eq: Equalizer? = null
    private var bass: BassBoost? = null

    private var originalQueue: List<MediaItem>? = null
    private var tickJob: Job? = null
    private var artAppliedFor: String? = null
    private var counted = false
    private var listenedMs = 0L
    private var fadeInArmed = false
    private var fadeFactor = 1f
    private var restored = false

    private val level = LevelProcessor()
    fun spectrum(): FloatArray = level.spectrum
    fun moveQueueItem(from: Int, to: Int) { if (from in 0 until player.mediaItemCount && to in 0 until player.mediaItemCount && from != to) { player.moveMediaItem(from, to); originalQueue = null } }
    private var profile: Loudness.Profile? = null
    private var profileId: String? = null
    private var profJob: Job? = null
    private var segN = 0
    // ---- true crossfade: the ending song keeps playing on a short-lived second player while the next one starts on the main player
    private var tailPrep: ExoPlayer? = null
    private var tailPrepId: String? = null
    private var tail: ExoPlayer? = null
    private var tailJob: Job? = null
    private val tailLevel = LevelProcessor()
    private var handoffKey: String? = null
    private var handoffInProgress = false
    private var segCorr: FloatArray? = null

    init {
        val attrs = AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build()
        val rf = object : androidx.media3.exoplayer.DefaultRenderersFactory(ctx) {
            override fun buildAudioSink(context: Context, enableFloatOutput: Boolean, enableAudioTrackPlaybackParams: Boolean): androidx.media3.exoplayer.audio.AudioSink =
                androidx.media3.exoplayer.audio.DefaultAudioSink.Builder(context)
                    .setEnableFloatOutput(enableFloatOutput)
                    .setEnableAudioTrackPlaybackParams(enableAudioTrackPlaybackParams)
                    .setAudioProcessors(arrayOf(level))
                    .build()
        }
        player = ExoPlayer.Builder(ctx, rf)
            .setAudioAttributes(attrs, true)          // pauses for calls / other apps
            .setHandleAudioBecomingNoisy(true)        // pauses when headphones are unplugged / Bluetooth disconnects
            .setWakeMode(C.WAKE_MODE_LOCAL)
            .build()
        val sid = (ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager).generateAudioSessionId()
        player.audioSessionId = sid
        setupEffects(sid)
        applySpeed()
        applyVolume()
        shuffle = prefs.getBoolean("shuffle", false)
        player.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                this@PlayerManager.isPlaying = isPlaying
                if (isPlaying) startTicker() else persistPosition()
                PlayerWidget.refresh(ctx)
            }
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                syncCurrent()
                ensureProfile()
                counted = false; listenedMs = 0
                val auto = reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO || reason == Player.MEDIA_ITEM_TRANSITION_REASON_REPEAT
                fadeInArmed = (auto || handoffInProgress) && store.crossfadeSec > 0
                if (!handoffInProgress) releaseTail()
                handoffInProgress = false
                if (auto && stopAfterSong) { stopAfterSong = false; player.pause() }
                persistQueue()
                PlayerWidget.refresh(ctx)
            }
            override fun onTimelineChanged(timeline: androidx.media3.common.Timeline, reason: Int) {
                refreshQueue(); syncCurrent()
            }
            override fun onPlaybackStateChanged(state: Int) { syncCurrent() }
            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) { if (!playWhenReady) releaseTail() }
        })
    }

    // ------------------------------------------------------------------ queue / playback
    private fun toItem(s: Song, art: ByteArray? = null): MediaItem {
        val md = MediaMetadata.Builder().setTitle(s.title).setArtist(s.artist).setAlbumTitle(s.album)
        if (art != null) md.setArtworkData(art, MediaMetadata.PICTURE_TYPE_FRONT_COVER)
        return MediaItem.Builder().setMediaId(s.id).setUri(s.uri).setMediaMetadata(md.build()).build()
    }

    fun songFor(id: String): Song? = songsById[id] ?: library.byId[id]

    fun playQueue(songs: List<Song>, index: Int, playlistId: String?, play: Boolean = true) {
        if (songs.isEmpty()) return
        songs.forEach { songsById[it.id] = it }
        sourcePlaylistId = playlistId
        if (playlistId == null && repeat == RepeatKind.PLAYLIST) repeat = RepeatKind.OFF
        applyRepeat()
        originalQueue = null
        artAppliedFor = null
        player.setMediaItems(songs.map { toItem(it) }, index.coerceIn(0, songs.lastIndex), 0L)
        player.prepare()
        player.playWhenReady = play
        if (shuffle && songs.size > 1) shuffleRest()
        ensureService()
    }

    fun playSongNow(song: Song, list: List<Song>, playlistId: String? = null) {
        val idx = list.indexOfFirst { it.id == song.id }
        if (idx >= 0) {
            // tapping the song that is already playing from the same source just toggles
            if (current?.id == song.id && sourcePlaylistId == playlistId && player.mediaItemCount == list.size) { togglePlay(); return }
            playQueue(list, idx, playlistId)
        }
    }

    fun shufflePlay(songs: List<Song>, playlistId: String?) {
        if (songs.isEmpty()) return
        if (!shuffle) { shuffle = true; prefs.edit().putBoolean("shuffle", true).apply() }
        playQueue(songs.shuffled(), 0, playlistId)
    }

    fun playNext(song: Song) {
        songsById[song.id] = song
        if (player.mediaItemCount == 0) { playQueue(listOf(song), 0, null); return }
        player.addMediaItem(player.currentMediaItemIndex + 1, toItem(song))
        originalQueue = null
    }

    fun addToQueue(songs: List<Song>) {
        songs.forEach { songsById[it.id] = it }
        if (player.mediaItemCount == 0) { playQueue(songs, 0, null); return }
        player.addMediaItems(songs.map { toItem(it) })
        originalQueue = null
    }

    fun removeFromQueue(index: Int) { if (index in 0 until player.mediaItemCount && index != player.currentMediaItemIndex) { player.removeMediaItem(index); originalQueue = null } }

    fun moveToNext(index: Int) {
        val cur = player.currentMediaItemIndex
        if (index == cur || index !in 0 until player.mediaItemCount) return
        val target = if (index < cur) cur else cur + 1
        player.moveMediaItem(index, target)
        originalQueue = null
    }

    fun jumpTo(index: Int) { player.seekTo(index, 0L); player.play() }

    fun togglePlay() {
        if (player.mediaItemCount == 0) {
            scope.launch { restoreLast(); player.play() }
            return
        }
        if (player.isPlaying) player.pause() else {
            if (player.playbackState == Player.STATE_ENDED) player.seekTo(0, 0L)
            player.play(); ensureService()
        }
    }
    fun play() { if (!player.isPlaying) togglePlay() }
    fun pause() { player.pause() }

    fun next() { if (player.hasNextMediaItem()) { player.seekToNextMediaItem(); fadeInArmed = false } else if (player.repeatMode == Player.REPEAT_MODE_ALL) player.seekToNextMediaItem() }
    fun previous() {
        if (player.currentPosition > 3000 || !player.hasPreviousMediaItem()) player.seekTo(0) else player.seekToPreviousMediaItem()
    }
    fun seekTo(ms: Long) { player.seekTo(ms); positionMs = ms }

    fun cycleRepeat() {
        repeat = if (sourcePlaylistId != null) when (repeat) {
            RepeatKind.OFF -> RepeatKind.PLAYLIST; RepeatKind.PLAYLIST -> RepeatKind.ONE; RepeatKind.ONE -> RepeatKind.OFF
        } else if (repeat == RepeatKind.OFF) RepeatKind.ONE else RepeatKind.OFF
        applyRepeat()
        prefs.edit().putInt("repeat", repeat.ordinal).apply()
    }
    private fun applyRepeat() {
        player.repeatMode = when (repeat) {
            RepeatKind.OFF -> Player.REPEAT_MODE_OFF; RepeatKind.PLAYLIST -> Player.REPEAT_MODE_ALL; RepeatKind.ONE -> Player.REPEAT_MODE_ONE
        }
    }

    fun toggleShuffle() {
        shuffle = !shuffle
        prefs.edit().putBoolean("shuffle", shuffle).apply()
        if (player.mediaItemCount > 1) { if (shuffle) shuffleRest() else restoreOrder() }
    }

    /** Current song stays, everything else is reshuffled around it (no audible gap). */
    private fun shuffleRest() {
        val n = player.mediaItemCount; val idx = player.currentMediaItemIndex
        if (n < 2 || idx < 0) return
        val all = (0 until n).map { player.getMediaItemAt(it) }
        originalQueue = all
        val rest = all.filterIndexed { i, _ -> i != idx }.shuffled()
        if (idx + 1 < n) player.removeMediaItems(idx + 1, n)
        if (idx > 0) player.removeMediaItems(0, idx)
        player.addMediaItems(rest)
    }
    private fun restoreOrder() {
        val orig = originalQueue ?: return
        originalQueue = null
        val curId = player.currentMediaItem?.mediaId ?: return
        val ci = orig.indexOfFirst { it.mediaId == curId }
        if (ci < 0) return
        val n = player.mediaItemCount; val idx = player.currentMediaItemIndex
        if (idx + 1 < n) player.removeMediaItems(idx + 1, n)
        if (idx > 0) player.removeMediaItems(0, idx)
        player.addMediaItems(0, orig.subList(0, ci))
        player.addMediaItems(orig.subList(ci + 1, orig.size))
    }

    // ------------------------------------------------------------------ state sync
    private fun refreshQueue() {
        queue = List(player.mediaItemCount) { i -> songFor(player.getMediaItemAt(i).mediaId) }.filterNotNull()
    }

    private fun syncCurrent() {
        val item = player.currentMediaItem
        val song = item?.let { songFor(it.mediaId) }
        if (song?.id != current?.id) {
            current = song
            if (song != null && artAppliedFor != song.id) applyNotificationArt(song)
        }
        queueIndex = player.currentMediaItemIndex
        val d = player.duration
        durationMs = if (d != C.TIME_UNSET && d > 0) d else song?.durationMs ?: 0L
        positionMs = player.currentPosition
    }

    /** Puts the cover into the MediaItem so the notification / lock-screen show it. Done in-place (no re-buffer). */
    private fun applyNotificationArt(song: Song) {
        artAppliedFor = song.id
        scope.launch {
            val bytes = ArtLoader.notificationBytes(ctx, song) ?: return@launch
            val idx = player.currentMediaItemIndex
            if (idx < 0 || player.currentMediaItem?.mediaId != song.id) return@launch
            player.replaceMediaItem(idx, toItem(song, bytes))
            PlayerWidget.refresh(ctx)
        }
    }

    private fun ensureService() {
        runCatching { ctx.startService(Intent(ctx, MusicService::class.java)) }
    }

    // ------------------------------------------------------------------ ticker (position, listen counter, crossfade, autosave)
    private fun startTicker() {
        if (tickJob?.isActive == true) return
        tickJob = scope.launch {
            var last = SystemClock.elapsedRealtime(); var saveAcc = 0L
            while (isActive) {
                delay(120)
                val now = SystemClock.elapsedRealtime(); val dt = now - last; last = now
                positionMs = player.currentPosition
                val d = player.duration
                if (d != C.TIME_UNSET && d > 0) durationMs = d
                if (player.isPlaying) {
                    listenedMs += dt
                    val cur = current
                    // a play counts only for real songs (>30 s) after half of them was actually listened to
                    if (!counted && cur != null && durationMs > com.auralis.player.data.Auto.MIN_LEN_MS && listenedMs >= durationMs / 2) {
                        counted = true; store.incPlay(cur.id)
                        scope.launch(kotlinx.coroutines.Dispatchers.IO) { com.auralis.player.data.History.log(cur) }
                    }
                    saveAcc += dt
                    if (saveAcc > 5000) { saveAcc = 0; persistPosition() }
                }
                updateCrossfade()
                ensureProfile(); updateLevel()
                if (!player.isPlaying && !player.playWhenReady) break
            }
        }
    }

    private fun updateCrossfade() {
        val cf = store.crossfadeSec * 1000L
        var f = 1f
        if (cf > 0 && durationMs > cf * 3) {
            val pos = positionMs
            val hasNext = player.hasNextMediaItem() || player.repeatMode == Player.REPEAT_MODE_ALL
            if (player.repeatMode != Player.REPEAT_MODE_ONE && hasNext && player.isPlaying && !stopAfterSong) {
                val rem = durationMs - pos
                val key = "${player.currentMediaItemIndex}:${player.currentMediaItem?.mediaId}"
                if (handoffKey != key) {
                    if (rem <= cf + 2500 && tailPrep == null) prepareTail()
                    if (rem <= cf) handoff(cf, key)
                }
            }
            if (fadeInArmed) { if (pos < cf) f = min(f, (pos.toFloat() / cf).coerceIn(0f, 1f)) else fadeInArmed = false }
        }
        if (f != fadeFactor) { fadeFactor = f; applyVolume() }
    }

    private fun newSecondPlayer(lv: LevelProcessor): ExoPlayer {
        val rfT = object : androidx.media3.exoplayer.DefaultRenderersFactory(ctx) {
            override fun buildAudioSink(context: Context, enableFloatOutput: Boolean, enableAudioTrackPlaybackParams: Boolean): androidx.media3.exoplayer.audio.AudioSink =
                androidx.media3.exoplayer.audio.DefaultAudioSink.Builder(context)
                    .setEnableFloatOutput(enableFloatOutput)
                    .setEnableAudioTrackPlaybackParams(enableAudioTrackPlaybackParams)
                    .setAudioProcessors(arrayOf(lv)).build()
        }
        val t = ExoPlayer.Builder(ctx, rfT).build()
        t.audioSessionId = player.audioSessionId        // same session: the equalizer / bass boost also colour the fading song
        t.playbackParameters = player.playbackParameters
        return t
    }

    private fun prepareTail() {
        val item = player.currentMediaItem ?: return
        releaseTail()
        runCatching {
            val t = newSecondPlayer(tailLevel)
            t.setMediaItem(item); t.volume = 0f; t.playWhenReady = false; t.prepare()
            tailPrep = t; tailPrepId = item.mediaId
        }
    }

    private fun handoff(cf: Long, key: String) {
        handoffKey = key
        val t = tailPrep
        if (t == null || tailPrepId != player.currentMediaItem?.mediaId) { releaseTail(); return }   // nothing ready: plain gapless change
        tailPrep = null
        val base = if (muted) 0f else ((if (store.useDb) 1f else volume) * sleepFade).coerceIn(0f, 1f)
        tailLevel.targetGain = level.targetGain
        t.seekTo(player.currentPosition); t.volume = base; t.playWhenReady = true
        tail = t
        handoffInProgress = true
        fadeInArmed = true
        player.seekToNextMediaItem()
        tailJob?.cancel()
        tailJob = scope.launch {
            val t0 = SystemClock.elapsedRealtime()
            while (isActive) {
                val fr = 1f - (SystemClock.elapsedRealtime() - t0).toFloat() / cf
                if (fr <= 0f) break
                runCatching { t.volume = base * fr }
                delay(30)
            }
            releaseTail()
        }
    }

    private fun releaseTail() {
        tailJob?.cancel(); tailJob = null
        runCatching { tail?.release() }; tail = null
        runCatching { tailPrep?.release() }; tailPrep = null; tailPrepId = null
    }

    // ------------------------------------------------------------------ volume / speed
    fun changeVolume(v: Float) { volume = v.coerceIn(0f, 1f); if (volume > 0f) muted = false; prefs.edit().putFloat("volume", volume).apply(); applyVolume() }
    fun toggleMute() { muted = !muted; applyVolume() }
    private fun applyVolume() {
        player.volume = if (muted) 0f else ((if (store.useDb) 1f else volume) * fadeFactor * sleepFade).coerceIn(0f, 1f)
        updateLevel()
    }

    // ---- decibel mode: slider = target loudness; every song is levelled to it (optionally per segment)
    fun changeVolumeDb(db: Float) { store.volumeDb = db.coerceIn(-40f, -6f); store.saveVol(); muted = false; applyVolume() }
    /** call after toggling useDb / dynVol / segment count */
    fun levelSettingsChanged() { segCorr = null; store.saveVol(); ensureProfile(); applyVolume() }

    private fun ensureProfile() {
        val s = current ?: return
        if (!store.useDb) return
        if (profileId == s.id) return
        profileId = s.id; profile = null; segCorr = null
        profJob?.cancel()
        profJob = scope.launch {
            val p = Loudness.get(ctx, s)
            if (profileId == s.id) { profile = p; segCorr = null; updateLevel() }
            val ni = player.nextMediaItemIndex
            if (ni != C.INDEX_UNSET) songFor(player.getMediaItemAt(ni).mediaId)?.let { Loudness.get(ctx, it) }
        }
    }

    private fun updateLevel() {
        if (!store.useDb) { level.targetGain = 1f; return }
        val pr = profile
        var g = store.volumeDb - (pr?.songDb ?: -16f)
        if (store.dynVol && pr != null && pr.db.isNotEmpty()) {
            val n = min(store.dynSegs.toInt(), pr.db.size).coerceAtLeast(1)
            if (segCorr == null || segN != n) { segCorr = pr.corrections(n); segN = n }
            val c = segCorr!!
            val dur = max(1L, if (durationMs > 0) durationMs else player.duration.coerceAtLeast(1L))
            val f = (player.currentPosition.toFloat() / dur * n - 0.5f).coerceIn(0f, (n - 1).toFloat())
            val i0 = f.toInt(); val i1 = min(i0 + 1, n - 1)
            val tt = f - i0; val sm = tt * tt * (3f - 2f * tt)
            g += c[i0] * (1f - sm) + c[i1] * sm
        }
        level.targetGain = Math.pow(10.0, g.coerceIn(-45f, 15f) / 20.0).toFloat()
    }

    fun changeSpeed(s: Float) { speed = s.coerceIn(0.5f, 2f); prefs.edit().putFloat("speed", speed).apply(); applySpeed() }
    fun changePitchLinked(v: Boolean) { pitchLinked = v; prefs.edit().putBoolean("pitchLinked", v).apply(); applySpeed() }
    private fun applySpeed() { player.playbackParameters = PlaybackParameters(speed, if (pitchLinked) speed else 1f) }

    // ------------------------------------------------------------------ sleep timer (keeps counting across track changes)
    fun startSleep(totalMs: Long) {
        if (totalMs <= 0) return
        sleepJob?.cancel()
        sleepActive = true
        val end = SystemClock.elapsedRealtime() + totalMs
        sleepJob = scope.launch {
            while (isActive) {
                val rem = end - SystemClock.elapsedRealtime()
                sleepRemainingMs = max(rem, 0L)
                if (rem <= 0) break
                sleepFade = if (sleepFadeOut && rem < 6000) (rem / 6000f) else 1f
                applyVolume()
                delay(200)
            }
            player.pause()
            sleepFade = 1f; applyVolume()
            sleepActive = false; sleepRemainingMs = 0
        }
    }
    fun cancelSleep() {
        sleepJob?.cancel(); sleepActive = false; sleepRemainingMs = 0; sleepFade = 1f; applyVolume()
    }
    fun setSleepFade(v: Boolean) { sleepFadeOut = v; prefs.edit().putBoolean("sleepFade", v).apply() }

    // ------------------------------------------------------------------ equalizer / bass
    private fun setupEffects(sid: Int) {
        try {
            val e = Equalizer(0, sid)
            eq = e
            val n = e.numberOfBands.toInt()
            bandFreqs = List(n) { e.getCenterFreq(it.toShort()) / 1000 }
            levelMin = e.bandLevelRange[0].toInt(); levelMax = e.bandLevelRange[1].toInt()
            presets = List(e.numberOfPresets.toInt()) { e.getPresetName(it.toShort()) }
            val saved = prefs.getString("eqLevels", null)?.split(",")?.mapNotNull { it.toIntOrNull() }
            bandLevels.clear()
            for (i in 0 until n) {
                val lv = saved?.getOrNull(i) ?: 0
                bandLevels.add(lv)
                e.setBandLevel(i.toShort(), lv.coerceIn(levelMin, levelMax).toShort())
            }
            e.enabled = eqEnabled
            eqAvailable = true
        } catch (_: Throwable) { eqAvailable = false }
        try {
            val b = BassBoost(0, sid)
            bass = b
            bassAvailable = b.strengthSupported
            if (bassAvailable) b.setStrength(bassStrength.toShort())
            b.enabled = bassStrength > 0
        } catch (_: Throwable) { bassAvailable = false }
    }
    fun changeEqEnabled(v: Boolean) { eqEnabled = v; runCatching { eq?.enabled = v }; prefs.edit().putBoolean("eqOn", v).apply() }
    fun setBand(i: Int, level: Int) {
        if (i !in bandLevels.indices) return
        bandLevels[i] = level
        runCatching { eq?.setBandLevel(i.toShort(), level.toShort()) }
        prefs.edit().putString("eqLevels", bandLevels.joinToString(",")).apply()
    }
    fun usePreset(p: Int) {
        val e = eq ?: return
        runCatching {
            e.usePreset(p.toShort())
            for (i in bandLevels.indices) bandLevels[i] = e.getBandLevel(i.toShort()).toInt()
            prefs.edit().putString("eqLevels", bandLevels.joinToString(",")).apply()
        }
        if (!eqEnabled) changeEqEnabled(true)
    }
    fun resetEq() { for (i in bandLevels.indices) setBand(i, 0) }
    fun setBass(strength: Int) {
        bassStrength = strength.coerceIn(0, 1000)
        runCatching { bass?.setStrength(bassStrength.toShort()); bass?.enabled = bassStrength > 0 }
        prefs.edit().putInt("bass", bassStrength).apply()
    }

    // ------------------------------------------------------------------ persistence of the last session
    private fun persistQueue() {
        if (player.mediaItemCount == 0) return
        val ids = (0 until player.mediaItemCount).joinToString("\n") { player.getMediaItemAt(it).mediaId }
        prefs.edit().putString("lastQueue", ids).putInt("lastIndex", player.currentMediaItemIndex)
            .putString("lastPlaylist", sourcePlaylistId).putInt("repeat", repeat.ordinal).apply()
    }
    private fun persistPosition() {
        if (player.mediaItemCount == 0) return
        prefs.edit().putLong("lastPos", player.currentPosition).putInt("lastIndex", player.currentMediaItemIndex).apply()
    }

    /** Re-load the queue from the previous session (paused, at the same second). */
    suspend fun restoreLast() {
        if (restored || player.mediaItemCount > 0) return
        restored = true
        if (!library.loaded) library.refresh()
        if (player.mediaItemCount > 0) return   // the user already started something while the library was loading: never overwrite it
        val ids = prefs.getString("lastQueue", null)?.split("\n")?.filter { it.isNotBlank() } ?: return
        val songs = ids.mapNotNull { library.byId[it] }
        if (songs.isEmpty()) return
        val wantedId = ids.getOrNull(prefs.getInt("lastIndex", 0))
        val idx = songs.indexOfFirst { it.id == wantedId }.let { if (it < 0) 0 else it }
        songs.forEach { songsById[it.id] = it }
        sourcePlaylistId = prefs.getString("lastPlaylist", null)
        repeat = RepeatKind.entries.getOrElse(prefs.getInt("repeat", 0)) { RepeatKind.OFF }
        if (sourcePlaylistId == null && repeat == RepeatKind.PLAYLIST) repeat = RepeatKind.OFF
        applyRepeat()
        player.setMediaItems(songs.map { toItem(it) }, idx, prefs.getLong("lastPos", 0L))
        player.prepare()
        player.playWhenReady = false
        syncCurrent()
        PlayerWidget.refresh(ctx)
    }

    fun release() { releaseTail(); player.release() }
}
