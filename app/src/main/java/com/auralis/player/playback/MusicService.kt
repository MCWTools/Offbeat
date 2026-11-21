package com.auralis.player.playback

import android.app.PendingIntent
import android.content.Intent
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.auralis.player.Graph
import com.auralis.player.ui.MainActivity
import kotlinx.coroutines.launch

/** Foreground media service: background playback + the media notification (cover, title, artist, prev / play / next). */
@UnstableApi
class MusicService : MediaSessionService() {
    private var session: MediaSession? = null

    override fun onCreate() {
        super.onCreate()
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val sess = MediaSession.Builder(this, Graph.player.player).setSessionActivity(open).build()
        session = sess
        // Without this the notification is never posted (the service is only started, no controller binds to it)
        addSession(sess)
        setMediaNotificationProvider(AccentNotificationProvider(this))
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val pm = Graph.player
        when (intent?.action) {
            ACTION_TOGGLE -> Graph.scope.launch { if (pm.player.mediaItemCount == 0) pm.restoreLast(); pm.togglePlay() }
            ACTION_NEXT -> Graph.scope.launch { if (pm.player.mediaItemCount == 0) pm.restoreLast(); pm.next() }
            ACTION_PREV -> Graph.scope.launch { if (pm.player.mediaItemCount == 0) pm.restoreLast(); pm.previous() }
        }
        return super.onStartCommand(intent, flags, startId)
    }

    // Keep the notification pinned (can't be swiped away) even while paused.
    override fun onUpdateNotification(session: MediaSession, startInForegroundRequired: Boolean) {
        super.onUpdateNotification(session, true)
    }

    // Swiping the app away from recents must not kill the music.
    override fun onTaskRemoved(rootIntent: Intent?) {}

    override fun onDestroy() {
        session?.release(); session = null
        super.onDestroy()
    }

    companion object {
        const val ACTION_TOGGLE = "com.auralis.player.TOGGLE"
        const val ACTION_NEXT = "com.auralis.player.NEXT"
        const val ACTION_PREV = "com.auralis.player.PREV"
    }
}
