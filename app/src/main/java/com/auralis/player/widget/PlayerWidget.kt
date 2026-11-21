package com.auralis.player.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.widget.RemoteViews
import com.auralis.player.Graph
import com.auralis.player.R
import com.auralis.player.data.ArtLoader
import com.auralis.player.playback.MusicService
import com.auralis.player.ui.MainActivity
import kotlinx.coroutines.launch

class PlayerWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        Graph.init(context)
        refresh(context)
    }

    companion object {
        private fun pending(ctx: Context, action: String, code: Int): PendingIntent =
            PendingIntent.getService(
                ctx, code, Intent(ctx, MusicService::class.java).setAction(action),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )

        fun refresh(ctx: Context) {
            val mgr = AppWidgetManager.getInstance(ctx)
            val ids = mgr.getAppWidgetIds(ComponentName(ctx, PlayerWidget::class.java))
            if (ids.isEmpty()) return
            Graph.scope.launch {
                val pm = Graph.player
                val song = pm.current
                val rv = RemoteViews(ctx.packageName, R.layout.widget_player)
                rv.setTextViewText(R.id.w_title, song?.title ?: "Offbeat")
                rv.setTextViewText(R.id.w_artist, song?.artist ?: "Tap play to start")
                rv.setImageViewResource(R.id.w_play, if (pm.isPlaying) R.drawable.ic_w_pause else R.drawable.ic_w_play)
                val bmp = song?.let { ArtLoader.load(ctx, it, 240) }
                val hue = ((song?.id?.hashCode() ?: 0).toUInt() % 360u).toFloat()
                if (bmp != null) rv.setImageViewBitmap(R.id.w_art, rounded(bmp, 200, 36f))
                else rv.setImageViewBitmap(R.id.w_art, rounded(gradient(200, 200, hue), 200, 36f))
                rv.setImageViewBitmap(R.id.w_bg, background(bmp, hue))
                rv.setInt(R.id.w_play, "setColorFilter", 0xFF111111.toInt())
                rv.setOnClickPendingIntent(R.id.w_play, pending(ctx, MusicService.ACTION_TOGGLE, 1))
                rv.setOnClickPendingIntent(R.id.w_next, pending(ctx, MusicService.ACTION_NEXT, 2))
                rv.setOnClickPendingIntent(R.id.w_prev, pending(ctx, MusicService.ACTION_PREV, 3))
                rv.setOnClickPendingIntent(
                    R.id.w_root,
                    PendingIntent.getActivity(ctx, 4, Intent(ctx, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT),
                )
                mgr.updateAppWidget(ids, rv)
            }
        }

        private fun gradient(w: Int, h: Int, hue: Float): Bitmap {
            val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            val p = Paint(Paint.ANTI_ALIAS_FLAG)
            p.shader = LinearGradient(0f, 0f, w.toFloat(), h.toFloat(), Color.HSVToColor(floatArrayOf(hue, 0.55f, 0.62f)), Color.HSVToColor(floatArrayOf((hue + 45f) % 360f, 0.6f, 0.30f)), Shader.TileMode.CLAMP)
            Canvas(b).drawRect(0f, 0f, w.toFloat(), h.toFloat(), p)
            return b
        }

        private fun rounded(src: Bitmap, size: Int, r: Float): Bitmap {
            val out = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
            val c = Canvas(out); val p = Paint(Paint.ANTI_ALIAS_FLAG)
            c.drawRoundRect(RectF(0f, 0f, size.toFloat(), size.toFloat()), r, r, p)
            p.xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_IN)
            val m = minOf(src.width, src.height)
            c.drawBitmap(src, android.graphics.Rect((src.width - m) / 2, (src.height - m) / 2, (src.width + m) / 2, (src.height + m) / 2), RectF(0f, 0f, size.toFloat(), size.toFloat()), p)
            return out
        }

        /** Rounded card: blurred + darkened cover (or the placeholder colours), a soft colour glow and a bottom shade. */
        private fun background(cover: Bitmap?, hue: Float): Bitmap {
            val w = 640; val h = 360
            val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            val c = Canvas(out)
            val clip = Path().apply { addRoundRect(RectF(0f, 0f, w.toFloat(), h.toFloat()), 56f, 56f, Path.Direction.CW) }
            c.clipPath(clip)
            val base = if (cover != null) cover else gradient(64, 64, hue)
            val tiny = Bitmap.createScaledBitmap(base, 12, 12, true)           // down then up = cheap heavy blur
            val blur = Bitmap.createScaledBitmap(tiny, w / 2, h / 2, true)
            val p = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
            c.drawBitmap(blur, null, RectF(-40f, -40f, w + 40f, h + 40f), p)
            // accent glow, top right
            var avg = tiny.getPixel(6, 6)
            val hsv = FloatArray(3); Color.colorToHSV(avg, hsv); hsv[1] = (hsv[1] + 0.25f).coerceAtMost(1f); hsv[2] = 1f
            val glow = Color.HSVToColor(hsv)
            p.shader = RadialGradient(w * 0.92f, h * 0.05f, w * 0.55f, Color.argb(120, Color.red(glow), Color.green(glow), Color.blue(glow)), Color.TRANSPARENT, Shader.TileMode.CLAMP)
            c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), p)
            // darken for readable text, stronger at the bottom
            p.shader = LinearGradient(0f, 0f, 0f, h.toFloat(), Color.argb(90, 0, 0, 0), Color.argb(170, 0, 0, 0), Shader.TileMode.CLAMP)
            c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), p)
            // thin light edge
            p.shader = null; p.style = Paint.Style.STROKE; p.strokeWidth = 3f; p.color = Color.argb(40, 255, 255, 255)
            c.drawRoundRect(RectF(1.5f, 1.5f, w - 1.5f, h - 1.5f), 55f, 55f, p)
            return out
        }
    }
}
