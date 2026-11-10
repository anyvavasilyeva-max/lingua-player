package io.github.linguaplayer.ui

import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.swipe
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.swipeRight
import androidx.compose.ui.test.onAllNodesWithText
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.linguaplayer.LinguaApp
import io.github.linguaplayer.MainActivity
import io.github.linguaplayer.data.db.EntryType
import io.github.linguaplayer.data.repo.EntryDraft
import io.github.linguaplayer.player.EngineStatus
import io.github.linguaplayer.player.SpeakResult
import io.github.linguaplayer.player.SpeechEngine
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.util.Collections

/**
 * Экран плеера с поддельным синтезатором: фраза «звучит», пока её не прервут командой.
 * Так состояние детерминировано и можно проверить, что именно и на каком языке произносится.
 */
class HoldingSpeech : SpeechEngine {
    override val status = MutableStateFlow(EngineStatus.READY)
    val spoken: MutableList<Pair<String, String>> = Collections.synchronizedList(mutableListOf())
    override suspend fun awaitReady() = true
    override suspend fun speak(text: String, lang: String, rate: Float): SpeakResult {
        spoken += text to lang
        awaitCancellation()
    }
    override fun stop() = Unit
}

@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "ru-rRU")
class PlayerFlowTest {
    @get:Rule
    val rule = createEmptyComposeRule()
    private val ui by lazy { UiTestHelper(rule) }
    private val speech = HoldingSpeech()
    private lateinit var scenario: ActivityScenario<MainActivity>
    private val c get() = ApplicationProvider.getApplicationContext<LinguaApp>().container

    @Before
    fun setUp() {
        c.playerSpeechOverride = speech
        runBlocking {
            // DataStore — один на процесс тестов: сбрасываем настройки, чтобы тесты не влияли друг на друга.
            c.settings.updatePlayback { io.github.linguaplayer.player.PlaybackSettings() }
            c.settings.setSeedColor(io.github.linguaplayer.data.repo.DEFAULT_SEED_COLOR)
            val pair = c.languagePairs.getAll().first().id
            c.entries.save(EntryDraft(pairId = pair, source = "포크", translation = "Вилка", type = EntryType.WORD, tagIds = emptySet()))
            c.entries.save(EntryDraft(pairId = pair, source = "숟가락", translation = "Ложка", type = EntryType.WORD, tagIds = emptySet()))
            c.entries.save(EntryDraft(pairId = pair, source = "설거지를 하다", translation = "Мыть посуду", type = EntryType.PHRASE, tagIds = emptySet()))
        }
        scenario = ActivityScenario.launch(MainActivity::class.java)
    }

    @After
    fun tearDown() = scenario.close()

    @Test
    fun playLibraryControlsAndDontRemember() {
        ui.waitText("Что послушаем?")
        ui.clickDesc("Слушать")

        // Библиотека по алфавиту: 설거지를 하다, 숟가락, 포크. Сразу звучит корейский.
        ui.waitText("1 из 3")
        ui.waitText("설거지를 하다")
        ui.realWait { speech.spoken.isNotEmpty() }
        assertEquals("설거지를 하다" to "ko-KR", speech.spoken.first())
        ui.shot("20_player")

        // «Не помню» ставит системный тег на текущую запись.
        ui.click("Не помню")
        ui.waitText("Отмечено «Не помню»")
        val tagged = runBlocking { c.tags.dontRememberTag()!!.let { t -> c.entries.getEntries(io.github.linguaplayer.data.db.EntryFilter(tagIds = setOf(t.id))) } }
        assertEquals(listOf("설거지를 하다"), tagged.map { it.source })
        ui.click("Отмечено «Не помню»")
        ui.waitText("Не помню")

        // Следующая запись.
        ui.clickDesc("Следующая")
        ui.waitText("2 из 3")
        ui.realWait { speech.spoken.last().first == "숟가락" }

        // Пауза и продолжение: запись начинается заново.
        ui.clickDesc("Пауза")
        ui.realWait { !c.player.state.value.isPlaying }
        val before = speech.spoken.size
        ui.clickDesc("Воспроизвести")
        ui.realWait { speech.spoken.size == before + 1 }
        assertEquals("숟가락", speech.spoken.last().first)

        // Смена направления в настройках: текущая запись начинается с перевода.
        ui.clickDesc("Настройки воспроизведения")
        ui.click("Русский → Корейский")
        ui.realWait { speech.spoken.last() == ("Ложка" to "ru-RU") }
        assertEquals(2, c.player.state.value.index + 1)
        ui.click("Слова")
        ui.realWait { c.player.state.value.queue.size == 2 }
        ui.shot("21_player_settings")
        val saved = runBlocking { c.settings.playback.first() }
        assertEquals(io.github.linguaplayer.player.Direction.TARGET_TO_SOURCE, saved.direction)
        assertEquals(io.github.linguaplayer.data.db.TypeFilter.WORDS, saved.typeFilter)
    }

