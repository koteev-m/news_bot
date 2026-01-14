package common

import kotlinx.coroutines.CancellationException

inline fun <T> runCatchingNonFatal(block: () -> T): Result<T> {
    return try {
        Result.success(block())
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (err: Error) {
        throw err
    } catch (t: Throwable) {
        Result.failure(t)
    }
}

suspend inline fun <T> runCatchingNonFatal(crossinline block: suspend () -> T): Result<T> {
    return try {
        Result.success(block())
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (err: Error) {
        throw err
    } catch (t: Throwable) {
        Result.failure(t)
    }
}
