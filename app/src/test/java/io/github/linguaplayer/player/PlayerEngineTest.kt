package io.github.linguaplayer.player

import io.github.linguaplayer.data.db.TypeFilter
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

@OptIn(ExperimentalCoroutinesApi::class)
class PlayerEngineTest {

    private fun TestScope.engine(fake: FakeSpeech, settings: PlaybackSettings = PlaybackSettings()) =
        PlayerEngine(backgroundScope, fake, Random(1)).also { it.updateSettings(settings) }

    /** advanceUntilIdle не ждёт backgroundScope — продвигаем виртуальное время явно. */
    private fun TestScope.finish() { advanceTimeBy(30 * 60_000L); runCurrent() }

    /** Никакие две фразы не пересекаются во времени. */
    private fun assertNoOverlap(fake: FakeSpeech) {
        val done = fake.log.filter { it.end != null }
        for ((a, b) in done.zipWithNext()) assertTrue("overlap: $a / $b", a.end!! <= b.start)
    }

    @Test
    fun playsWholeQueueSequentiallyWithoutOverlap() = runTest {
        val fake = FakeSpeech(this)
        val p = engine(fake, PlaybackSettings(pauseSeconds = 2))
        p.load(source())
        finish()
        assertEquals(
            listOf("포크", "Вилка", "숟가락", "Ложка", "설거지를 하다", "Мыть посуду", "젓가락", "Палочки", "어떻게 해야 할까요?", "Что мне делать?"),
            fake.texts,
        )
        assertNoOverlap(fake)
        // слово 1 с → пауза 2 с → перевод 1 с → интервал 1,2 с: следующая запись через 5,2 с
        assertEquals(0L, fake.log[0].start)
        assertEquals(3000L, fake.log[1].start)
        assertEquals(5200L, fake.log[2].start)
        val st = p.state.value
        assertTrue(st.ended)
        assertFalse(st.isPlaying)
        assertEquals(4, st.index)
    }

    @Test
    fun reverseDirectionAndRepeats() = runTest {
        val fake = FakeSpeech(this)
        val p = engine(fake, PlaybackSettings(direction = Direction.TARGET_TO_SOURCE, repeats = 2, typeFilter = TypeFilter.PHRASES))
        p.load(source())
        finish()
        assertEquals(
            listOf("Мыть посуду", "설거지를 하다", "설거지를 하다", "Что мне делать?", "어떻게 해야 할까요?", "어떻게 해야 할까요?"),
            fake.texts,
        )
        assertEquals(listOf("ru-RU", "ko-KR", "ko-KR"), fake.log.take(3).map { it.lang })
        assertNoOverlap(fake)
    }

    @Test
    fun ratesPerLanguage() = runTest {
        val fake = FakeSpeech(this)
        val p = engine(fake, PlaybackSettings(sourceRate = 0.75f, targetRate = 1.25f))
        p.load(source(SAMPLE.take(1)))
        finish()
        assertEquals(listOf(0.75f, 1.25f), fake.log.map { it.rate })
    }

    @Test
    fun pauseStopsSpeechAndResumeRestartsCurrentEntry() = runTest {
        val fake = FakeSpeech(this)
        val p = engine(fake)
        p.load(source())
        advanceTimeBy(3500) // идёт перевод «Вилка»
        runCurrent()
        assertEquals(Part.TRANSLATION, p.state.value.speaking)
        p.pause()
        advanceTimeBy(60_000)
        runCurrent()
        assertFalse(p.state.value.isPlaying)
        assertEquals(listOf("포크", "Вилка"), fake.texts) // за минуту паузы — ничего
        assertNull(fake.log[1].end) // фраза оборвана
        p.play()
        advanceTimeBy(100)
        runCurrent()
        assertEquals("포크", fake.texts.last()) // запись начинается сначала
        assertEquals(0, p.state.value.index)
    }

    @Test
    fun nextAndPreviousInterruptWithoutOverlap() = runTest {
        val fake = FakeSpeech(this)
        val p = engine(fake)
        p.load(source())
        advanceTimeBy(500); runCurrent()
        p.next()
        advanceTimeBy(500); runCurrent()
        p.next()
        advanceTimeBy(500); runCurrent()
        p.previous()
        advanceTimeBy(100); runCurrent()
        assertEquals(listOf("포크", "숟가락", "설거지를 하다", "숟가락"), fake.texts)
        assertEquals(1, p.state.value.index)
        // Прерванные фразы не завершились; новая началась не раньше обрыва предыдущей.
        for ((a, b) in fake.log.zipWithNext()) assertTrue(a.end == null || a.end!! <= b.start)
        assertTrue(fake.log.zipWithNext().all { (a, b) -> a.start <= b.start })
    }

    @Test
    fun nextWhilePausedOnlyMovesPosition() = runTest {
        val fake = FakeSpeech(this)
        val p = engine(fake)
        p.load(source(), autoplay = false)
        p.next(); p.next()
        finish()
        assertTrue(fake.log.isEmpty())
        assertEquals(2, p.state.value.index)
        p.next(); p.next(); p.next() // за концом очереди не уходит
        assertEquals(4, p.state.value.index)
    }

