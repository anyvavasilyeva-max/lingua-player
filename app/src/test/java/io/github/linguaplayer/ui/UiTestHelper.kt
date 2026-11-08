package io.github.linguaplayer.ui

import android.graphics.Bitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.printToString
import java.io.File

/**
 * Общие действия UI-тестов.
 *
 * Ожидание идёт по реальным часам: Room и чтение файлов работают в настоящих фоновых потоках,
 * а таймаут стандартного waitUntil считается по виртуальным часам теста.
 * Скриншоты сохраняются, если задано свойство -Dlingua.screenshots=<папка>
 * (./gradlew testDebugUnitTest -PscreenshotsDir=...).
 */
class UiTestHelper(private val rule: ComposeTestRule) {

    fun realWait(timeoutMs: Long = 20_000, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (true) {
            rule.waitForIdle()
            if (condition()) return
            if (System.currentTimeMillis() > deadline) {
                shot("zz_timeout")
                println(rule.onAllNodes(isRoot(), useUnmergedTree = true).printToString())
                error("condition not met in $timeoutMs ms")
            }
            Thread.sleep(50)
        }
    }

    private fun exists(text: String) =
        rule.onAllNodesWithText(text, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()

    /** Ждёт текст; если он в ещё не показанной части прокручиваемого списка — прокручивает к нему. */
    fun waitText(text: String) = realWait {
        if (exists(text)) return@realWait true
        if (rule.onAllNodes(hasScrollToNodeAction()).fetchSemanticsNodes().isNotEmpty()) {
            runCatching { rule.onAllNodes(hasScrollToNodeAction())[0].performScrollToNode(hasText(text)) }
        }
        false
    }

    /** Ждёт элемент с описанием (иконка-кнопка) и нажимает его. */
    fun clickDesc(description: String) {
        realWait { rule.onAllNodesWithContentDescription(description).fetchSemanticsNodes().isNotEmpty() }
        rule.onAllNodesWithContentDescription(description)[0].performClick()
    }

    fun click(text: String) {
        waitText(text)
        val node = rule.onAllNodesWithText(text, useUnmergedTree = true)[0]
        runCatching { node.performScrollTo() }
        node.performClick()
    }

    fun shot(name: String) {
        val dir = System.getProperty("lingua.screenshots") ?: return
        rule.waitForIdle()
        // При открытом диалоге корней два — снимаем верхний (диалог).
        val roots = rule.onAllNodes(isRoot())
        val count = roots.fetchSemanticsNodes().size
        val bmp = roots[count - 1].captureToImage().asAndroidBitmap()
        File(dir).mkdirs()
        File(dir, "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
