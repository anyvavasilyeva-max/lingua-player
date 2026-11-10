package io.github.linguaplayer.ui

import android.net.Uri
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.linguaplayer.LinguaApp
import io.github.linguaplayer.data.db.EntryFilter
import io.github.linguaplayer.data.db.EntryType
import io.github.linguaplayer.data.repo.EntryDraft
import io.github.linguaplayer.ui.settings.DataMessage
import io.github.linguaplayer.ui.settings.DataViewModel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import java.io.File

/** Настройки → Данные: создать копию → изменить данные → восстановить (через ViewModel экрана). */
@RunWith(AndroidJUnit4::class)
class DataFlowTest {
    private val app = ApplicationProvider.getApplicationContext<LinguaApp>()
    private val c get() = app.container

    private fun waitFor(condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 15_000
        while (!condition()) {
            shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(50))
            check(System.currentTimeMillis() < deadline) { "timeout" }
            Thread.sleep(20)
        }
    }

    @Test
    fun backupAndRestoreFromSettings() {
        c.playerSpeechOverride = HoldingSpeech()
        val pair = runBlocking { c.languagePairs.getAll().first().id }
        runBlocking {
            c.entries.save(EntryDraft(pairId = pair, source = "포크", translation = "Вилка", type = EntryType.WORD, tagIds = emptySet()))
            c.playlists.create("Кухня", pair)
        }
        val vm = DataViewModel(c)
        val file = File(app.cacheDir, "copy.lpbackup")
        vm.createBackup(app, Uri.fromFile(file))
        waitFor { vm.state.value.message != null }
        assertEquals(DataMessage.BackupCreated, vm.state.value.message)
        assertTrue(file.length() > 0)
        vm.consumeMessage()

        // После копии добавили запись и удалили плейлист.
        runBlocking {
            c.entries.save(EntryDraft(pairId = pair, source = "숟가락", translation = "Ложка", type = EntryType.WORD, tagIds = emptySet()))
            c.playlists.observeAll(pair).let { it.first() }.forEach { c.playlists.delete(it.playlist.id) }
        }

        vm.inspectBackup(app, Uri.fromFile(file))
        waitFor { vm.state.value.pending != null }
        assertEquals(1, vm.state.value.pending!!.entries.size)
        vm.confirmRestore()
        waitFor { vm.state.value.message != null }
        assertEquals(DataMessage.Restored(1, 1), vm.state.value.message)

        val entries = runBlocking { c.entries.getEntries(EntryFilter()) }
        assertEquals(listOf("포크"), entries.map { it.source })
        assertNotNull(runBlocking { c.playlists.observeAll(pair).first() }.singleOrNull())
        assertFalse(c.player.state.value.hasQueue)
    }

    @Test
    fun notABackupIsReported() {
        val vm = DataViewModel(c)
        val file = File(app.cacheDir, "words.csv").apply { writeText("source,translation\n포크,Вилка\n") }
        vm.inspectBackup(app, Uri.fromFile(file))
        waitFor { vm.state.value.message != null }
        assertEquals(DataMessage.Failed(io.github.linguaplayer.R.string.data_err_not_backup), vm.state.value.message)
    }
}
