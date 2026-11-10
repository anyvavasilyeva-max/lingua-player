package io.github.linguaplayer.playback

import android.app.PendingIntent
import android.content.Intent
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import io.github.linguaplayer.LinguaApp
import io.github.linguaplayer.MainActivity
import io.github.linguaplayer.R
import kotlinx.coroutines.launch

/**
 * Фоновое воспроизведение (foreground service типа mediaPlayback).
 *
 * Media3 сам показывает медиа-уведомление и переводит сервис в foreground, когда плеер
 * играет, и снимает foreground при паузе. Через MediaSession работают экран блокировки,
 * шторка уведомлений, Bluetooth-гарнитуры, часы и автомобильные системы.
 */
@OptIn(UnstableApi::class)
class PlaybackService : MediaSessionService() {
    private var session: MediaSession? = null

    override fun onCreate() {
        super.onCreate()
        val c = (application as LinguaApp).container
        val player = TtsMediaPlayer(c.player, c.mainScope) { mode ->
            c.appScope.launch { c.settings.updatePlayback { it.copy(repeatMode = mode) } }
        }
        setMediaNotificationProvider(
            DefaultMediaNotificationProvider.Builder(this)
                .setChannelName(R.string.notification_channel)
                .build()
                .apply { setSmallIcon(R.drawable.ic_stat_player) },
        )
        val openPlayer = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java)
                .putExtra(MainActivity.EXTRA_OPEN_PLAYER, true)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        session = MediaSession.Builder(this, player).setSessionActivity(openPlayer).build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    /** Приложение смахнули из «Недавних»: если не играет — сервис закрывается; если играет — продолжает. */
    override fun onTaskRemoved(rootIntent: Intent?) {
        val playing = (application as LinguaApp).container.player.state.value.isPlaying
        if (!playing) stopSelf()
    }

    override fun onDestroy() {
        session?.run {
            player.release()
            release()
        }
        session = null
        super.onDestroy()
    }
}
