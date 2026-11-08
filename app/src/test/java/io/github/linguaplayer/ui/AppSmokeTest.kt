package io.github.linguaplayer.ui

import android.graphics.Bitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.isDialog
import kotlinx.coroutines.flow.first
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.longClick
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
    private val ui by lazy { UiTestHelper(rule) }
    private fun waitText(t: String) = ui.waitText(t)
    private fun click(t: String) = ui.click(t)
    private fun shot(n: String) = ui.shot(n)
    private fun realWait(c: () -> Boolean) = ui.realWait(condition = c)




    @Test
    fun addEntryCreateTagAndPlaylist() {
        waitText("Что послушаем?")
        shot("01_home_empty")

        // Плейлист
        click("Плейлисты")
        click("Новый плейлист")
        rule.onNode(hasSetTextAction()).performTextInput("Кухня")
        click("Сохранить")
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

    @Test
    fun massTaggingAndSmartPlaylist() {
        val c = (rule.activity.application as io.github.linguaplayer.LinguaApp).container
        kotlinx.coroutines.runBlocking {
            val pair = c.languagePairs.getAll().first().id
            listOf("포크" to "Вилка", "숟가락" to "Ложка").forEach { (s, t) ->
                c.entries.save(io.github.linguaplayer.data.repo.EntryDraft(pairId = pair, source = s, translation = t, type = io.github.linguaplayer.data.db.EntryType.WORD, tagIds = emptySet()))
            }
            c.entries.save(io.github.linguaplayer.data.repo.EntryDraft(pairId = pair, source = "설거지를 하다", translation = "Мыть посуду", type = io.github.linguaplayer.data.db.EntryType.PHRASE, tagIds = emptySet()))
        }
        click("Библиотека")
        waitText("설거지를 하다")
        rule.onAllNodesWithText("설거지를 하다", useUnmergedTree = true)[0].performTouchInput { longClick() }
        waitText("Выбрано: 1")
        click("숟가락") // в режиме выбора обычное нажатие добавляет запись
        waitText("Выбрано: 2")
        rule.onNodeWithContentDescription("Выбрать все").performClick()
        waitText("Выбрано: 3")
        shot("06_library_selection")
        rule.onNodeWithContentDescription("Добавить теги").performClick()
        rule.onNode(hasSetTextAction() and hasAnyAncestor(isDialog())).performTextInput("кухня")
        rule.onNodeWithContentDescription("Создать").performClick()
        // Ждём строку нового тега в списке диалога (не текст в поле ввода).
        realWait {
            rule.onAllNodes(hasText("кухня") and hasAnyAncestor(isDialog()) and !hasSetTextAction(), useUnmergedTree = true)
                .fetchSemanticsNodes().isNotEmpty()
        }
        click("Сохранить")
        realWait {
            kotlinx.coroutines.runBlocking {
                c.tags.observeWithCounts().first().any { it.tag.name == "кухня" && it.usageCount == 3 }
            }
        }
        waitText("#кухня")

        // Умный плейлист «кухня — фразы»
        click("Плейлисты")
        click("Новый плейлист")
        rule.onNode(hasSetTextAction()).performTextInput("Кухня — фразы")
        click("Умный")
        click("Фразы")
        click("#кухня")
        shot("07_smart_editor")
        click("Сохранить")
        waitText("설거지를 하다")
        shot("08_smart_playlist")
        val count = rule.onAllNodesWithText("포크", useUnmergedTree = true).fetchSemanticsNodes().size
        org.junit.Assert.assertEquals(0, count)
    }
}

