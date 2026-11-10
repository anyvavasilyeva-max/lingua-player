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
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.SettableFuture

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
        session = MediaSession.Builder(this, player)
            .setSessionActivity(openPlayer)
            .setCallback(ResumptionCallback(c, player))
            .build()
    }

    /**
     * Кнопка ▶ на наушниках/в системных медиа-контролах, когда приложение не запущено:
     * система будит сервис через MediaButtonReceiver, а здесь возвращается сохранённая очередь.
     */
    private class ResumptionCallback(
        private val c: io.github.linguaplayer.AppContainer,
        private val player: TtsMediaPlayer,
    ) : MediaSession.Callback {
        override fun onPlaybackResumption(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            isForPlayback: Boolean,
        ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> {
            val future = SettableFuture.create<MediaSession.MediaItemsWithStartPosition>()
            c.mainScope.launch {
                c.playbackRestored.await()
                val st = c.player.state.value
                if (st.hasQueue) {
                    future.set(MediaSession.MediaItemsWithStartPosition(player.currentMediaItems(), st.index, 0))
                } else {
                    future.setException(UnsupportedOperationException("nothing to resume"))
                }
            }
            return future
        }
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
