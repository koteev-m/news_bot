package http

import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.CancellationException
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HttpClientErrorTest {
    @Test
    fun `httpStatusError with null body keeps snippet null`() {
        val error =
            HttpClientError.httpStatusError(
                status = HttpStatusCode.BadRequest,
                requestUrl = "https://example.com",
                rawBody = null,
            )

        assertNull(error.bodySnippet)
    }

    @Test
    fun `httpStatusError replaces control characters with spaces`() {
        val rawBody = "line1\tline2\nline3\rline4"
        val error =
            HttpClientError.httpStatusError(
                status = HttpStatusCode.BadRequest,
                requestUrl = "https://example.com",
                rawBody = rawBody,
            )

        val snippet = assertNotNull(error.bodySnippet)
        assertEquals("line1 line2 line3 line4", snippet)
        assertFalse(snippet.contains("\t"))
        assertFalse(snippet.contains("\n"))
        assertFalse(snippet.contains("\r"))
    }

    @Test
    fun `httpStatusError truncates long body with ellipsis`() {
        val rawBody = "a".repeat(600)
        val error =
            HttpClientError.httpStatusError(
                status = HttpStatusCode.BadRequest,
                requestUrl = "https://example.com",
                rawBody = rawBody,
            )

        val snippet = assertNotNull(error.bodySnippet)
        assertTrue(snippet.length <= 512)
        assertTrue(snippet.endsWith("…"))
    }

    @Test
    fun `httpStatusError with blank body returns null snippet`() {
        val rawBody = " \t\n\r   "
        val error =
            HttpClientError.httpStatusError(
                status = HttpStatusCode.BadRequest,
                requestUrl = "https://example.com",
                rawBody = rawBody,
            )

        assertNull(error.bodySnippet)
    }

    @Test
    fun `toHttpClientError rethrows cancellation`() {
        assertFailsWith<CancellationException> {
            CancellationException().toHttpClientError()
        }
    }

    @Test
    fun `toHttpClientError rethrows errors`() {
        assertFailsWith<AssertionError> {
            AssertionError("boom").toHttpClientError()
        }
    }
}
