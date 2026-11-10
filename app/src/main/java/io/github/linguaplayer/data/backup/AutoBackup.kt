package io.github.linguaplayer.data.backup

import android.content.Context
import android.net.Uri
import androidx.room.InvalidationTracker
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import io.github.linguaplayer.LinguaApp
import io.github.linguaplayer.data.db.AppDatabase
import io.github.linguaplayer.data.repo.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Автоматическая резервная копия (включается в «Настройки → Данные»).
 *
 * - Копия пишется в выбранный пользователем файл (например, на Google Диске — он доступен и на
 *   Android, и на iPhone) и дополнительно в 3 локальные копии внутри приложения.
 * - Когда: через [DEBOUNCE_MINUTES] минуты после последнего изменения данных (записи, теги,
 *   плейлисты) и раз в сутки. Выполняет WorkManager — даже если приложение закрыто.
 * - Защита: пустая библиотека (нет записей и плейлистов) не записывается — иначе на новом
 *   телефоне пустое приложение затёрло бы хорошую копию.
 * - Файл формируется целиком в памяти и записывается одним действием.
 */
class AutoBackup(
    private val context: Context,
    private val db: AppDatabase,
    private val backup: BackupRepository,
    private val settings: SettingsRepository,
    private val clock: () -> Long = System::currentTimeMillis,
    private val localDir: File = File(context.filesDir, "autobackup"),
) {
    private val mutex = Mutex()

    sealed interface Result {
        data object Done : Result
        data object Disabled : Result
        data object EmptyLibrary : Result
        data class Failed(val message: String) : Result
    }

    private val observer = object : InvalidationTracker.Observer(
        arrayOf("language_pairs", "entries", "tags", "entry_tags", "playlists", "playlist_entries", "smart_playlist_tags"),
    ) {
        override fun onInvalidated(tables: Set<String>) = scheduleSoon()
    }

    /** Подписка на изменения данных и ежедневное расписание. */
    fun start(scope: CoroutineScope) {
        db.invalidationTracker.addObserver(observer)
        scope.launch {
            settings.autoBackup.collect { s -> if (s.enabled) scheduleDaily() else cancelAll() }
        }
    }

    private fun workManager(): WorkManager? = runCatching { WorkManager.getInstance(context) }.getOrNull()

    fun scheduleSoon(delayMinutes: Long = DEBOUNCE_MINUTES) {
        val request = OneTimeWorkRequestBuilder<AutoBackupWorker>().setInitialDelay(delayMinutes, TimeUnit.MINUTES).build()
        // REPLACE: каждое новое изменение откладывает копию — получается «через N минут после последнего изменения».
        workManager()?.enqueueUniqueWork(WORK_SOON, ExistingWorkPolicy.REPLACE, request)
    }

    private fun scheduleDaily() {
        val request = PeriodicWorkRequestBuilder<AutoBackupWorker>(1, TimeUnit.DAYS).build()
        workManager()?.enqueueUniquePeriodicWork(WORK_DAILY, ExistingPeriodicWorkPolicy.KEEP, request)
    }

    private fun cancelAll() {
        workManager()?.cancelUniqueWork(WORK_SOON)
        workManager()?.cancelUniqueWork(WORK_DAILY)
    }

    /** Создать копию сейчас (вызывается WorkManager или кнопкой «Создать сейчас»). */
    suspend fun runNow(): Result = mutex.withLock {
        val s = settings.autoBackup.first()
        if (!s.enabled) return Result.Disabled
        val dao = db.backupDao()
        if (dao.entries().isEmpty() && dao.playlists().isEmpty()) return Result.EmptyLibrary
        val bytes = try {
            ByteArrayOutputStream().also { backup.export(it) }.toByteArray()
        } catch (e: Exception) {
            return fail(e)
        }
        val now = clock()
        withContext(Dispatchers.IO) { runCatching { writeLocal(bytes, now) } }
        val target = s.targetUri
        if (target != null) {
            try {
                withContext(Dispatchers.IO) {
                    context.contentResolver.openOutputStream(Uri.parse(target), "wt")!!.use { it.write(bytes) }
                }
            } catch (e: Exception) {
                return fail(e)
            }
        }
        settings.setAutoBackupResult(now, null)
        Result.Done
    }

    private suspend fun fail(e: Exception): Result {
        val msg = e.message ?: e.javaClass.simpleName
        settings.setAutoBackupResult(null, msg)
        return Result.Failed(msg)
    }

    /** Локальные копии: хранятся [LOCAL_KEEP] последних. */
    private fun writeLocal(bytes: ByteArray, now: Long) {
        localDir.mkdirs()
        val tmp = File(localDir, "auto_$now.lpbackup.tmp")
        tmp.writeBytes(bytes)
        tmp.renameTo(File(localDir, "auto_$now.lpbackup"))
        localCopies().drop(LOCAL_KEEP).forEach { it.delete() }
    }

    /** Локальные автокопии, новые первыми. */
    fun localCopies(): List<File> =
        localDir.listFiles { f -> f.name.startsWith("auto_") && f.name.endsWith(".lpbackup") }
            .orEmpty().sortedByDescending { it.name.removePrefix("auto_").removeSuffix(".lpbackup").toLongOrNull() ?: 0 }

    companion object {
        const val DEBOUNCE_MINUTES = 2L
        const val LOCAL_KEEP = 3
        const val WORK_SOON = "auto-backup-soon"
        const val WORK_DAILY = "auto-backup-daily"
    }
}

class AutoBackupWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val c = (applicationContext as LinguaApp).container
        return when (c.autoBackup.runNow()) {
            is AutoBackup.Result.Failed -> if (runAttemptCount < 3) Result.retry() else Result.failure()
            else -> Result.success()
        }
    }
}
