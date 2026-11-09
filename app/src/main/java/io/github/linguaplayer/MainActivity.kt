package io.github.linguaplayer

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.Color
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.linguaplayer.data.repo.DEFAULT_SEED_COLOR
import io.github.linguaplayer.ui.LinguaNavHost
import io.github.linguaplayer.ui.theme.LinguaTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val settings = (application as LinguaApp).container.settings
        setContent {
            val seed by settings.seedColor.collectAsStateWithLifecycle(DEFAULT_SEED_COLOR)
            LinguaTheme(seed = Color(seed)) {
                LinguaNavHost()
            }
        }
    }
}
