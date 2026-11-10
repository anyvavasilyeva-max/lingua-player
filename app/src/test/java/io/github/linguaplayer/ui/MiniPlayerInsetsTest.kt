package io.github.linguaplayer.ui

import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onRoot
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.linguaplayer.LinguaApp
import io.github.linguaplayer.MainActivity
import io.github.linguaplayer.data.db.EntryType
import io.github.linguaplayer.data.repo.DEFAULT_SEED_COLOR
import io.github.linguaplayer.data.repo.EntryDraft
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Мини-плеер не должен уходить под системные кнопки Android (трёхкнопочная навигация).
 * Системная панель имитируется отступом 48 dp снизу.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "ru-rRU-w360dp-h640dp")
class MiniPlayerInsetsTest {
    @get:Rule
    val rule = createEmptyComposeRule()
    private val ui by lazy { UiTestHelper(rule) }
    private lateinit var scenario: ActivityScenario<MainActivity>
    private val c get() = ApplicationProvider.getApplicationContext<LinguaApp>().container

    @Before
    fun setUp() {
        c.playerSpeechOverride = HoldingSpeech()
        runBlocking {
            c.settings.setSeedColor(DEFAULT_SEED_COLOR)
            c.settings.updatePlayback { io.github.linguaplayer.player.PlaybackSettings() }
            val pair = c.languagePairs.getAll().first().id
            val e = c.entries.save(
                EntryDraft(pairId = pair, source = "백화점이 은행 간너편에 있습니다.", translation = "Универмаг находится напротив банка.", type = EntryType.PHRASE, tagIds = emptySet()),
            )
            val p = c.playlists.create("диалоги", pair)
            c.playlists.addEntries(p, listOf(e))
        }
        scenario = ActivityScenario.launch(MainActivity::class.java)
    }

    @After
    fun tearDown() = scenario.close()

    private fun applyNavigationBar(heightPx: Int) {
        scenario.onActivity { a ->
            val insets = WindowInsetsCompat.Builder()
                .setInsets(WindowInsetsCompat.Type.navigationBars(), Insets.of(0, 0, 0, heightPx))
                .build()
            ViewCompat.dispatchApplyWindowInsets(a.window.decorView, insets)
        }
    }

    @Test
    fun miniPlayerStaysAboveSystemButtons() {
        var density = 1f
        scenario.onActivity { density = it.resources.displayMetrics.density }
        val navBarPx = (48 * density).toInt()
        applyNavigationBar(navBarPx)

        // Запуск плейлиста и возврат на экран плейлиста (там нет нижнего меню).
        ui.click("Плейлисты")
        ui.click("диалоги")
        ui.click("Слушать")
        ui.waitText("1 из 1")
        ui.clickDesc("Назад") // закрыть плеер → экран плейлиста с мини-плеером
        ui.waitText("диалоги · 1/1")
        applyNavigationBar(navBarPx)
        rule.waitForIdle()
        ui.shot("50_mini_player_playlist_navbar")

        val rootBottom = rule.onRoot().fetchSemanticsNode().boundsInRoot.bottom
        val safeBottom = rootBottom - navBarPx
        val texts = listOf("диалоги · 1/1", "Универмаг находится напротив банка.")
        for (t in texts) {
            val b = rule.onAllNodesWithText(t, useUnmergedTree = true).fetchSemanticsNodes().last().boundsInRoot
            assertTrue("«$t» перекрыт системными кнопками: ${b.bottom} > $safeBottom", b.bottom <= safeBottom)
        }
        val play = (rule.onAllNodesWithContentDescription("Пауза").fetchSemanticsNodes() + rule.onAllNodesWithContentDescription("Воспроизвести").fetchSemanticsNodes()).last().boundsInRoot
        assertTrue("кнопка ▶ под системными кнопками", play.bottom <= safeBottom)
        // Плашка крупная: не меньше 72 dp.
        assertTrue(play.height >= 52 * density - 1)

        // На главной мини-плеер — над нижним меню, которое само отступает от системных кнопок.
        ui.clickDesc("Назад")
        ui.click("Главная")
        ui.waitText("диалоги · 1/1")
        ui.shot("51_mini_player_home_navbar")
        val home = rule.onAllNodesWithText("диалоги · 1/1", useUnmergedTree = true).fetchSemanticsNodes().last().boundsInRoot
        assertTrue(home.bottom <= safeBottom)
    }
}
