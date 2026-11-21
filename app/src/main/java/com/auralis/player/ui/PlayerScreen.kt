package com.auralis.player.ui

import android.graphics.Bitmap
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.displayCutoutPadding
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.material.icons.rounded.DragHandle
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.material.icons.rounded.DirectionsCar
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.Lyrics
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.PlaylistPlay
import androidx.compose.material.icons.rounded.QueueMusic
import androidx.compose.material.icons.rounded.Repeat
import androidx.compose.material.icons.rounded.RepeatOne
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material.icons.rounded.Timer
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material.icons.rounded.VolumeDown
import androidx.compose.material.icons.rounded.VolumeOff
import androidx.compose.material.icons.rounded.VolumeUp
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.palette.graphics.Palette
import com.auralis.player.Graph
import com.auralis.player.data.ArtLoader
import com.auralis.player.data.Song
import com.auralis.player.playback.RepeatKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.roundToInt

private val Ink = Color(0xFF0B0B10)

@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
fun PlayerScreen(onClose: () -> Unit) {
    val pm = Graph.player; val store = Graph.store
    val song = pm.current
    val ctx = LocalContext.current
    val accent = LocalAccent.current
    val scope = rememberCoroutineScope()
    var showLyrics by remember { mutableStateOf(false) }
    var showQueue by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }
    var dragTime by remember { mutableStateOf<Long?>(null) }
    var driving by remember { mutableStateOf(false) }
    var clean by remember { mutableStateOf(false) }   // hide the whole UI: only background + cover
    androidx.activity.compose.BackHandler(clean) { clean = false }

    // ---- background: only COLOURS are taken from the cover (the picture itself is never stretched, so no creepy blurred faces)
    val art by produceState<Bitmap?>(ArtLoader.peek(song, 720), song?.id) {
        value = if (song == null) null else ArtLoader.load(ctx, song, 720)
    }
    val palette by produceState(listOf(accent, accent, accent), art, accent, song?.id) {
        val bm = art
        // no cover -> use the very same colours as the generated placeholder icon
        val hue = ((song?.id?.hashCode() ?: 0).toUInt() % 360u).toFloat()
        value = if (bm == null) listOf(Color.hsv(hue, 0.55f, 0.62f), Color.hsv(hue, 0.45f, 0.50f), Color.hsv((hue + 45f) % 360f, 0.6f, 0.30f)) else withContext(Dispatchers.Default) {
            val p = Palette.from(bm).maximumColorCount(16).generate()
            val dom = p.getDominantColor(accent.toArgb())
            listOf(
                Color(p.getVibrantColor(dom)),
                Color(p.getMutedColor(dom)),
                Color(p.getDarkVibrantColor(p.getDarkMutedColor(dom))),
            )
        }
    }
    val c1 by animateColorAsState(palette[0], tween(900), label = "c1")
    val c2 by animateColorAsState(palette[1], tween(900), label = "c2")
    val c3 by animateColorAsState(palette[2], tween(900), label = "c3")
    val tint = c1

    Box(
        Modifier.fillMaxSize().background(Ink)
            // swallow every touch the player itself did not use, so nothing underneath can be tapped through it
            .pointerInput(Unit) { awaitPointerEventScope { while (true) awaitPointerEvent().changes.forEach { it.consume() } } }
            .then(
            if (clean) Modifier.clickable(indication = null, interactionSource = remember { MutableInteractionSource() }) { clean = false } else Modifier,
        ),
    ) {
        // Background = the real cover, full size, with a light GPU (shader) blur on top of it
        val blurPx = with(androidx.compose.ui.platform.LocalDensity.current) { if (store.bgBlurOn) store.bgBlur.dp.toPx() else 0f }
        val filter = remember(store.bgSat, store.bgBright, store.bgContrast) { bgColorFilter(store.bgSat, store.bgBright, store.bgContrast) }
        Crossfade(targetState = art, animationSpec = tween(700), label = "bg") { bm ->
            if (bm != null && store.bgBlurOn) {
                val zoom = store.bgZoom
                if (zoom < 1.15f) { // zoomed out: fill the edges with a heavily blurred copy
                    Image(
                        bm.asImageBitmap(), null,
                        Modifier.fillMaxSize().graphicsLayer {
                            if (android.os.Build.VERSION.SDK_INT >= 31) renderEffect = androidx.compose.ui.graphics.BlurEffect(60f, 60f, androidx.compose.ui.graphics.TileMode.Clamp)
                            scaleX = 1.3f; scaleY = 1.3f
                        },
                        contentScale = ContentScale.Crop, colorFilter = filter,
                    )
                }
                Image(
                    bm.asImageBitmap(), null,
                    Modifier.fillMaxSize().graphicsLayer {
                        if (android.os.Build.VERSION.SDK_INT >= 31 && blurPx > 0.5f) renderEffect = androidx.compose.ui.graphics.BlurEffect(blurPx, blurPx, androidx.compose.ui.graphics.TileMode.Clamp)
                        scaleX = zoom; scaleY = zoom
                    },
                    contentScale = ContentScale.Crop, colorFilter = filter,
                )
            } else if (bm != null) {
                // "Mix": soft blend of the cover's own colours
                Box(
                    Modifier.fillMaxSize().background(c3.copy(alpha = 1f)).drawBehind {
                        val big = maxOf(size.width, size.height)
                        drawRect(Brush.radialGradient(listOf(c1, Color.Transparent), Offset(size.width * 0.15f, size.height * 0.2f), big * 0.95f))
                        drawRect(Brush.radialGradient(listOf(c2, Color.Transparent), Offset(size.width * 0.9f, size.height * 0.75f), big * 0.9f))
                        drawRect(Brush.radialGradient(listOf(accent.copy(alpha = 0.35f), Color.Transparent), Offset(size.width * 0.5f, size.height * 1.0f), big * 0.7f))
                    },
                )
            } else {
                Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(accent.copy(alpha = 0.40f), Color(0xFF14141C), Ink))))
            }
        }
        val dim = store.bgDim
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = dim * 0.55f), Color.Black.copy(alpha = dim), Color.Black.copy(alpha = (dim + 0.3f).coerceAtMost(0.95f))))))

        val landscape = androidx.compose.ui.platform.LocalConfiguration.current.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().displayCutoutPadding().padding(horizontal = 22.dp)) {
            // ---- top bar
            val topBar: @Composable () -> Unit = {
            AnimatedVisibility(visible = !clean, enter = fadeIn(), exit = fadeOut()) {
            Row(Modifier.fillMaxWidth().height(56.dp), verticalAlignment = Alignment.CenterVertically) {
                IconBtn(Icons.Rounded.KeyboardArrowDown, "Close", Color.White, 30.dp) { onClose() }
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("PLAYING FROM", fontSize = 10.sp, letterSpacing = 1.4.sp, color = Color.White.copy(alpha = 0.6f))
                    Text(
                        (com.auralis.player.data.Auto.playlists(store, Graph.library.songs) + store.playlists).firstOrNull { it.id == pm.sourcePlaylistId }?.name ?: "Music library",
                        fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                }
                IconBtn(Icons.Rounded.DirectionsCar, "Driving mode", Color.White) { driving = true }
                IconBtn(Icons.Rounded.VisibilityOff, "Hide interface", Color.White) { clean = true }
                IconBtn(Icons.Rounded.MoreVert, "Settings", Color.White) { showSettings = true }
            }
            }
            }

            // ---- artwork / lyrics
            val artArea: @Composable (Modifier) -> Unit = { mod ->
            Box(mod.fillMaxWidth(), contentAlignment = Alignment.Center) {
                AnimatedContent(
                    targetState = showLyrics, label = "lyr",
                    transitionSpec = { fadeIn(tween(300)) togetherWith fadeOut(tween(200)) },
                ) { lyr ->
                    if (lyr && song != null && !clean) {
                        LyricsView(song, pm.positionMs, onSeek = { pm.seekTo(it) })
                    } else {
                        SwipeArt(song, tint, clean) { clean = false }
                    }
                }
            }
            }

            val controls: @Composable () -> Unit = {
            AnimatedVisibility(visible = !clean, enter = fadeIn(), exit = fadeOut()) { Column {
            // ---- title + favourite
            Row(Modifier.fillMaxWidth().padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    AnimatedContent(targetState = song, label = "title", transitionSpec = { fadeIn(tween(300)) togetherWith fadeOut(tween(150)) }) { s ->
                        Column {
                            Text(s?.title ?: "Nothing playing", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold, maxLines = 1, modifier = Modifier.basicMarquee())
                            Text(s?.artist ?: "", color = Color.White.copy(alpha = 0.7f), fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
                if (song != null) {
                    val fav = store.isFav(song.id)
                    IconBtn(if (fav) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder, "Favorite", if (fav) accent else Color.White, 26.dp) { store.toggleFav(song.id) }
                }
            }

            // ---- seek bar + times
            SeekBar(pm.positionMs, pm.durationMs, accent, onSeek = { pm.seekTo(it) }, onDragTime = { dragTime = it })
            Row(Modifier.fillMaxWidth().offset(y = (-8).dp), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(fmt(dragTime ?: pm.positionMs), color = if (dragTime != null) accent else Color.White.copy(alpha = 0.7f), fontSize = 12.sp, fontWeight = FontWeight.Medium)
                Text(fmt(pm.durationMs), color = Color.White.copy(alpha = 0.7f), fontSize = 12.sp)
            }

            // ---- transport
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                IconBtn(Icons.Rounded.Shuffle, "Shuffle", if (pm.shuffle) accent else Color.White.copy(alpha = 0.4f), 24.dp) { pm.toggleShuffle() }
                IconBtn(Icons.Rounded.SkipPrevious, "Previous", Color.White, 38.dp) { pm.previous() }
                PlayButton(pm.isPlaying, accent) { pm.togglePlay() }
                IconBtn(Icons.Rounded.SkipNext, "Next", Color.White, 38.dp) { pm.next() }
                RepeatButton(pm.repeat, accent) { pm.cycleRepeat() }
            }

            // ---- volume + quick buttons
            Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                IconBtn(
                    if (pm.muted || (!store.useDb && pm.volume == 0f)) Icons.Rounded.VolumeOff else if ((if (store.useDb) (store.volumeDb + 40f) / 34f else pm.volume) < 0.4f) Icons.Rounded.VolumeDown else Icons.Rounded.VolumeUp,
                    "Mute", if (pm.muted) accent else Color.White.copy(alpha = 0.8f), 22.dp,
                ) { pm.toggleMute() }
                Slider(
                    value = if (store.useDb) store.volumeDb else if (pm.muted) 0f else pm.volume,
                    onValueChange = { if (store.useDb) pm.changeVolumeDb(it) else pm.changeVolume(it) },
                    valueRange = if (store.useDb) -40f..-6f else 0f..1f,
                    modifier = Modifier.weight(1f),
                    colors = SliderDefaults.colors(thumbColor = Color.White, activeTrackColor = accent, inactiveTrackColor = Color.White.copy(alpha = 0.2f)),
                    thumb = { SliderDefaults.Thumb(remember { MutableInteractionSource() }, thumbSize = androidx.compose.ui.unit.DpSize(16.dp, 16.dp), colors = SliderDefaults.colors(thumbColor = Color.White)) },
                )
                if (store.useDb) Text("%.0f dB".format(store.volumeDb), fontSize = 11.sp, color = Color.White.copy(alpha = 0.75f), modifier = Modifier.width(40.dp), textAlign = TextAlign.End)
                GlowToggle(Icons.Rounded.Timer, pm.sleepActive, accent) { if (pm.sleepActive) pm.cancelSleep() else showSettings = true }
                GlowToggle(Icons.Rounded.Lyrics, showLyrics, accent) { showLyrics = !showLyrics }
                GlowToggle(Icons.Rounded.QueueMusic, false, accent) { showQueue = true }
            }
            AnimatedContent(pm.sleepActive, label = "sleepchip") { on ->
                if (on) Text(
                    "Sleep in ${fmt(pm.sleepRemainingMs)}", color = accent, fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
                    textAlign = TextAlign.End, modifier = Modifier.fillMaxWidth(),
                ) else Spacer(Modifier.height(4.dp))
            }
            Spacer(Modifier.height(6.dp))
            } }
            }

            topBar()
            if (landscape) {
                Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(32.dp), verticalAlignment = Alignment.CenterVertically) {
                    artArea(Modifier.weight(1f).fillMaxHeight())
                    Box(Modifier.weight(1.15f).fillMaxHeight(), contentAlignment = Alignment.Center) {
                        Column(Modifier.verticalScroll(rememberScrollState())) { controls() }
                    }
                }
            } else {
                artArea(Modifier.weight(1f))
                controls()
            }
        }
    }

    if (driving) DrivingMode { driving = false }
    if (showQueue) QueueSheet { showQueue = false }
    if (showSettings) PlayerSettingsSheet { showSettings = false }
}

