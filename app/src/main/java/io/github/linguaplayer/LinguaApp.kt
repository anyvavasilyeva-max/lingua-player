package io.github.linguaplayer

import android.app.Application

open class LinguaApp : Application() {
    lateinit var container: AppContainer
        private set

    /**
     * Подключать ли экран к сервису воспроизведения (MediaController).
     * В UI-тестах Robolectric отключается: его имитация bindService несовместима с Media3;
     * сам сервис проверяется отдельными тестами.
     */
    open val connectPlaybackService: Boolean get() = true

    /** Следить за изменениями для автокопии. В тестах включается явно, чтобы тесты не мешали друг другу. */
    open val startAutoBackup: Boolean get() = true

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        // Подписка на изменения данных и расписание автокопии.
        if (startAutoBackup) container.autoBackup.start(container.appScope)
    }
}
