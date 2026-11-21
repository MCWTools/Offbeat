package com.auralis.player.ui

import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.auralis.player.Graph
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

@Composable
fun SettingsScreen() {
    val store = Graph.store
    val scope = rememberCoroutineScope()
    val accent = LocalAccent.current
    var page by remember { mutableStateOf("") }
    if (page == "backup") { androidx.activity.compose.BackHandler { page = "" }; BackupScreen { page = "" }; return }
    if (page == "dups") { androidx.activity.compose.BackHandler { page = "" }; DuplicatesScreen { page = "" }; return }
    Column(Modifier.fillMaxSize().statusBarsPadding().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp)) {
        Text("Settings", fontSize = 26.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(vertical = 18.dp))

        Section("Accent color")
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Accents.forEachIndexed { i, a ->
                Box(
                    Modifier.size(38.dp).clip(CircleShape).background(a.color)
                        .border(if (i == store.accent) 3.dp else 0.dp, MaterialTheme.colorScheme.onSurface, CircleShape)
                        .clickable { store.changeAccent(i) },
                    contentAlignment = Alignment.Center,
                ) { if (i == store.accent) Icon(Icons.Rounded.Check, null, tint = Color.Black.copy(alpha = 0.7f), modifier = Modifier.size(18.dp)) }
            }
        }
        Text(Accents[store.accent].name, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp))

        Section("Theme")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            listOf("System", "Dark", "AMOLED", "Light").forEachIndexed { i, n ->
                FilterChip(selected = store.themeMode == i, onClick = { store.changeThemeMode(i) }, label = { Text(n) })
            }
        }

            Section("Player background")
        run {
                SwitchRow("Blur background (off = colour mix)", store.bgBlurOn) { store.bgBlurOn = it; store.saveBg() }
                if (store.bgBlurOn) {
                    LabeledSlider("Blur power", store.bgBlur, 0f..40f, "%.0f") { store.bgBlur = it; store.saveBg() }
                    LabeledSlider("Zoom", store.bgZoom, 0.5f..2.5f, "%.2fx") { store.bgZoom = it; store.saveBg() }
                }
                LabeledSlider("Brightness", store.bgBright, -0.5f..0.5f, "%+.2f") { store.bgBright = it; store.saveBg() }
                LabeledSlider("Contrast", store.bgContrast, 0.5f..1.6f, "%.2f") { store.bgContrast = it; store.saveBg() }
                LabeledSlider("Saturation", store.bgSat, 0f..2f, "%.2f") { store.bgSat = it; store.saveBg() }
                LabeledSlider("Darkness", store.bgDim, 0f..0.9f, "%.2f") { store.bgDim = it; store.saveBg() }
                TextButton(onClick = { store.resetBg() }) { Text("Reset background") }
            }

            Section("Volume")
        run {
                SwitchRow("Use decibels (auto-level every song)", store.useDb) { store.useDb = it; Graph.player.levelSettingsChanged() }
                if (store.useDb) {
                    Text("The volume slider becomes a target loudness. Quiet songs are lifted and loud ones lowered to that level.", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    SwitchRow("Dynamic volume (level inside the song)", store.dynVol) { store.dynVol = it; Graph.player.levelSettingsChanged() }
                    if (store.dynVol) {
                        LabeledSlider("Segments", store.dynSegs, 4f..60f, "%.0f") { store.dynSegs = Math.round(it).toFloat(); Graph.player.levelSettingsChanged() }
                        Text("The song is cut into this many parts; parts that are too quiet or too loud are gently nudged and blended smoothly.", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }

        Section("Visualizer")
        SwitchRow("Music-reactive bars around the cover", store.vizOn) { store.vizOn = it; store.saveViz() }

        Section("Song transition")
        Text(if (store.crossfadeSec == 0) "Gapless (no fade)" else "Fade over ${store.crossfadeSec}s", fontSize = 14.sp)
        Slider(store.crossfadeSec.toFloat(), { store.setCrossfade(it.roundToInt()) }, valueRange = 0f..12f, steps = 11,
            colors = SliderDefaults.colors(activeTrackColor = accent, thumbColor = accent))

        Section("Library")
        Text("New or deleted songs are picked up automatically.", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        TextButton(onClick = { scope.launch { Graph.library.refresh() } }) { Text("Rescan now") }

        Section("Tools")
        TextButton(onClick = { page = "dups" }) { Text("Find duplicate songs") }
        TextButton(onClick = { page = "backup" }) { Text("Backup & restore") }

        Section("Link downloader")
        Text("Uses yt-dlp inside the app. If a YouTube / TikTok link stops working, update it.", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        TextButton(onClick = { Graph.downloader.updateEngine() }) { Text("Update downloader") }
        if (Graph.downloader.engineMessage.isNotBlank()) Text(Graph.downloader.engineMessage, fontSize = 12.sp, color = accent)

        Section("About")
        Text("Offbeat", fontSize = 14.sp)
        Text("${Graph.library.songs.size} songs · ${store.playlists.size} playlists", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
fun Section(title: String) {
    Text(title, fontSize = 13.sp, fontWeight = FontWeight.Bold, color = LocalAccent.current, modifier = Modifier.padding(top = 26.dp, bottom = 10.dp))
}
