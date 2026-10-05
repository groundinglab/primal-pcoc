package net.primal.android.pcoc.enroll

import java.io.File
import java.io.IOException

/**
 * One object file per 64-hex account id, under [directory].
 * A sidecar holds the file name and the date saved. Delete removes that account's files only.
 */
class FileEnrolledPcocStore(
    private val directory: File,
) : EnrolledPcocStore {

    override fun contains(userIdHex: String): Boolean = objectFile(userIdHex)?.isFile == true

    override fun read(userIdHex: String): ByteArray? = objectFile(userIdHex)?.takeIf { it.isFile }?.readBytes()

    override fun note(userIdHex: String): EnrolledNote? {
        val text = noteFile(userIdHex)?.takeIf { it.isFile }?.readText().orEmpty()
        val split = text.indexOf('\n')
        val savedAt = if (split > 0) text.substring(0, split).toLongOrNull() else null
        val fileName = if (split > 0) text.substring(split + 1) else ""
        return if (savedAt != null && fileName.isNotEmpty()) {
            EnrolledNote(fileName = fileName, savedAtEpochMs = savedAt)
        } else {
            null
        }
    }

    override fun write(userIdHex: String, objectBytes: ByteArray) {
        writeBytes(userIdHex, objectBytes)
    }

    override fun write(userIdHex: String, objectBytes: ByteArray, fileName: String, savedAtEpochMs: Long) {
        writeBytes(userIdHex, objectBytes)
        try {
            writeNote(userIdHex, fileName, savedAtEpochMs)
        } catch (error: IOException) {
            delete(userIdHex)
            throw error
        }
    }

    override fun delete(userIdHex: String) {
        objectFile(userIdHex)?.delete()
        noteFile(userIdHex)?.delete()
    }

    private fun writeBytes(userIdHex: String, objectBytes: ByteArray) {
        val file = objectFile(userIdHex) ?: throw IllegalArgumentException("user id is not 64 hex")
        directory.mkdirs()
        file.writeBytes(objectBytes)
    }

    private fun writeNote(userIdHex: String, fileName: String, savedAtEpochMs: Long) {
        val file = noteFile(userIdHex) ?: return
        directory.mkdirs()
        file.writeText(savedAtEpochMs.toString() + "\n" + fileName)
    }

    private fun objectFile(userIdHex: String): File? {
        if (!USER_ID.matches(userIdHex)) return null
        return File(directory, "${userIdHex.lowercase()}.bin")
    }

    private fun noteFile(userIdHex: String): File? {
        if (!USER_ID.matches(userIdHex)) return null
        return File(directory, "${userIdHex.lowercase()}.note")
    }

    private companion object {
        val USER_ID = Regex("^[0-9a-fA-F]{64}$")
    }
}
