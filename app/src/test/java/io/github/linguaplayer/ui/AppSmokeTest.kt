package io.github.linguaplayer.ui

import android.graphics.Bitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.linguaplayer.MainActivity
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * Дымовой тест интерфейса: запускает настоящий MainActivity (с реальной БД Room)
 * и проходит основной сценарий этапа 1. Скриншоты сохраняются, если задано
 * свойство -Dlingua.screenshots=<папка>.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "ru-rRU")
class AppSmokeTest {
    @get:Rule
    val rule = createAndroidComposeRule<MainActivity>()

    private fun shot(name: String) {
        val dir = System.getProperty("lingua.screenshots") ?: return
        rule.waitForIdle()
        val bmp = rule.onRoot().captureToImage().asAndroidBitmap()
        File(dir).mkdirs()
        File(dir, "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    /** Room работает в фоновых потоках, поэтому ждём появления текста явно. */
    private fun waitText(text: String) {
        rule.waitUntil(10_000) { rule.onAllNodesWithText(text, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
    }

    /** Клик по элементу с текстом (после того как он появился). */
    private fun click(text: String) {
        waitText(text)
        rule.onAllNodesWithText(text, useUnmergedTree = true)[0].performClick()
    }

    @Test
    fun addEntryCreateTagAndPlaylist() {
        waitText("Что послушаем?")
        shot("01_home_empty")

        // Плейлист
        click("Плейлисты")
        click("Новый плейлист")
        rule.onNode(hasSetTextAction()).performTextInput("Кухня")
        click("Создать")
        rule.waitForIdle()
        waitText("Кухня")

        // Запись с новым тегом и плейлистом
        click("Библиотека")
        click("Новая запись")
        waitText("Слово или фраза (Корейский)")
        rule.onNodeWithText("Слово или фраза (Корейский)").performTextInput("포크")
        rule.onNodeWithText("Перевод (Русский)").performTextInput("Вилка")
        click("Новый тег")
        rule.onAllNodesWithText("Название")[0].performTextInput("TOPIK 2급")
        click("Создать")
        waitText("TOPIK 2급")
        rule.onNodeWithText("Кухня").performScrollTo().performClick()
        shot("02_entry_editor")
        rule.onNodeWithContentDescription("Сохранить").performClick()

        waitText("포크")
        waitText("Вилка")
        waitText("#TOPIK 2급")
        shot("03_library")

        // Запись видна в плейлисте
        click("Плейлисты")
        click("Кухня")
        rule.waitForIdle()
        waitText("포크")
        shot("04_playlist")

        rule.onNodeWithContentDescription("Назад").performClick()
        click("Настройки")
        waitText("Корейский → Русский")
        shot("05_settings")
    }
}
