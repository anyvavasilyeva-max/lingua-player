package io.github.linguaplayer

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import io.github.linguaplayer.ui.LinguaNavHost
import io.github.linguaplayer.ui.theme.LinguaTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            LinguaTheme {
                LinguaNavHost()
            }
        }
    }
}
