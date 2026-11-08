package io.github.linguaplayer.ui

import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
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
}
