package net.primal.android.pcoc.enroll

/**
 * Stored object bytes for admission. This store is not an "is a hike" record.
 * Clear deletes these bytes only. The note is the file name and the date saved.
 */
interface EnrolledPcocStore {
    fun contains(userIdHex: String): Boolean

    fun read(userIdHex: String): ByteArray?

    fun note(userIdHex: String): EnrolledNote? = null

    fun write(userIdHex: String, objectBytes: ByteArray)

    fun write(userIdHex: String, objectBytes: ByteArray, fileName: String, savedAtEpochMs: Long) {
        write(userIdHex, objectBytes)
    }

    fun delete(userIdHex: String)
}

data class EnrolledNote(
    val fileName: String,
    val savedAtEpochMs: Long,
)
