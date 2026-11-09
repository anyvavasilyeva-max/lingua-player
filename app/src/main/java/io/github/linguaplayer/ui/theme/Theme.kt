package io.github.linguaplayer.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import io.github.linguaplayer.data.repo.DEFAULT_SEED_COLOR

/**
 * Цветовая схема из одного выбранного пользователем цвета.
 *
 * Выбранный цвет используется как есть для «фирменных» поверхностей (обложки плейлистов,
 * контейнеры). Для текста и значков (primary) он при необходимости затемняется (светлая тема)
 * или осветляется (тёмная тема), пока контраст с фоном не станет ≥ 4,5:1 (WCAG AA) —
 * поэтому интерфейс читаем при любом выбранном цвете, даже светло-жёлтом.
 */
object SeedScheme {
    private val LightBg = Color(0xFFF8F9FC)
    private val DarkBg = Color(0xFF111318)

    fun contrast(a: Color, b: Color): Float {
        val la = a.luminance() + 0.05f
        val lb = b.luminance() + 0.05f
        return if (la > lb) la / lb else lb / la
    }

    /** Чёрный или белый текст поверх цвета — что контрастнее. */
    fun onColor(c: Color): Color = if (contrast(c, Color.White) >= contrast(c, Color(0xFF0F1A1C))) Color.White else Color(0xFF0F1A1C)

    /**
     * Цвет крупного жирного названия на обложке: белый, если он хоть немного читается
     * (контраст ≥ 2,4 — для крупного текста этого достаточно визуально), иначе тёмный.
     */
    fun onCover(c: Color): Color = if (contrast(c, Color.White) >= 2.4f) Color.White else Color(0xFF0F1A1C)

    /** Сдвигает цвет к [target], пока контраст с [bg] не достигнет [min]. */
    private fun ensureContrast(c: Color, bg: Color, target: Color, min: Float = 4.5f): Color {
        var t = 0f
        var r = c
        while (contrast(r, bg) < min && t < 1f) {
            t += 0.05f
            r = lerp(c, target, t)
        }
        return r
    }

    fun light(seed: Color): ColorScheme {
        val primary = ensureContrast(seed, LightBg, Color.Black)
        val container = lerp(seed, Color.White, 0.75f)
        val secondary = ensureContrast(lerp(seed, Color(0xFF5F6B70), 0.45f), LightBg, Color.Black)
        return lightColorScheme(
            primary = primary,
            onPrimary = onColor(primary),
            primaryContainer = container,
            onPrimaryContainer = ensureContrast(lerp(seed, Color.Black, 0.6f), container, Color.Black, 7f),
            secondary = secondary,
            onSecondary = onColor(secondary),
            secondaryContainer = lerp(seed, Color.White, 0.85f),
            onSecondaryContainer = lerp(seed, Color.Black, 0.75f),
            tertiary = Color(0xFFBF3737),
            background = LightBg,
            surface = LightBg,
            surfaceContainer = lerp(LightBg, seed, 0.05f),
            surfaceContainerHigh = lerp(LightBg, seed, 0.09f),
        )
    }

    fun dark(seed: Color): ColorScheme {
        val primary = ensureContrast(seed, DarkBg, Color.White)
        val container = lerp(seed, DarkBg, 0.55f)
        val secondary = ensureContrast(lerp(seed, Color(0xFFB0BEC5), 0.45f), DarkBg, Color.White)
        return darkColorScheme(
            primary = primary,
            onPrimary = onColor(primary),
            primaryContainer = container,
            onPrimaryContainer = ensureContrast(lerp(seed, Color.White, 0.7f), container, Color.White, 7f),
            secondary = secondary,
            onSecondary = onColor(secondary),
            secondaryContainer = lerp(seed, DarkBg, 0.7f),
            onSecondaryContainer = lerp(seed, Color.White, 0.8f),
            tertiary = Color(0xFFFFB59C),
            background = DarkBg,
            surface = DarkBg,
            surfaceContainer = lerp(Color(0xFF1D2026), seed, 0.06f),
            surfaceContainerHigh = lerp(Color(0xFF272A31), seed, 0.08f),
        )
    }
}

/** Выбранный цвет «как есть» — для обложек плейлистов. */
val LocalBrandColor = staticCompositionLocalOf { Color(DEFAULT_SEED_COLOR) }

@Composable
fun LinguaTheme(
    seed: Color = Color(DEFAULT_SEED_COLOR),
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val scheme = remember(seed, darkTheme) { if (darkTheme) SeedScheme.dark(seed) else SeedScheme.light(seed) }
    androidx.compose.runtime.CompositionLocalProvider(LocalBrandColor provides seed) {
        MaterialTheme(colorScheme = scheme, content = content)
    }
}
