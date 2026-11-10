package io.github.linguaplayer

import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.graphics.Color
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import io.github.linguaplayer.data.repo.DEFAULT_SEED_COLOR
import io.github.linguaplayer.playback.PlaybackService
import io.github.linguaplayer.ui.LinguaNavHost
import io.github.linguaplayer.ui.theme.LinguaTheme

class MainActivity : ComponentActivity() {
    /** Увеличивается, когда нужно открыть экран плеера (нажатие на медиа-уведомление). */
    private val openPlayerRequest = mutableIntStateOf(0)
    private var controller: ListenableFuture<MediaController>? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        handleIntent(intent)
        val settings = (application as LinguaApp).container.settings
        setContent {
            val seed by settings.seedColor.collectAsStateWithLifecycle(DEFAULT_SEED_COLOR)
            LinguaTheme(seed = Color(seed)) {
                LinguaNavHost(openPlayerRequest = openPlayerRequest.intValue)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        if (intent?.getBooleanExtra(EXTRA_OPEN_PLAYER, false) == true) {
            intent.removeExtra(EXTRA_OPEN_PLAYER)
            openPlayerRequest.intValue++
        }
    }

    /**
     * Подключение к сервису воспроизведения: пока приложение открыто, сервис запущен и
     * MediaSession готова. Когда начинается воспроизведение, Media3 переводит сервис в
     * foreground — и он продолжает работать после закрытия экрана.
     */
    override fun onStart() {
        super.onStart()
        if (!(application as LinguaApp).connectPlaybackService) return
        val token = SessionToken(this, ComponentName(this, PlaybackService::class.java))
        controller = MediaController.Builder(this, token).buildAsync()
    }

    override fun onStop() {
        controller?.let { MediaController.releaseFuture(it) }
        controller = null
        super.onStop()
    }

    companion object {
        const val EXTRA_OPEN_PLAYER = "io.github.linguaplayer.OPEN_PLAYER"
    }
}
