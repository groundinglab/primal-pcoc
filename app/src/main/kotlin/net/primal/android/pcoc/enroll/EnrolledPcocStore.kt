package net.primal.android.pcoc.enroll

/**
 * Stored object bytes for admission. This store is not an "is a hike" record.
 * Clear deletes these bytes only.
 */
interface EnrolledPcocStore {
    fun contains(userIdHex: String): Boolean

    fun read(userIdHex: String): ByteArray?

    fun write(userIdHex: String, objectBytes: ByteArray)

    fun delete(userIdHex: String)
}
