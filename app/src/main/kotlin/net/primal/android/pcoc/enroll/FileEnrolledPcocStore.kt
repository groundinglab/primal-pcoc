package net.primal.android.pcoc.enroll

import java.io.File

/**
 * One object file per 64-hex account id, under [directory]. Delete removes that file only.
 */
class FileEnrolledPcocStore(
    private val directory: File,
) : EnrolledPcocStore {

    override fun contains(userIdHex: String): Boolean = objectFile(userIdHex)?.isFile == true

    override fun read(userIdHex: String): ByteArray? = objectFile(userIdHex)?.takeIf { it.isFile }?.readBytes()

    override fun write(userIdHex: String, objectBytes: ByteArray) {
        val file = objectFile(userIdHex) ?: throw IllegalArgumentException("user id is not 64 hex")
        directory.mkdirs()
        file.writeBytes(objectBytes)
    }

    override fun delete(userIdHex: String) {
        objectFile(userIdHex)?.delete()
    }

    private fun objectFile(userIdHex: String): File? {
        if (!USER_ID.matches(userIdHex)) return null
        return File(directory, "${userIdHex.lowercase()}.bin")
    }

    private companion object {
        val USER_ID = Regex("^[0-9a-fA-F]{64}$")
    }
}
