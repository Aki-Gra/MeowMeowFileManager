package com.meowmeow.filemanager.ui

import kotlinx.coroutines.CancellationException

/**
 * Coroutine cancellation is expected when a user moves between folders or
 * replaces an in-flight scan. It must propagate rather than become a UI error
 * or let an obsolete scan overwrite more recent screen state.
 */
internal suspend fun <T> resultUnlessCancelled(block: suspend () -> T): Result<T> =
    try {
        Result.success(block())
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: Exception) {
        Result.failure(failure)
    }