    @Test
    fun flashcardsHiddenAnswerEyeSwipeRepeatShuffle() {
        runBlocking { c.settings.updatePlayback { it.copy(hideAnswer = true) } }
        ui.waitText("Что послушаем?")
        ui.clickDesc("Слушать")
        ui.waitText("1 из 3")
        // Ставим на паузу и листаем как карточки — без прослушивания.
        ui.clickDesc("Пауза")
        ui.realWait { !c.player.state.value.isPlaying }
        val spokenBefore = speech.spoken.size

        // Ответ (перевод) скрыт, пока не нажать «глазик».
        ui.waitText("설거지를 하다")
        ui.waitText("• • •")
        org.junit.Assert.assertTrue(rule.onAllNodesWithText("Мыть посуду").fetchSemanticsNodes().isEmpty())
        ui.shot("22_card_hidden")
        ui.clickDesc("Показать ответ")
        ui.waitText("Мыть посуду")
        ui.shot("23_card_revealed")

        // Свайп влево — следующая карточка, ответ снова скрыт.
        rule.onAllNodesWithText("설거지를 하다", useUnmergedTree = true)[0].performTouchInput { swipeLeft() }
        ui.waitText("2 из 3")
        ui.waitText("• • •")
        rule.onAllNodesWithText("숟가락", useUnmergedTree = true)[0].performTouchInput { swipeRight() }
        ui.waitText("1 из 3")
        assertEquals(spokenBefore, speech.spoken.size) // листание на паузе ничего не произносит

        // Кнопки повтора и перемешивания сохраняются в настройках.
        ui.clickDesc("Повтор выключен")
        ui.realWait { runBlocking { c.settings.playback.first().repeatMode } == io.github.linguaplayer.player.RepeatMode.ALL }
        ui.realWait { c.player.state.value.settings.repeatMode == io.github.linguaplayer.player.RepeatMode.ALL }
        ui.clickDesc("Зациклить список")
        ui.realWait { runBlocking { c.settings.playback.first().repeatMode } == io.github.linguaplayer.player.RepeatMode.ONE }
        ui.clickDesc("Перемешать")
        ui.realWait { runBlocking { c.settings.playback.first().order } == io.github.linguaplayer.player.PlayOrder.SHUFFLE }
        // При перемешивании текущая карточка осталась текущей.
        assertEquals("설거지를 하다", c.player.state.value.current!!.source)
        ui.shot("24_player_controls")
    }

    @Test
    fun eyeTapAffectsCurrentCardHoldAffectsAll() {
        ui.waitText("Что послушаем?")
        ui.clickDesc("Слушать")
        ui.waitText("1 из 3")
        ui.clickDesc("Пауза")
        ui.realWait { !c.player.state.value.isPlaying }

        // «Глазик» есть всегда; по умолчанию ответ открыт.
        ui.waitText("Мыть посуду")
        // Нажатие — скрыть только эту карточку.
        ui.clickDesc("Скрыть ответ")
        ui.waitText("• • •")
        ui.clickDesc("Следующая")
        ui.waitText("2 из 3")
        ui.waitText("Ложка") // на следующей карточке ответ снова открыт (общий режим не менялся)
        assertEquals(false, runBlocking { c.settings.playback.first().hideAnswer })

        // Удержание — скрыть на всех карточках; режим запоминается.
        rule.onAllNodesWithContentDescription("Скрыть ответ")[0].performTouchInput { longClick() }
        ui.realWait { runBlocking { c.settings.playback.first().hideAnswer } }
        ui.waitText("• • •")
        ui.clickDesc("Следующая")
        ui.waitText("3 из 3")
        ui.waitText("• • •")
        ui.shot("25_eye_all_hidden")
        // Подсказка про удержание — под кнопкой «Не помню».
        rule.onAllNodesWithText("Удерживайте 👁", substring = true, useUnmergedTree = true)[0].performScrollTo()
        org.junit.Assert.assertTrue(rule.onAllNodesWithText("чтобы показать", substring = true).fetchSemanticsNodes().size == 1)
        ui.shot("26_eye_hold_hint")

        // Нажатие на скрытой — открыть только её; общий режим остаётся «скрыто».
        ui.clickDesc("Показать ответ")
        ui.waitText("Вилка")
        ui.clickDesc("Предыдущая")
        ui.waitText("2 из 3")
        ui.waitText("• • •")

        // Удержание на скрытой — открыть на всех.
        rule.onAllNodesWithContentDescription("Показать ответ")[0].performTouchInput { longClick() }
        ui.realWait { runBlocking { !c.settings.playback.first().hideAnswer } }
        ui.waitText("Ложка")
        ui.clickDesc("Следующая")
        ui.waitText("Вилка")
    }

    @Test
    fun swipeDownClosesPlayerToHomeAndKeepsPlaying() {
        ui.waitText("Что послушаем?")
        ui.clickDesc("Слушать")
        ui.waitText("1 из 3")
        ui.realWait { c.player.state.value.isPlaying }
        // Свайп влево по-прежнему листает, а не закрывает.
        rule.onAllNodesWithText("설거지를 하다", useUnmergedTree = true)[0].performTouchInput { swipeLeft() }
        ui.waitText("2 из 3")
        // Свайп пальцем вниз на ~300 dp от слова на карточке.
        rule.onAllNodesWithText("숟가락", useUnmergedTree = true)[0].performTouchInput {
            swipe(start = center, end = center + androidx.compose.ui.geometry.Offset(0f, 300.dp.toPx()), durationMillis = 300)
        }
        ui.waitText("Что послушаем?") // главная
        ui.waitText("Корейский → Русский")
        assertEquals(true, c.player.state.value.isPlaying) // прослушивание продолжается
        ui.waitText("Библиотека · 2/3") // мини-плеер
        ui.shot("27_after_swipe_down_home")
    }
}