@Composable
private fun SwipeArt(song: Song?, tint: Color, clean: Boolean, onReveal: () -> Unit) {
    val pm = Graph.player
    val scope = rememberCoroutineScope()
    val off = remember { Animatable(0f) }
    val store = Graph.store
    val act = androidx.compose.ui.platform.LocalContext.current as? android.app.Activity
    var hud by remember { mutableStateOf<String?>(null) }
    var hudKey by remember { mutableIntStateOf(0) }
    var bright by remember { mutableFloatStateOf(-1f) }
    androidx.compose.runtime.DisposableEffect(Unit) { onDispose { act?.window?.let { w -> w.attributes = w.attributes.also { it.screenBrightness = -1f } } } }
    LaunchedEffect(hudKey) { if (hud != null) { kotlinx.coroutines.delay(900); hud = null } }
    BoxWithConstraints(
        Modifier.fillMaxSize()
            .pointerInput(Unit) {   // ONE detector for everything: tap, double-tap seek, horizontal swipe = prev/next, vertical swipe = brightness / volume
                var lastTap = 0L
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val slop = viewConfiguration.touchSlop
                    val left = down.position.x < size.width / 2
                    var total = androidx.compose.ui.geometry.Offset.Zero
                    var axis = 0   // 0 undecided, 1 horizontal, 2 vertical
                    var moved = false
                    while (true) {
                        val ev = awaitPointerEvent()
                        val ch = ev.changes.firstOrNull { it.id == down.id } ?: break
                        if (!ch.pressed) {
                            if (axis == 1) scope.launch {
                                val dx = off.value
                                if (dx < -140f) pm.next() else if (dx > 140f && pm.player.hasPreviousMediaItem()) pm.player.seekToPreviousMediaItem()
                                off.animateTo(0f, spring(Spring.DampingRatioMediumBouncy, Spring.StiffnessMedium))
                            } else if (!moved) {
                                val now = System.currentTimeMillis()
                                if (clean) { onReveal(); lastTap = 0L }
                                else if (now - lastTap < 320) {
                                    val fwd = down.position.x > size.width / 2
                                    pm.seekTo((pm.positionMs + if (fwd) 10_000 else -10_000).coerceIn(0L, maxOf(0L, pm.durationMs)))
                                    hud = if (fwd) "+10 s" else "-10 s"; hudKey++; lastTap = 0L
                                } else lastTap = now
                            }
                            break
                        }
                        val d = ch.positionChange()
                        if (axis == 0) {
                            total += d
                            if (kotlin.math.abs(total.x) > slop || kotlin.math.abs(total.y) > slop) {
                                moved = true
                                axis = if (kotlin.math.abs(total.x) >= kotlin.math.abs(total.y)) 1 else 2
                                if (axis == 2 && bright < 0f) bright = (act?.window?.attributes?.screenBrightness ?: -1f).let { if (it < 0f) 0.5f else it }
                                ch.consume()
                                if (axis == 1) scope.launch { off.snapTo(total.x * 0.8f) }
                            }
                        } else {
                            ch.consume()
                            if (axis == 1) scope.launch { off.snapTo(off.value + d.x * 0.8f) }
                            else {
                                val dd = -d.y / size.height * 1.4f
                                if (left) {
                                    bright = (bright + dd).coerceIn(0.02f, 1f)
                                    act?.window?.let { w -> w.attributes = w.attributes.also { it.screenBrightness = bright } }
                                    hud = "Brightness ${(bright * 100).roundToInt()}%"
                                } else if (store.useDb) {
                                    pm.changeVolumeDb(store.volumeDb + dd * 34f); hud = "Volume %.0f dB".format(store.volumeDb)
                                } else {
                                    pm.changeVolume(pm.volume + dd); hud = "Volume ${(pm.volume * 100).roundToInt()}%"
                                }
                                hudKey++
                            }
                        }
                    }
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        val side = if (maxWidth < maxHeight) maxWidth else maxHeight
        val vizMargin = if (store.vizOn) 30.dp else 0.dp
        if (store.vizOn) CoverVisualizer(Modifier.size(side), (side - 8.dp - vizMargin * 2) / side)
        hud?.let { Text(it, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
            modifier = Modifier.align(Alignment.TopCenter).padding(top = 8.dp).clip(RoundedCornerShape(18.dp)).background(Color.Black.copy(alpha = 0.55f)).padding(horizontal = 14.dp, vertical = 6.dp)) }
        Box(
            Modifier.size(side - 8.dp - vizMargin * 2)
                .offset { IntOffset(off.value.roundToInt(), 0) }
                .shadow(28.dp, RoundedCornerShape(28.dp), ambientColor = tint, spotColor = tint)
        ) {
            AnimatedContent(
                targetState = song, label = "cover",
                transitionSpec = { (fadeIn(tween(350)) + scaleIn(tween(350), 0.92f)) togetherWith (fadeOut(tween(200)) + scaleOut(tween(200), 0.96f)) },
            ) { s -> ArtImage(s, Modifier.fillMaxSize(), 720, 28.dp, big = true) }
        }
    }
}

@Composable
private fun PlayButton(playing: Boolean, accent: Color, onClick: () -> Unit) {
    val content = if (accent.luminanceDark()) Color.White else Color(0xFF101014)
    Box(
        Modifier.size(78.dp)
            .shadow(22.dp, CircleShape, ambientColor = accent, spotColor = accent)
            .clip(CircleShape).background(accent).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Crossfade(playing, animationSpec = tween(180), label = "pp") { p ->
            Icon(if (p) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, if (p) "Pause" else "Play", tint = content, modifier = Modifier.size(44.dp))
        }
    }
}

private fun Color.luminanceDark(): Boolean = (0.299f * red + 0.587f * green + 0.114f * blue) < 0.55f

@Composable
private fun RepeatButton(mode: RepeatKind, accent: Color, onClick: () -> Unit) {
    val tint by animateColorAsState(if (mode == RepeatKind.OFF) Color.White.copy(alpha = 0.4f) else accent, tween(200), label = "rep")
    Box(contentAlignment = Alignment.Center) {
        IconBtn(if (mode == RepeatKind.ONE) Icons.Rounded.RepeatOne else Icons.Rounded.Repeat, "Repeat", tint, 24.dp, onClick)
        if (mode == RepeatKind.PLAYLIST) {
            // little "playlist" badge = repeat the whole playlist
            Box(
                Modifier.align(Alignment.BottomEnd).offset(x = (-4).dp, y = (-6).dp).size(16.dp).clip(CircleShape).background(accent),
                contentAlignment = Alignment.Center,
            ) { Icon(Icons.Rounded.PlaylistPlay, null, tint = if (accent.luminanceDark()) Color.White else Color.Black, modifier = Modifier.size(12.dp)) }
        }
    }
}

@Composable
fun GlowToggle(icon: androidx.compose.ui.graphics.vector.ImageVector, active: Boolean, accent: Color, onClick: () -> Unit) {
    val bg by animateColorAsState(if (active) accent.copy(alpha = 0.28f) else Color.Transparent, tween(250), label = "glowbg")
    Box(
        Modifier.size(44.dp).clip(CircleShape).background(bg).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Icon(icon, null, tint = if (active) accent else Color.White.copy(alpha = 0.8f), modifier = Modifier.size(24.dp)) }
}

// ====================================================================================== queue
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QueueSheet(onDismiss: () -> Unit) {
    val pm = Graph.player
    val state = rememberLazyListState()
    var dragIdx by remember { mutableIntStateOf(-1) }
    var dragDy by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(Unit) { if (pm.queueIndex > 0) state.scrollToItem((pm.queueIndex - 1).coerceAtLeast(0)) }
    // stable keys (song id + n-th occurrence) so a row keeps its identity while it is dragged to a new position
    val keys = remember(pm.queue) { val seen = HashMap<String, Int>(); pm.queue.map { s -> val n = (seen[s.id] ?: 0); seen[s.id] = n + 1; "${s.id}#$n" } }
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = MaterialTheme.colorScheme.surfaceContainerHigh) {
        Text("Queue · ${pm.queue.size} songs", Modifier.padding(horizontal = 20.dp, vertical = 6.dp), fontSize = 18.sp, fontWeight = FontWeight.Bold)
        Text("Drag the handle to reorder · swipe left to remove", Modifier.padding(horizontal = 20.dp), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        LazyColumn(Modifier.fillMaxWidth().height(480.dp), state = state) {
            itemsIndexed(pm.queue, key = { i, _ -> keys.getOrElse(i) { "x$i" } }) { i, s ->
                val cur = i == pm.queueIndex
                val dismiss = androidx.compose.material3.rememberSwipeToDismissBoxState(
                    confirmValueChange = { v -> if (v == androidx.compose.material3.SwipeToDismissBoxValue.EndToStart && !cur) { pm.removeFromQueue(i); true } else false },
                )
                androidx.compose.material3.SwipeToDismissBox(
                    state = dismiss, enableDismissFromStartToEnd = false, enableDismissFromEndToStart = !cur && dragIdx < 0,
                    modifier = Modifier.animateItem().graphicsLayer { translationY = if (i == dragIdx) dragDy else 0f; shadowElevation = if (i == dragIdx) 24f else 0f },
                    backgroundContent = { Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.errorContainer), contentAlignment = Alignment.CenterEnd) { Icon(Icons.Rounded.Close, null, Modifier.padding(end = 24.dp)) } },
                ) {
                    Box(Modifier.background(MaterialTheme.colorScheme.surfaceContainerHigh)) {
                        SongRow(
                            s, playing = cur, selected = false, selectionMode = false,
                            onClick = { pm.jumpTo(i) }, onLongClick = {},
                            trailing = {
                                if (!cur) IconBtn(Icons.Rounded.SkipNext, "Play next", MaterialTheme.colorScheme.onSurfaceVariant, 20.dp) { pm.moveToNext(i) }
                                Icon(
                                    Icons.Rounded.DragHandle, "Reorder", tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(horizontal = 12.dp).size(26.dp).pointerInput(keys) {
                                        detectDragGestures(
                                            onDragStart = { dragIdx = state.layoutInfo.visibleItemsInfo.firstOrNull { it.key == keys.getOrNull(i) }?.index ?: i; dragDy = 0f },
                                            onDragEnd = { dragIdx = -1; dragDy = 0f }, onDragCancel = { dragIdx = -1; dragDy = 0f },
                                            onDrag = { c, d ->
                                                c.consume(); dragDy += d.y
                                                val h = state.layoutInfo.visibleItemsInfo.firstOrNull()?.size?.toFloat() ?: 150f
                                                while (dragDy > h / 2 && dragIdx < pm.queue.size - 1) { pm.moveQueueItem(dragIdx, dragIdx + 1); dragIdx++; dragDy -= h }
                                                while (dragDy < -h / 2 && dragIdx > 0) { pm.moveQueueItem(dragIdx, dragIdx - 1); dragIdx--; dragDy += h }
                                            },
                                        )
                                    },
                                )
                            },
                        )
                    }
                }
            }
        }
    }
}

