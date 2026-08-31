package de.uhi.enia.ridesafe.util

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.InputStream
import java.io.OutputStream

/**
 * [InputStream.copyTo], but it notices cancellation.
 *
 * A stream copy is blocking work with no suspension point in it, so a cancelled export or import
 * would otherwise run to completion regardless. One check per buffer bounds that to a single read.
 */
@Suppress("BlockingMethodInNonBlockingContext")
internal suspend fun copyCancellable(
    input: InputStream,
    output: OutputStream,
) {
    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
    while (true) {
        currentCoroutineContext().ensureActive()
        val count = input.read(buffer)
        if (count < 0) return
        output.write(buffer, 0, count)
    }
}
