package com.auralis.player.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.QueueMusic
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.auralis.player.Graph
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            AuralisTheme(Graph.store.accent, Graph.store.themeMode) {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) { Root() }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (hasAudioPermission()) Graph.scope.launch { Graph.library.refresh() }
    }

    private fun hasAudioPermission() = ContextCompat.checkSelfPermission(this, audioPermission()) == PackageManager.PERMISSION_GRANTED
}

private fun audioPermission() =
    if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_AUDIO else Manifest.permission.READ_EXTERNAL_STORAGE

@Composable
private fun Root() {
    val ctx = LocalContext.current
    var granted by remember { mutableStateOf(ContextCompat.checkSelfPermission(ctx, audioPermission()) == PackageManager.PERMISSION_GRANTED) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { res ->
        granted = res[audioPermission()] == true || ContextCompat.checkSelfPermission(ctx, audioPermission()) == PackageManager.PERMISSION_GRANTED
    }
    if (!granted) {
        Column(Modifier.fillMaxSize().padding(32.dp), verticalArrangement = androidx.compose.foundation.layout.Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(Icons.Rounded.MusicNote, null, tint = LocalAccent.current, modifier = Modifier.padding(bottom = 16.dp).then(Modifier.androidxSize(64)))
            Text("Welcome to Offbeat", fontSize = 24.sp, fontWeight = FontWeight.Bold)
            Text("Allow access to your music so the app can find your songs automatically.", textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 14.dp))
            Button(onClick = {
                val perms = mutableListOf(audioPermission())
                if (Build.VERSION.SDK_INT >= 33) perms += Manifest.permission.POST_NOTIFICATIONS
                launcher.launch(perms.toTypedArray())
            }) { Text("Allow access") }
        }
        return
    }

    LaunchedEffect(Unit) {
        Graph.library.refresh()
        Graph.library.startObserving()
        Graph.player.restoreLast()
    }
    // notification permission (Android 13+), asked once after music permission
    val notifLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    var tab by remember { mutableIntStateOf(0) }
    var playerOpen by remember { mutableStateOf(false) }
    val pm = Graph.player
    BackHandler(playerOpen) { playerOpen = false }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            Box(Modifier.weight(1f)) {
                Crossfade(tab, animationSpec = tween(220), label = "tabs") { t ->
                    when (t) { 0 -> LibraryScreen(); 1 -> PlaylistsScreen(); else -> SettingsScreen() }
                }
            }
            AnimatedVisibility(pm.current != null && !playerOpen, enter = fadeIn() + slideInVertically { it / 2 }, exit = fadeOut() + slideOutVertically { it / 2 }) {
                MiniPlayer { playerOpen = true }
            }
            NavigationBar(containerColor = MaterialTheme.colorScheme.surfaceContainer) {
                listOf(Icons.Rounded.MusicNote to "Music", Icons.Rounded.QueueMusic to "Playlists", Icons.Rounded.Settings to "Settings").forEachIndexed { i, (ic, label) ->
                    NavigationBarItem(
                        selected = tab == i, onClick = { tab = i }, icon = { Icon(ic, label) }, label = { Text(label) },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = MaterialTheme.colorScheme.onPrimary, indicatorColor = LocalAccent.current,
                            selectedTextColor = LocalAccent.current,
                        ),
                    )
                }
            }
        }
        AnimatedVisibility(
            playerOpen && pm.current != null,
            enter = slideInVertically(tween(380)) { it } + fadeIn(tween(300)),
            exit = slideOutVertically(tween(300)) { it } + fadeOut(tween(250)),
        ) { PlayerScreen { playerOpen = false } }
    }
}

private fun Modifier.androidxSize(dp: Int) = this.then(Modifier.size(dp.dp))
