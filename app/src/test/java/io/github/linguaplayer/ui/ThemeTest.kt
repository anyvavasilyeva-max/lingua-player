package io.github.linguaplayer.ui

import androidx.compose.ui.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.linguaplayer.data.repo.DEFAULT_SEED_COLOR
import io.github.linguaplayer.ui.components.HexColor
import io.github.linguaplayer.ui.theme.SeedScheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.random.Random

@RunWith(AndroidJUnit4::class)
class ThemeTest {
    @Test
    fun anySeedGivesReadablePrimaryInBothThemes() {
        val seeds = listOf(DEFAULT_SEED_COLOR, 0xFFFFDE59.toInt(), 0xFF00FBFF.toInt(), 0xFFFFFFFF.toInt(), 0xFF000000.toInt(), 0xFF1800AD.toInt()) +
            List(200) { 0xFF000000.toInt() or Random(it).nextInt(0x1000000) }
        for (argb in seeds) {
            val seed = Color(argb)
            val light = SeedScheme.light(seed)
            val dark = SeedScheme.dark(seed)
            assertTrue("light ${HexColor.format(argb)}", SeedScheme.contrast(light.primary, light.background) >= 4.4f)
            assertTrue("dark ${HexColor.format(argb)}", SeedScheme.contrast(dark.primary, dark.background) >= 4.4f)
            assertTrue(SeedScheme.contrast(light.onPrimary, light.primary) >= 3f)
            assertTrue(SeedScheme.contrast(SeedScheme.onColor(seed), seed) >= 3f)
        }
    }

    @Test
    fun hexRoundTrip() {
        assertEquals("#00B2D1", HexColor.format(DEFAULT_SEED_COLOR))
        assertEquals(DEFAULT_SEED_COLOR, HexColor.parse("#00b2d1"))
        assertEquals(0xFFBF3737.toInt(), HexColor.parse("bf3737"))
        assertNull(HexColor.parse("#12345"))
        assertNull(HexColor.parse("#GG0000"))
    }
}
