package com.auralis.player.playback

import android.app.Notification
import android.content.Context
import android.os.Bundle
import androidx.annotation.OptIn
import androidx.compose.ui.graphics.toArgb
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.CommandButton
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.MediaNotification
import androidx.media3.session.MediaSession
import androidx.media3.session.SessionCommand
import com.auralis.player.Graph
import com.auralis.player.R
import com.auralis.player.ui.Accents
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.ListenableFuture

/** Media notification (cover, title, artist, prev / play / next) tinted with the accent colour picked in Settings. */
@OptIn(UnstableApi::class)
class AccentNotificationProvider(private val ctx: Context) : MediaNotification.Provider {
    private val base = DefaultMediaNotificationProvider.Builder(ctx).build().apply { setSmallIcon(R.drawable.ic_stat_note) }

    override fun createNotification(
        mediaSession: MediaSession,
        customLayout: ImmutableList<CommandButton>,
        actionFactory: MediaNotification.ActionFactory,
        onNotificationChangedCallback: MediaNotification.Provider.Callback,
    ): MediaNotification {
        val n = base.createNotification(mediaSession, customLayout, actionFactory, onNotificationChangedCallback)
        val color = Accents[Graph.store.accent.coerceIn(0, Accents.lastIndex)].color.toArgb()
        val rebuilt = Notification.Builder.recoverBuilder(ctx, n.notification).setColor(color).setColorized(true).build()
        return MediaNotification(n.notificationId, rebuilt)
    }

    override fun handleCustomCommand(session: MediaSession, action: String, extras: Bundle): Boolean =
        base.handleCustomCommand(session, action, extras)
}
