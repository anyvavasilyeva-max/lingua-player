package io.github.linguaplayer.ui

import android.graphics.Bitmap
import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.printToString
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.linguaplayer.LinguaApp
import io.github.linguaplayer.data.db.EntryFilter
import io.github.linguaplayer.data.db.EntryType
import io.github.linguaplayer.ui.importer.ImportScreen
import io.github.linguaplayer.ui.importer.ImportViewModel
import io.github.linguaplayer.ui.theme.LinguaTheme
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** Мастер импорта: XLSX → выбор листа → тип → предпросмотр → импорт → отчёт. */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "ru-rRU")
class ImportFlowTest {
    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()
    private val ui by lazy { UiTestHelper(rule) }
    private fun waitText(t: String) = ui.waitText(t)
    private fun click(t: String) = ui.click(t)
    private fun shot(n: String) = ui.shot(n)
    private fun realWait(c: () -> Boolean) = ui.realWait(condition = c)





    @Test
    fun importsSecondSheetOfXlsx() {
        val bytes = javaClass.classLoader!!.getResourceAsStream("sample_ko_ru.xlsx")!!.readBytes()
        val file = File(rule.activity.cacheDir, "sample_ko_ru.xlsx").apply { writeBytes(bytes) }

        rule.setContent { LinguaTheme { ImportScreen(onBack = {}, onFinished = {}) } }
        waitText("Выбрать файл")
        shot("10_import_pick")
        // Встроенный пример заполнения файла.
        waitText("Как заполнить файл")
        rule.onAllNodesWithText("Сохранить пример CSV", useUnmergedTree = true)[0].performScrollTo()
        waitText("↑ несколько переводов и тегов")
        shot("10b_import_sample")
        val vm = ViewModelProvider(rule.activity)[ImportViewModel::class.java]
        rule.runOnUiThread<Unit> { vm.onFilePicked(rule.activity, Uri.fromFile(file)) }

        realWait { !vm.state.value.loading }
        assertEquals(null, vm.state.value.fileError)
        waitText("Файл: sample_ko_ru.xlsx")
        click("Слова (строк: 5)")
        click("Фразы (строк: 4)")
        // На листе «Фразы» нет столбца типа: по умолчанию выбрано «слово», меняем на «фраза».
        waitText("Тип для всех записей")
        click("фраза")
        shot("11_import_configure")
        click("Предпросмотр")

        waitText("Импортировать (3)")
        shot("12_import_preview")
        click("Импортировать (3)")
        waitText("Импорт завершён")
        shot("13_import_done")

        val c = (rule.activity.application as LinguaApp).container
        val entries = runBlocking { c.entries.getEntries(EntryFilter()) }
        assertEquals(3, entries.size)
        assertTrue(entries.all { it.type == EntryType.PHRASE })
        assertTrue(entries.any { it.source == "밥 먹었어요?" && it.translation == "Ты поел? (приветствие)" })
    }
}
