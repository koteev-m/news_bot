package netflow2

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class TickerNormalizationTest {
    @Test
    fun `normalizes upper-case and trims`() {
        assertEquals("SBER", normalizeTicker(" sber "))
        assertEquals("GAZP-1", normalizeTicker("gazp-1"))
    }

    @Test
    fun `rejects whitespace and invalid characters`() {
        assertFailsWith<IllegalArgumentException> { normalizeTicker("S BER") }
        assertFailsWith<IllegalArgumentException> { normalizeTicker("SBER?x=1") }
    }
}
