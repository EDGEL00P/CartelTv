package com.carteltv.tv.core

import kotlin.coroutines.cancellation.CancellationException

/** [runCatching] that rethrows [CancellationException] so cancelled coroutines stop instead of looking like failures. */
inline fun <T> suspendRunCatching(block: () -> T): Result<T> =
    try {
        Result.success(block())
    } catch (e: CancellationException) {
        throw e
    } catch (e: Throwable) {
        Result.failure(e)
    }

