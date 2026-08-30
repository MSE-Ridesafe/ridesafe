package de.uhi.enia.ridesafe.backup

import de.uhi.enia.ridesafe.util.copyCancellable
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.security.DigestInputStream
import java.security.MessageDigest
import java.util.HexFormat
import java.util.zip.CRC32
import java.util.zip.CheckedInputStream

internal data class FileIntegrity(
    val size: Long,
    val sha256: String,
    val crc32: Long,
)

internal fun fileIntegrity(file: File): FileIntegrity = file.inputStream().use(::streamIntegrity)

internal fun streamIntegrity(input: InputStream): FileIntegrity {
    val digest = MessageDigest.getInstance("SHA-256")
    val crc = CRC32()
    val size = DigestInputStream(CheckedInputStream(input, crc), digest).copyTo(OutputStream.nullOutputStream())
    return FileIntegrity(size, HexFormat.of().formatHex(digest.digest()), crc.value)
}

internal suspend fun copyFileCancellable(
    source: File,
    destination: File,
) {
    source.inputStream().buffered().use { input -> destination.outputStream().buffered().use { output -> copyCancellable(input, output) } }
}
