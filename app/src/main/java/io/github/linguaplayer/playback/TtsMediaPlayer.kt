package io.github.linguaplayer.playback

import android.os.Looper
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.SimpleBasePlayer
import androidx.media3.common.util.UnstableApi
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import io.github.linguaplayer.player.PlayerEngine
import io.github.linguaplayer.player.PlayerState
import io.github.linguaplayer.player.QueueItem
import io.github.linguaplayer.player.RepeatMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Мост между нашим движком и Media3.
 *
 * TextToSpeech не является медиаплеером, поэтому MediaSession (экран блокировки, уведомление,
 * Bluetooth-кнопки, Android Auto/часы) получает «плеер» — эту обёртку над [PlayerEngine]:
 * - состояние Media3 строится из [PlayerState] (очередь = плейлист, запись = MediaItem,
 *   корейский текст — заголовок, перевод — исполнитель, название плейлиста — альбом);
 * - команды Media3 (play/pause/next/previous/seek/repeat) превращаются в вызовы движка.
 * Позиция внутри записи не сообщается (у синтеза речи нет длительности заранее),
 * поэтому на экране блокировки нет полосы перемотки — только управление записями.
 *
 * Вызывается только из главного потока.
 */
@OptIn(UnstableApi::class)
class TtsMediaPlayer(
    private val engine: PlayerEngine,
    scope: CoroutineScope,
    private val onSetRepeatMode: (RepeatMode) -> Unit,
) : SimpleBasePlayer(Looper.getMainLooper()) {

    private var cachedQueue: List<QueueItem>? = null
    private var cachedTitle: String? = null
    private var cachedPlaylist: List<MediaItemData> = emptyList()
    private val commands = Player.Commands.Builder().addAll(
        Player.COMMAND_PLAY_PAUSE,
        Player.COMMAND_STOP,
        Player.COMMAND_SEEK_TO_NEXT,
        Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM,
        Player.COMMAND_SEEK_TO_PREVIOUS,
        Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM,
        Player.COMMAND_SEEK_TO_MEDIA_ITEM,
        Player.COMMAND_SEEK_TO_DEFAULT_POSITION,
        Player.COMMAND_GET_CURRENT_MEDIA_ITEM,
        Player.COMMAND_GET_TIMELINE,
        Player.COMMAND_GET_METADATA,
        Player.COMMAND_SET_REPEAT_MODE,
        Player.COMMAND_RELEASE,
    ).build()

    // Подписка — последней: к этому моменту все поля уже инициализированы.
    private val job: Job = scope.launch { engine.state.collect { invalidateState() } }

    /** Плейлист Media3 пересобирается только при смене очереди (а не на каждую фразу). */
    private fun playlistFor(st: PlayerState): List<MediaItemData> {
        if (st.queue !== cachedQueue || st.title != cachedTitle) {
            cachedQueue = st.queue
            cachedTitle = st.title
            cachedPlaylist = st.queue.mapIndexed { i, item ->
                val metadata = MediaMetadata.Builder()
                    .setTitle(item.source)
                    .setArtist(item.translation)
                    .setAlbumTitle(st.title)
                    .setTrackNumber(i + 1)
                    .setTotalTrackCount(st.queue.size)
                    .build()
                MediaItemData.Builder("$i:${item.entryId}")
                    .setMediaItem(MediaItem.Builder().setMediaId(item.entryId.toString()).setMediaMetadata(metadata).build())
                    .setDurationUs(C.TIME_UNSET)
                    .setIsSeekable(false)
                    .build()
            }
        }
        return cachedPlaylist
    }

    override fun getState(): State {
        val st = engine.state.value
        val playlist = playlistFor(st)
        return State.Builder()
            .setAvailableCommands(commands)
            .setPlaylist(playlist)
            .setCurrentMediaItemIndex(if (playlist.isEmpty()) 0 else st.index.coerceIn(0, playlist.lastIndex))
            .setContentPositionMs(0)
            .setPlayWhenReady(st.isPlaying, Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
            .setPlaybackState(
                when {
                    playlist.isEmpty() -> Player.STATE_IDLE
                    st.ended -> Player.STATE_ENDED
                    else -> Player.STATE_READY
                },
            )
            .setRepeatMode(
                when (st.settings.repeatMode) {
                    RepeatMode.OFF -> Player.REPEAT_MODE_OFF
                    RepeatMode.ALL -> Player.REPEAT_MODE_ALL
                    RepeatMode.ONE -> Player.REPEAT_MODE_ONE
                },
            )
            // Очередь уже перемешана движком; для Media3 порядок — линейный.
            .setShuffleModeEnabled(false)
            .build()
    }

    override fun handleSetPlayWhenReady(playWhenReady: Boolean): ListenableFuture<*> {
        if (playWhenReady) engine.play() else engine.pause()
        return Futures.immediateVoidFuture()
    }

    override fun handlePrepare(): ListenableFuture<*> = Futures.immediateVoidFuture()

    override fun handleStop(): ListenableFuture<*> {
        engine.pause()
        return Futures.immediateVoidFuture()
    }

    override fun handleSeek(mediaItemIndex: Int, positionMs: Long, seekCommand: Int): ListenableFuture<*> {
        // Media3 сам вычисляет индекс для next/previous с учётом режима повтора.
        if (mediaItemIndex != C.INDEX_UNSET) engine.seekTo(mediaItemIndex)
        return Futures.immediateVoidFuture()
    }

    override fun handleSetRepeatMode(repeatMode: Int): ListenableFuture<*> {
        onSetRepeatMode(
            when (repeatMode) {
                Player.REPEAT_MODE_ALL -> RepeatMode.ALL
                Player.REPEAT_MODE_ONE -> RepeatMode.ONE
                else -> RepeatMode.OFF
            },
        )
        return Futures.immediateVoidFuture()
    }

    override fun handleRelease(): ListenableFuture<*> {
        job.cancel()
        return Futures.immediateVoidFuture()
    }
}