    @Test
    fun changingDirectionMidPlayKeepsCurrentEntry() = runTest {
        val fake = FakeSpeech(this)
        val p = engine(fake)
        p.load(source())
        advanceTimeBy(5300); runCurrent() // вторая запись (숟가락)
        assertEquals(1, p.state.value.index)
        p.updateSettings(p.state.value.settings.copy(direction = Direction.TARGET_TO_SOURCE))
        advanceTimeBy(100); runCurrent()
        assertEquals(1, p.state.value.index)
        assertEquals("Ложка", fake.texts.last())
    }

    @Test
    fun enablingShuffleKeepsCurrentEntryCurrent() = runTest {
        val fake = FakeSpeech(this)
        val p = engine(fake)
        p.load(source(), startEntryId = 3, autoplay = false)
        assertEquals(2, p.state.value.index)
        p.updateSettings(p.state.value.settings.copy(order = PlayOrder.SHUFFLE))
        assertEquals(3L, p.state.value.current!!.entryId)
        assertEquals(0, p.state.value.index)
        assertEquals(5, p.state.value.queue.size)
    }

    @Test
    fun filterChangeThatRemovesCurrentFallsBackToStart() = runTest {
        val fake = FakeSpeech(this)
        val p = engine(fake)
        p.load(source(), startEntryId = 1, autoplay = false)
        p.updateSettings(p.state.value.settings.copy(typeFilter = TypeFilter.PHRASES))
        assertEquals(3L, p.state.value.current!!.entryId)
    }

    @Test
    fun missingVoiceStopsWithClearError() = runTest {
        val fake = FakeSpeech(this).apply { unavailable = setOf("ko-KR") }
        val p = engine(fake)
        p.load(source())
        finish()
        assertEquals(PlayerError.LanguageUnavailable("ko-KR"), p.state.value.error)
        assertFalse(p.state.value.isPlaying)
    }

    @Test
    fun engineInitFailure() = runTest {
        val fake = FakeSpeech(this).apply { ready = false }
        val p = engine(fake)
        p.load(source())
        finish()
        assertEquals(PlayerError.EngineUnavailable, p.state.value.error)
    }

    @Test
    fun repeatedErrorsStopPlayback() = runTest {
        val fake = FakeSpeech(this).apply { failAll = true }
        val p = engine(fake)
        p.load(source())
        finish()
        assertEquals(PlayerError.RepeatedFailures, p.state.value.error)
    }

    @Test
    fun noTranslationMode() = runTest {
        val fake = FakeSpeech(this)
        val p = engine(fake, PlaybackSettings(translationEnabled = false))
        p.load(source(SAMPLE.take(2)))
        finish()
        assertEquals(listOf("포크", "숟가락"), fake.texts)
    }

    @Test
    fun playAfterEndStartsOver() = runTest {
        val fake = FakeSpeech(this)
        val p = engine(fake)
        p.load(source(SAMPLE.take(2)))
        finish()
        assertTrue(p.state.value.ended)
        p.play()
        advanceTimeBy(100); runCurrent()
        assertEquals(0, p.state.value.index)
        assertEquals("포크", fake.texts.last())
    }

    @Test
    fun repeatAllLoopsList() = runTest {
        val fake = FakeSpeech(this)
        val p = engine(fake, PlaybackSettings(pauseSeconds = 0, repeatMode = RepeatMode.ALL))
        p.load(source(SAMPLE.take(2)))
        // одна запись = 1 + 1 + 1,2 = 3,2 с; за 10 с — 3 записи
        advanceTimeBy(10_000); runCurrent()
        assertEquals(listOf("포크", "Вилка", "숟가락", "Ложка", "포크", "Вилка", "숟가락"), fake.texts)
        assertTrue(p.state.value.isPlaying)
        assertFalse(p.state.value.ended)
    }

    @Test
    fun repeatOneLoopsCurrentEntryUntilNext() = runTest {
        val fake = FakeSpeech(this)
        val p = engine(fake, PlaybackSettings(pauseSeconds = 0, repeatMode = RepeatMode.ONE))
        p.load(source(SAMPLE.take(3)))
        advanceTimeBy(7_000); runCurrent()
        assertEquals(listOf("포크", "Вилка", "포크", "Вилка", "포크"), fake.texts)
        assertEquals(0, p.state.value.index)
        p.next()
        advanceTimeBy(100); runCurrent()
        assertEquals("숟가락", fake.texts.last())
    }

    @Test
    fun repeatAllWrapsNextAndPrevious() = runTest {
        val fake = FakeSpeech(this)
        val p = engine(fake, PlaybackSettings(repeatMode = RepeatMode.ALL))
        p.load(source(), autoplay = false)
        p.previous()
        assertEquals(4, p.state.value.index)
        p.next()
        assertEquals(0, p.state.value.index)
        p.updateSettings(p.state.value.settings.copy(repeatMode = RepeatMode.OFF))
        p.previous()
        assertEquals(0, p.state.value.index)
        assertFalse(p.hasPrevious())
    }

    @Test
    fun slowRepeatRatesReachEngine() = runTest {
        val fake = FakeSpeech(this)
        val p = engine(fake, PlaybackSettings(repeats = 3, slowRepeat = true, sourceRate = 1.25f))
        p.load(source(SAMPLE.take(1)))
        finish()
        assertEquals(listOf(1.25f, 1f, 1.25f, 1f), fake.log.map { it.rate }) // 3 повтора + перевод 1×
    }
}
