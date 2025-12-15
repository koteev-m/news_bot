package routes

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class EtagTest {
    @Test
    fun `matches wildcard star`() {
        assertTrue(matchesEtag("*", "abc"))
        assertTrue(matchesEtag("\"*\"", "abc"))
    }

    @Test
    fun `matches any value from comma separated list`() {
        assertTrue(matchesEtag("\"etag1\", \"etag2\"", "etag2"))
        assertFalse(matchesEtag("\"etag1\", \"etag2\"", "etag3"))
        assertTrue(matchesEtag("W/\"etag1\", \"etag2\"", "etag1"))
    }

    @Test
    fun `matches weak and quoted etags`() {
        assertTrue(matchesEtag("W/\"etag123\"", "etag123"))
        assertTrue(matchesEtag("w/\"etag123\"", "etag123"))
        assertTrue(matchesEtag("etag123", "etag123"))
        assertTrue(matchesEtag("W/etag123", "etag123"))
    }

    @Test
    fun `ignores whitespace and mixed weak tokens`() {
        assertTrue(matchesEtag("  W/\"etag123\" , \"other\"", "etag123"))
        assertTrue(matchesEtag("etag123 , W/\"etag456\"", "etag456"))
    }

    @Test
    fun `returns false for blank header`() {
        assertFalse(matchesEtag(null, "etag"))
        assertFalse(matchesEtag("   ", "etag"))
    }
}
