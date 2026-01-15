package common

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest

class RunCatchingNonFatalTest {
    @Test
    fun `non-suspend version captures regular exceptions`() {
        val result = runCatchingNonFatal { error("boom") }

        assertTrue(result.isFailure)
        assertEquals("boom", result.exceptionOrNull()?.message)
    }

    @Test
    fun `non-suspend version rethrows cancellation exceptions`() {
        assertFailsWith<CancellationException> {
            runCatchingNonFatal { throw CancellationException("cancelled") }
        }
    }

    @Test
    fun `non-suspend version rethrows errors`() {
        assertFailsWith<AssertionError> {
            runCatchingNonFatal { throw AssertionError("boom") }
        }
    }

    @Test
    fun `suspend version captures regular exceptions`() = runTest {
        val result = runCatchingNonFatal {
            delay(1)
            error("boom")
        }

        assertTrue(result.isFailure)
        assertEquals("boom", result.exceptionOrNull()?.message)
    }

    @Test
    fun `suspend version rethrows cancellation exceptions`() = runTest {
        assertFailsWith<CancellationException> {
            runCatchingNonFatal {
                delay(1)
                throw CancellationException("cancelled")
            }
        }
    }

    @Test
    fun `suspend version rethrows errors`() = runTest {
        assertFailsWith<AssertionError> {
            runCatchingNonFatal {
                delay(1)
                throw AssertionError("boom")
            }
        }
    }
}
