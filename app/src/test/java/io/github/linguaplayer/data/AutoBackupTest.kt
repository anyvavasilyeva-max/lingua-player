package io.github.linguaplayer.data

import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.ListenableWorker
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.testing.WorkManagerTestInitHelper
import io.github.linguaplayer.LinguaApp
import io.github.linguaplayer.data.backup.AutoBackup
import io.github.linguaplayer.data.backup.AutoBackupWorker
import io.github.linguaplayer.data.backup.BackupRepository
import io.github.linguaplayer.data.db.EntryType
import io.github.linguaplayer.data.repo.EntryDraft
import io.github.linguaplayer.data.repo.SettingsRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.xmlpull.v1.XmlPullParser
import java.io.File

@RunWith(AndroidJUnit4::class)
class AutoBackupTest {
    private val context = ApplicationProvider.getApplicationContext<LinguaApp>()
    private val settings = SettingsRepository(context)
    private val t = TestDb()
    private var time = 1_000_000L
    private val backupRepo = BackupRepository(t.db, settings, File(context.filesDir, "covers"), "test")
    private val localDir = File(context.cacheDir, "autobackup-test-${System.nanoTime()}")
    private val auto = AutoBackup(context, t.db, backupRepo, settings, { time++ }, localDir)
    // Свой файл у каждого теста: фоновая задача соседнего теста не должна писать в него.
    private val target = File(context.cacheDir, "drive-${System.nanoTime()}/lingua_player_autobackup.lpbackup").apply { parentFile?.mkdirs() }

    @Before
    fun setUp() {
        runBlocking {
            settings.setAutoBackupEnabled(false)
            settings.setAutoBackupTarget(Uri.fromFile(target).toString(), "lingua_player_autobackup.lpbackup")
            settings.setAutoBackupResult(null, null)
        }
        localDir.deleteRecursively()
    }

    @After
    fun tearDown() {
        runCatching { WorkManager.getInstance(context).cancelAllWork().result.get() }
        // Подписки контейнера живут дольше теста (Robolectric создаёт новое Application на каждый тест,
        // а старое не уничтожается) — останавливаем их, чтобы они не писали в файлы следующего теста.
        context.container.appScope.coroutineContext[kotlinx.coroutines.Job]?.cancel()
        runBlocking { settings.setAutoBackupEnabled(false) }
        t.close()
    }

    private suspend fun addWord(s: String) {
        t.entries.save(EntryDraft(pairId = t.pairs.getAll().first().id, source = s, translation = "x", type = EntryType.WORD, tagIds = emptySet()))
    }

    @Test
    fun disabledDoesNothing() = runTest {
        addWord("포크")
        assertEquals(AutoBackup.Result.Disabled, auto.runNow())
        assertTrue(!target.exists() || target.length() == 0L)
    }

    @Test
    fun emptyLibraryNeverOverwritesExistingBackup() = runTest {
        target.writeText("ХОРОШАЯ КОПИЯ")
        settings.setAutoBackupEnabled(true)
        assertEquals(AutoBackup.Result.EmptyLibrary, auto.runNow())
        assertEquals("ХОРОШАЯ КОПИЯ", target.readText())
    }

    @Test
    fun writesRestorableBackupAndKeepsThreeLocalCopies() = runTest {
        settings.setAutoBackupEnabled(true)
        addWord("포크"); addWord("숟가락")
        assertEquals(AutoBackup.Result.Done, auto.runNow())
        val data = backupRepo.inspect(target.inputStream())
        assertEquals(setOf("포크", "숟가락"), data.entries.map { it.source }.toSet())
        assertNotNull(settings.autoBackup.first().lastAt)

        repeat(4) { addWord("단어$it"); auto.runNow() }
        val local = auto.localCopies()
        assertEquals(AutoBackup.LOCAL_KEEP, local.size)
        assertEquals(6, backupRepo.inspect(local.first().inputStream()).entries.size) // самая новая — первая
        assertEquals(6, backupRepo.inspect(target.inputStream()).entries.size)
    }

    @Test
    fun writeErrorIsReportedAndLocalCopyStillSaved() = runTest {
        settings.setAutoBackupEnabled(true)
        settings.setAutoBackupTarget(Uri.fromFile(File("/nonexistent-dir/x.lpbackup")).toString(), "x")
        addWord("포크")
        val r = auto.runNow()
        assertTrue(r is AutoBackup.Result.Failed)
        assertNotNull(settings.autoBackup.first().lastError)
        assertEquals(1, auto.localCopies().size)
    }

    @Test
    fun dataChangeSchedulesBackupAndWorkerRuns() {
        WorkManagerTestInitHelper.initializeTestWorkManager(context)
        val c = context.container
        runBlocking {
            settings.setAutoBackupEnabled(true)
            c.autoBackup.start(c.appScope) // как в LinguaApp.onCreate
            c.entries.save(EntryDraft(pairId = c.languagePairs.getAll().first().id, source = "포크", translation = "Вилка", type = EntryType.WORD, tagIds = emptySet()))
        }
        val deadline = System.currentTimeMillis() + 5_000
        var infos: List<WorkInfo>
        do {
            infos = WorkManager.getInstance(context).getWorkInfosForUniqueWork(AutoBackup.WORK_SOON).get()
            Thread.sleep(50)
        } while (infos.isEmpty() && System.currentTimeMillis() < deadline)
        assertEquals(WorkInfo.State.ENQUEUED, infos.single().state)

        val result = runBlocking { TestListenableWorkerBuilder<AutoBackupWorker>(context).build().doWork() }
        assertEquals(ListenableWorker.Result.success(), result)
        assertTrue(target.length() > 0)
    }

    @Test
    fun systemBackupIncludesWholeDatabaseAndCovers() {
        for (res in listOf(io.github.linguaplayer.R.xml.backup_rules, io.github.linguaplayer.R.xml.data_extraction_rules)) {
            val parser = context.resources.getXml(res)
            val includes = mutableListOf<String>()
            while (parser.next() != XmlPullParser.END_DOCUMENT) {
                if (parser.eventType == XmlPullParser.START_TAG && parser.name == "include") {
                    includes += parser.getAttributeValue(null, "domain") + ":" + parser.getAttributeValue(null, "path")
                }
            }
            assertTrue(includes.toString(), "database:." in includes) // вместе с журналом -wal
            assertTrue("file:covers/" in includes)
            assertTrue("file:datastore/" in includes)
            assertNull(includes.firstOrNull { it.contains("autobackup") })
        }
    }
}
