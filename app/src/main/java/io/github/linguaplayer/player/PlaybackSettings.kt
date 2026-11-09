package io.github.linguaplayer.player

import io.github.linguaplayer.data.db.TypeFilter

/** Направление: что звучит первым. */
enum class Direction(val code: String) {
    /** Изучаемый язык → перевод (포크 → Вилка). */
    SOURCE_TO_TARGET("source_first"),
    /** Перевод → изучаемый язык (Вилка → 포크). */
    TARGET_TO_SOURCE("target_first"),
    /** Для каждой записи направление выбирается случайно. */
    RANDOM("random");

    companion object {
        fun fromCode(c: String?) = entries.firstOrNull { it.code == c } ?: SOURCE_TO_TARGET
    }
}

enum class PlayOrder(val code: String) {
    SEQUENTIAL("sequential"), SHUFFLE("shuffle");

    companion object {
        fun fromCode(c: String?) = entries.firstOrNull { it.code == c } ?: SEQUENTIAL
    }
}

/** Повтор, как в музыкальном плеере. */
enum class RepeatMode(val code: String) {
    /** Доиграть очередь и остановиться. */
    OFF("off"),
    /** Зациклить список: после последней записи — снова первая. */
    ALL("all"),
    /** Зациклить запись: текущая запись повторяется, пока не нажать ⏭. */
    ONE("one");

    fun next(): RepeatMode = when (this) { OFF -> ALL; ALL -> ONE; ONE -> OFF }

    companion object {
        fun fromCode(c: String?) = entries.firstOrNull { it.code == c } ?: OFF
    }
}

/** Все параметры воспроизведения. Хранятся в DataStore (SettingsRepository). */
data class PlaybackSettings(
    val direction: Direction = Direction.SOURCE_TO_TARGET,
    val typeFilter: TypeFilter = TypeFilter.ALL,
    val order: PlayOrder = PlayOrder.SEQUENTIAL,
    /** Пауза между словом и переводом, секунды. */
    val pauseSeconds: Int = 2,
    /** Сколько раз произносится текст на изучаемом языке. */
    val repeats: Int = 1,
    /** Скорость речи изучаемого языка (1.0 — обычная). */
    val sourceRate: Float = 1f,
    /** Скорость речи языка перевода. */
    val targetRate: Float = 1f,
    /** false — режим без перевода: звучит только изучаемый язык. */
    val translationEnabled: Boolean = true,
    val repeatMode: RepeatMode = RepeatMode.OFF,
    /** Повторы с замедлением: 2-й (и каждый чётный) повтор — на одну ступень скорости медленнее. */
    val slowRepeat: Boolean = false,
    /** Скрывать ответ на карточке плеера, пока не нажать «глазик». */
    val hideAnswer: Boolean = false,
) {
    /** Параметры, при изменении которых нужно перестроить очередь. */
    fun queueKey() = Triple(direction, typeFilter, order)

    companion object {
        val PAUSES = listOf(0, 2, 5, 10)
        val REPEATS = listOf(1, 2, 3)
        val RATES = listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f)

        /** Скорость на одну ступень медленнее (минимум — самая медленная ступень). */
        fun slower(rate: Float): Float = RATES.lastOrNull { it < rate - 0.001f } ?: RATES.first()
    }
}
