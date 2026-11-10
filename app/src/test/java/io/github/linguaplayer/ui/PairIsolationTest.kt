package io.github.linguaplayer.ui

import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.linguaplayer.LinguaApp
import io.github.linguaplayer.MainActivity
import io.github.linguaplayer.data.db.EntryType
import io.github.linguaplayer.data.repo.EntryDraft
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Переключение языковой пары скрывает библиотеку и плейлисты другой пары. */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "ru-rRU")
class PairIsolationTest {
    @get:Rule
    val rule = createEmptyComposeRule()
    private val ui by lazy { UiTestHelper(rule) }
    private lateinit var scenario: ActivityScenario<MainActivity>
    private val c get() = ApplicationProvider.getApplicationContext<LinguaApp>().container

    @Before
    fun setUp() {
        c.playerSpeechOverride = HoldingSpeech()
        runBlocking {
            val ko = c.languagePairs.getAll().first().id
            val en = c.languagePairs.add("en-US", "ru-RU")
            c.settings.setActivePair(ko)
            c.settings.setSeedColor(io.github.linguaplayer.data.repo.DEFAULT_SEED_COLOR)
            c.entries.save(EntryDraft(pairId = ko, source = "포크", translation = "Вилка", type = EntryType.WORD, tagIds = emptySet()))
            c.entries.save(EntryDraft(pairId = en, source = "spoon", translation = "Ложка", type = EntryType.WORD, tagIds = emptySet()))
            c.playlists.create("Корейский плейлист", ko)
            c.playlists.create("English playlist", en)
        }
        scenario = ActivityScenario.launch(MainActivity::class.java)
    }

    @After
    fun tearDown() = scenario.close()

    private fun absent(text: String) = rule.onAllNodesWithText(text, useUnmergedTree = true).fetchSemanticsNodes().isEmpty()

    @Test
    fun switchingPairHidesOtherPair() {
        ui.waitText("Корейский → Русский")
        // Слушаем корейскую библиотеку.
        ui.clickDesc("Слушать")
        ui.waitText("1 из 1")
        ui.clickDesc("Назад")

        ui.click("Библиотека")
        ui.waitText("포크")
        assertTrue(absent("spoon"))
        ui.click("Плейлисты")
        ui.waitText("Корейский плейлист")
        assertTrue(absent("English playlist"))

        // Переключение пары на главной.
        ui.click("Главная")
        ui.click("Корейский → Русский")
        ui.click("Английский → Русский")
        ui.waitText("Английский → Русский")
        // Очередь корейской пары сброшена.
        ui.realWait { !c.player.state.value.hasQueue }
        ui.shot("40_home_en_pair")

        ui.click("Библиотека")
        ui.waitText("spoon")
        assertTrue(absent("포크"))
        ui.click("Плейлисты")
        ui.waitText("English playlist")
        assertTrue(absent("Корейский плейлист"))
        ui.shot("41_playlists_en_pair")
        assertFalse(c.player.state.value.hasQueue)
    }
}