// ====================================================================================== player settings
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlayerSettingsSheet(onDismiss: () -> Unit) {
    val pm = Graph.player; val store = Graph.store
    val accent = LocalAccent.current
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = MaterialTheme.colorScheme.surfaceContainerHigh) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 28.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text("Player settings", fontSize = 20.sp, fontWeight = FontWeight.Bold)

            // -------- sleep timer
            SettingCard("Sleep timer", glow = pm.sleepActive) {
                var min by remember { mutableStateOf("") }
                var sec by remember { mutableStateOf("") }
                if (pm.sleepActive) {
                    Text(fmt(pm.sleepRemainingMs), fontSize = 34.sp, fontWeight = FontWeight.Bold, color = accent)
                    Text("Keeps counting while you change songs.", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Button(onClick = { pm.cancelSleep() }, shape = RoundedCornerShape(20.dp)) { Text("Stop timer") }
                } else {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(min, { min = it.filter(Char::isDigit).take(4) }, label = { Text("Minutes") }, singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.weight(1f))
                        OutlinedTextField(sec, { sec = it.filter(Char::isDigit).take(2) }, label = { Text("Seconds") }, singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.weight(1f))
                    }
                    Row(Modifier.horizontalScrollRow(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(5, 10, 15, 30, 45, 60, 90).forEach { m ->
                            FilterChip(selected = false, onClick = { pm.startSleep(m * 60_000L) }, label = { Text("$m min") })
                        }
                    }
                    Button(
                        onClick = {
                            val total = (min.toLongOrNull() ?: 0L) * 60_000L + (sec.toLongOrNull() ?: 0L) * 1000L
                            if (total > 0) pm.startSleep(total)
                        },
                        shape = RoundedCornerShape(20.dp),
                    ) { Icon(Icons.Rounded.Timer, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Start timer") }
                }
                SwitchRow("Fade out volume at the end", pm.sleepFadeOut) { pm.setSleepFade(it) }
                SwitchRow("Stop after this song", pm.stopAfterSong) { pm.stopAfterSong = it }
            }

            // -------- background look + effects
            // -------- speed / pitch
            SettingCard("Speed") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("%.2fx".format(pm.speed), fontSize = 22.sp, fontWeight = FontWeight.Bold, color = accent, modifier = Modifier.width(80.dp))
                    Slider(pm.speed, { pm.changeSpeed((it * 20).roundToInt() / 20f) }, valueRange = 0.5f..2f, modifier = Modifier.weight(1f),
                        colors = SliderDefaults.colors(activeTrackColor = accent, thumbColor = accent))
                    TextButton(onClick = { pm.changeSpeed(1f) }) { Text("Reset") }
                }
                Row(Modifier.fillMaxWidth().clickable { pm.changePitchLinked(!pm.pitchLinked) }, verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(pm.pitchLinked, { pm.changePitchLinked(it) })
                    Column {
                        Text("Change pitch with speed")
                        Text(if (pm.pitchLinked) "Faster = higher, slower = lower" else "Speed only, pitch stays the same", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }

            // -------- equalizer + bass
            SettingCard("Equalizer") {
                if (!pm.eqAvailable) Text("Equalizer isn't available on this device.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                else {
                    SwitchRow("Enabled", pm.eqEnabled) { pm.changeEqEnabled(it) }
                    if (pm.presets.isNotEmpty()) Row(Modifier.horizontalScrollRow(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        pm.presets.forEachIndexed { i, n -> FilterChip(selected = false, onClick = { pm.usePreset(i) }, label = { Text(n) }) }
                    }
                    pm.bandFreqs.forEachIndexed { i, hz ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(if (hz >= 1000) "${hz / 1000} kHz" else "$hz Hz", fontSize = 12.sp, modifier = Modifier.width(60.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Slider(
                                pm.bandLevels.getOrElse(i) { 0 }.toFloat(), { pm.setBand(i, it.roundToInt()) },
                                valueRange = pm.levelMin.toFloat()..pm.levelMax.toFloat(), enabled = pm.eqEnabled, modifier = Modifier.weight(1f),
                                colors = SliderDefaults.colors(activeTrackColor = accent, thumbColor = accent),
                            )
                            Text("${pm.bandLevels.getOrElse(i) { 0 } / 100} dB", fontSize = 11.sp, modifier = Modifier.width(44.dp), textAlign = TextAlign.End, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    TextButton(onClick = { pm.resetEq() }) { Text("Reset bands") }
                }
                if (pm.bassAvailable) {
                    Text("Bass boost", fontWeight = FontWeight.SemiBold)
                    Slider(pm.bassStrength.toFloat(), { pm.setBass(it.roundToInt()) }, valueRange = 0f..1000f,
                        colors = SliderDefaults.colors(activeTrackColor = accent, thumbColor = accent))
                }
            }

            // -------- crossfade
            SettingCard("Song transition") {
                Text(if (store.crossfadeSec == 0) "Gapless (no fade)" else "Fade out / in over ${store.crossfadeSec}s", fontWeight = FontWeight.SemiBold)
                Slider(store.crossfadeSec.toFloat(), { store.setCrossfade(it.roundToInt()) }, valueRange = 0f..12f, steps = 11,
                    colors = SliderDefaults.colors(activeTrackColor = accent, thumbColor = accent))
                Text("0 = gapless playback between songs.", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun Modifier.horizontalScrollRow(): Modifier = this.then(Modifier.horizontalScroll(rememberScrollState()))

@Composable
private fun SettingCard(title: String, glow: Boolean = false, content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) {
    val accent = LocalAccent.current
    val border by animateColorAsState(if (glow) accent else Color.Transparent, tween(300), label = "cardborder")
    Surface(
        shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
        modifier = Modifier.fillMaxWidth().shadow(if (glow) 14.dp else 0.dp, RoundedCornerShape(20.dp), ambientColor = accent, spotColor = accent),
        border = androidx.compose.foundation.BorderStroke(1.dp, border),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, fontSize = 15.sp, fontWeight = FontWeight.Bold)
            content()
        }
    }
}

@Composable
fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f))
        Switch(checked, onChange)
    }
}

// ====================================================================================== mini player
@Composable
fun MiniPlayer(onOpen: () -> Unit) {
    val pm = Graph.player
    val song = pm.current ?: return
    val accent = LocalAccent.current
    var dx by remember { mutableLongStateOf(0L) }
    Surface(
        shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh, tonalElevation = 6.dp, shadowElevation = 10.dp,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 6.dp).clip(RoundedCornerShape(20.dp))
            .clickable(onClick = onOpen)
            .pointerInput(Unit) {
                detectHorizontalDragGestures(
                    onDragStart = { dx = 0 },
                    onDragEnd = { if (dx < -90) pm.next() else if (dx > 90 && pm.player.hasPreviousMediaItem()) pm.player.seekToPreviousMediaItem() },
                    onHorizontalDrag = { c, d -> c.consume(); dx += d.toLong() },
                )
            },
    ) {
        Column {
            Row(Modifier.padding(start = 8.dp, end = 2.dp, top = 8.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                AnimatedContent(song, label = "mini-art", transitionSpec = { fadeIn(tween(250)) togetherWith fadeOut(tween(150)) }) { s ->
                    ArtImage(s, Modifier.size(46.dp), 128, 12.dp)
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(song.title, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                    Text(song.artist, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                IconBtn(Icons.Rounded.SkipPrevious, "Previous", MaterialTheme.colorScheme.onSurface, 26.dp) { pm.previous() }
                Crossfade(pm.isPlaying, label = "mini-pp") { p ->
                    IconBtn(if (p) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, "Play/Pause", accent, 30.dp) { pm.togglePlay() }
                }
                IconBtn(Icons.Rounded.SkipNext, "Next", MaterialTheme.colorScheme.onSurface, 26.dp) { pm.next() }
            }
            val frac = if (pm.durationMs > 0) (pm.positionMs.toFloat() / pm.durationMs).coerceIn(0f, 1f) else 0f
            Box(Modifier.fillMaxWidth().height(2.dp).background(accent.copy(alpha = 0.15f))) {
                Box(Modifier.fillMaxWidth(frac).height(2.dp).background(accent))
            }
        }
    }
}


/** saturation 0..2, brightness -0.5..0.5, contrast 0.5..1.6  ->  one colour matrix for the background image */
fun bgColorFilter(sat: Float, bright: Float, contrast: Float): androidx.compose.ui.graphics.ColorFilter? {
    if (sat == 1f && bright == 0f && contrast == 1f) return null
    val lr = 0.213f; val lg = 0.715f; val lb = 0.072f
    val t = (0.5f * (1f - contrast) + bright) * 255f
    val m = floatArrayOf(
        contrast * (lr * (1 - sat) + sat), contrast * lg * (1 - sat), contrast * lb * (1 - sat), 0f, t,
        contrast * lr * (1 - sat), contrast * (lg * (1 - sat) + sat), contrast * lb * (1 - sat), 0f, t,
        contrast * lr * (1 - sat), contrast * lg * (1 - sat), contrast * (lb * (1 - sat) + sat), 0f, t,
        0f, 0f, 0f, 1f, 0f,
    )
    return androidx.compose.ui.graphics.ColorFilter.colorMatrix(androidx.compose.ui.graphics.ColorMatrix(m))
}

@Composable
fun LabeledSlider(label: String, value: Float, range: ClosedFloatingPointRange<Float>, fmt: String, onChange: (Float) -> Unit) {
    val accent = LocalAccent.current
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, fontSize = 13.sp, modifier = Modifier.width(84.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
        Slider(value, onChange, valueRange = range, modifier = Modifier.weight(1f), colors = SliderDefaults.colors(activeTrackColor = accent, thumbColor = accent))
        Text(fmt.format(value), fontSize = 12.sp, modifier = Modifier.width(44.dp), textAlign = TextAlign.End, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
