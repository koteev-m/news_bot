package common

import kotlinx.coroutines.CancellationException

/**
 * Ловим только non-fatal, CancellationException/Error пробрасываем.
 */
inline fun <T> runCatchingNonFatal(block: () -> T): Result<T> {
    return try {
        Result.success(block())
    } catch (t: Throwable) {
        rethrowIfFatal(t)
        Result.failure(t)
    }
}

/**
 * Ловим только non-fatal, CancellationException/Error пробрасываем.
 */
@Suppress("INVISIBLE_REFERENCE", "INVISIBLE_MEMBER")
@kotlin.internal.LowPriorityInOverloadResolution
suspend inline fun <T> runCatchingNonFatal(block: suspend () -> T): Result<T> {
    return try {
        Result.success(block())
    } catch (t: Throwable) {
        rethrowIfFatal(t)
        Result.failure(t)
    }
}

@PublishedApi
internal fun rethrowIfFatal(t: Throwable) {
    if (t is CancellationException) throw t
    if (t is Error) throw t
}
