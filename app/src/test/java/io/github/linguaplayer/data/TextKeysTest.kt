package io.github.linguaplayer.data

import io.github.linguaplayer.util.TextKeys
import org.junit.Assert.assertEquals
import org.junit.Test

class TextKeysTest {
    @Test
    fun decomposedHangulEqualsPrecomposed() {
        val precomposed = "포크"
        val decomposed = java.text.Normalizer.normalize(precomposed, java.text.Normalizer.Form.NFD)
        check(precomposed != decomposed)
        assertEquals(TextKeys.key(precomposed), TextKeys.key(decomposed))
    }

    @Test
    fun caseAndWhitespaceAreIgnored() {
        assertEquals("мыть посуду", TextKeys.key("  Мыть   ПОСУДУ \n"))
        assertEquals("Мыть посуду", TextKeys.clean("  Мыть   посуду "))
    }
}
