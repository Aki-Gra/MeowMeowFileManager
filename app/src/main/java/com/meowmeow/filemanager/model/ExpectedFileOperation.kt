package com.meowmeow.filemanager.model

/**
 * User-correctable validation outcomes, not diagnostics failures.
 * Do not include filenames, paths or file contents in these messages.
 */
class ExpectedFileOperationException(message: String) : IllegalArgumentException(message)

fun validateRequestedName(name: String) {
    if (!isValidFileName(name)) {
        throw ExpectedFileOperationException("Enter a valid name.")
    }
}

fun rejectExistingName(alreadyExists: Boolean) {
    if (alreadyExists) {
        throw ExpectedFileOperationException("An item with that name already exists.")
    }
}

/** Keep this narrow: unexpected I/O, access failures and other bugs remain loggable. */
fun shouldRecordOperationError(error: Throwable): Boolean =
    error !is ExpectedFileOperationException &&
        error !is kotlinx.coroutines.CancellationException
