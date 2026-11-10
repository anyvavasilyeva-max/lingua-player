package io.github.linguaplayer.player

import io.github.linguaplayer.data.db.EntryEntity
import io.github.linguaplayer.data.db.EntryType
import io.github.linguaplayer.data.db.LanguagePairEntity
import io.github.linguaplayer.data.db.TypeFilter
import kotlin.random.Random

/** Один элемент очереди: запись + языки + выбранное направление. */
data class QueueItem(
    val entryId: Long,
    /** Языковая пара записи — при переключении пары чужая очередь сбрасывается. */
    val pairId: Long,
    val source: String,
    val translation: String,
    val type: EntryType,
    val sourceLang: String,
    val targetLang: String,
    /** true — сначала изучаемый язык, затем перевод. */
    val sourceFirst: Boolean,
)

/** Что воспроизводить: заголовок (имя плейлиста) и записи в исходном порядке. */
data class PlaybackSource(
    val title: String,
    val entries: List<EntryEntity>,
    val pairs: Map<Long, LanguagePairEntity>,
    /** id плейлиста; null — библиотека или выборка. */
    val playlistId: Long? = null,
)

object QueueBuilder {

    /**
     * Строит очередь: фильтр типа → порядок → направление.
     * @param keepFirst id записи, которая при перемешивании должна остаться первой (текущая).
     */
    fun build(
        source: PlaybackSource,
        settings: PlaybackSettings,
        random: Random,
        keepFirst: Long? = null,
    ): List<QueueItem> {
        val filtered = source.entries.filter {
            when (settings.typeFilter) {
                TypeFilter.ALL -> true
                TypeFilter.WORDS -> it.type == EntryType.WORD
                TypeFilter.PHRASES -> it.type == EntryType.PHRASE
            }
        }.filter { source.pairs.containsKey(it.pairId) }

        val ordered = when (settings.order) {
            PlayOrder.SEQUENTIAL -> filtered
            PlayOrder.SHUFFLE -> {
                val first = filtered.firstOrNull { it.id == keepFirst }
                val rest = filtered.filter { it.id != keepFirst }.shuffled(random)
                if (first != null) listOf(first) + rest else rest
            }
        }

        return ordered.map { e ->
            val pair = source.pairs.getValue(e.pairId)
            QueueItem(
                entryId = e.id,
                pairId = e.pairId,
                source = e.source,
                translation = e.translation,
                type = e.type,
                sourceLang = pair.sourceLang,
                targetLang = pair.targetLang,
                sourceFirst = when (settings.direction) {
                    Direction.SOURCE_TO_TARGET -> true
                    Direction.TARGET_TO_SOURCE -> false
                    Direction.RANDOM -> random.nextBoolean()
                },
            )
        }
    }
}
