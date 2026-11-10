package io.github.linguaplayer.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.performTextReplacement
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.linguaplayer.LinguaApp
import io.github.linguaplayer.MainActivity
import io.github.linguaplayer.data.db.EntryType
import io.github.linguaplayer.data.repo.DEFAULT_SEED_COLOR
import io.github.linguaplayer.data.repo.EntryDraft
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** Обложки плейлистов (плитка, своя картинка), недавние на главной, выбор цвета оформления. */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "ru-rRU")
class DesignTest {
    @get:Rule
    val rule = createEmptyComposeRule()
    private val ui by lazy { UiTestHelper(rule) }
    private lateinit var scenario: ActivityScenario<MainActivity>
    private val app get() = ApplicationProvider.getApplicationContext<LinguaApp>()
    private val c get() = app.container

    @Before
    fun setUp() {
        c.playerSpeechOverride = HoldingSpeech()
        runBlocking {
            c.settings.setSeedColor(DEFAULT_SEED_COLOR)
            c.settings.setPlaylistsGrid(true)
            val pair = c.languagePairs.getAll().first().id
            val e = c.entries.save(EntryDraft(pairId = pair, source = "포크", translation = "Вилка", type = EntryType.WORD, tagIds = emptySet()))
            val names = listOf("Существительные", "Глаголы", "Прилагательные", "Числа", "20 фраз", "Повторить")
            val ids = names.map { c.playlists.create(it, pair) }
            ids.forEach { c.playlists.addEntries(it, listOf(e)); c.playlists.markOpened(it) }
            // Своя обложка у первого плейлиста — картинка-градиент.
            val bmp = Bitmap.createBitmap(400, 400, Bitmap.Config.ARGB_8888)
            Canvas(bmp).drawRect(0f, 0f, 400f, 400f, Paint().apply {
                shader = LinearGradient(0f, 0f, 400f, 400f, 0xFF0F2429.toInt(), 0xFFBF3737.toInt(), Shader.TileMode.CLAMP)
            })
            val file = File(app.filesDir, "covers/test.png").apply { parentFile?.mkdirs() }
            file.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
            c.playlists.setCover(ids[0], file.absolutePath)
        }
        scenario = ActivityScenario.launch(MainActivity::class.java)
    }

    @After
    fun tearDown() = scenario.close()

    @Test
    fun coversGridHomeAndColorPicker() {
        ui.waitText("Что послушаем?")
        ui.waitText("Повторить") // недавно открытые — плитками
        ui.shot("30_home_recent_tiles")

        ui.click("Плейлисты")
        ui.waitText("Существительные")
        ui.shot("31_playlists_grid")
        ui.clickDesc("Списком")
        ui.realWait { runBlocking { !c.settings.playlistsGrid.first() } }
        ui.shot("32_playlists_list")
        ui.clickDesc("Плиткой")
        ui.realWait { runBlocking { c.settings.playlistsGrid.first() } }

        ui.click("Глаголы")
        ui.waitText("1 запись")
        ui.shot("33_playlist_detail_cover")
        ui.clickDesc("Назад")

        // Цвет оформления: ввод HEX.
        ui.click("Настройки")
        ui.click("Цвет оформления")
        ui.waitText("HEX")
        rule.onNode(hasSetTextAction() and hasAnyAncestor(isDialog())).performTextReplacement("#BF3737")
        ui.shot("34_color_picker")
        ui.click("Сохранить")
        ui.realWait { runBlocking { c.settings.seedColor.first() } == 0xFFBF3737.toInt() }
        ui.click("Плейлисты")
        ui.waitText("Глаголы")
        ui.shot("35_playlists_red")
        assertEquals(0xFFBF3737.toInt(), runBlocking { c.settings.seedColor.first() })
    }
}
