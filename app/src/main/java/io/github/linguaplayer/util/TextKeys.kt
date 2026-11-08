package io.github.linguaplayer.util

import java.text.Normalizer
import java.util.Locale

/**
 * Нормализация текста для поиска и поиска дубликатов.
 *
 * - Unicode NFC: корейский текст может прийти как в виде готовых слогов (포),
 *   так и разложенным на чамо (ᄑ + ᅩ) — после NFC они совпадают.
 * - Обрезка и схлопывание пробелов.
 * - Нижний регистр (Locale.ROOT), чтобы «Вилка» и «вилка» считались одинаковыми.
 */
object TextKeys {
    private val whitespace = Regex("\\s+")

    fun key(text: String): String =
        Normalizer.normalize(text, Normalizer.Form.NFC)
            .trim()
            .replace(whitespace, " ")
            .lowercase(Locale.ROOT)

    /** Чистит отображаемый текст, сохраняя регистр. */
    fun clean(text: String): String =
        Normalizer.normalize(text, Normalizer.Form.NFC).trim().replace(whitespace, " ")
}
