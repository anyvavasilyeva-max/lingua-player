package io.github.linguaplayer.player

import io.github.linguaplayer.data.db.TypeFilter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class QueueBuilderTest {
    @Test
    fun sequentialKeepsSourceOrder() {
        val q = QueueBuilder.build(source(), PlaybackSettings(), Random(1))
        assertEquals(listOf(1L, 2, 3, 4, 5), q.map { it.entryId })
        assertTrue(q.all { it.sourceFirst })
        assertEquals("ko-KR", q[0].sourceLang)
        assertEquals("ru-RU", q[0].targetLang)
    }

    @Test
    fun shuffleIsPermutationAndDeterministicForSeed() {
        val many = (1L..50).map { entry(it, "단어$it", "слово $it") }
        val s = PlaybackSettings(order = PlayOrder.SHUFFLE)
        val a = QueueBuilder.build(source(many), s, Random(42)).map { it.entryId }
        val b = QueueBuilder.build(source(many), s, Random(42)).map { it.entryId }
        assertEquals(a, b)
        assertEquals((1L..50).toSet(), a.toSet())
        assertNotEquals((1L..50).toList(), a)
    }

    @Test
    fun shuffleKeepsCurrentEntryFirst() {
        val many = (1L..30).map { entry(it, "단어$it", "слово $it") }
        val q = QueueBuilder.build(source(many), PlaybackSettings(order = PlayOrder.SHUFFLE), Random(7), keepFirst = 17)
        assertEquals(17L, q.first().entryId)
        assertEquals(30, q.size)
    }

    @Test
    fun typeFilter() {
        val words = QueueBuilder.build(source(), PlaybackSettings(typeFilter = TypeFilter.WORDS), Random(1))
        assertEquals(listOf(1L, 2, 4), words.map { it.entryId })
        val phrases = QueueBuilder.build(source(), PlaybackSettings(typeFilter = TypeFilter.PHRASES), Random(1))
        assertEquals(listOf(3L, 5), phrases.map { it.entryId })
    }

    @Test
    fun directions() {
        val back = QueueBuilder.build(source(), PlaybackSettings(direction = Direction.TARGET_TO_SOURCE), Random(1))
        assertTrue(back.none { it.sourceFirst })
        val many = (1L..100).map { entry(it, "단어$it", "слово $it") }
        val rnd = QueueBuilder.build(source(many), PlaybackSettings(direction = Direction.RANDOM), Random(3))
        val forward = rnd.count { it.sourceFirst }
        assertTrue("random direction mixes both: $forward", forward in 25..75)
    }

    @Test
    fun entriesOfUnknownPairAreSkipped() {
        val q = QueueBuilder.build(source(SAMPLE + entry(9, "x", "y", pairId = 99)), PlaybackSettings(), Random(1))
        assertEquals(5, q.size)
    }
}
