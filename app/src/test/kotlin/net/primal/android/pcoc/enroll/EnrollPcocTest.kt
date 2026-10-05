package net.primal.android.pcoc.enroll

import io.kotest.matchers.shouldBe
import org.junit.Test

class EnrollPcocTest {

    private val accountHex = "11".repeat(32)
    private val otherHex = "22".repeat(32)
    private val objectBytes = byteArrayOf(9, 8, 7)

    @Test
    fun `picked file is stored for the account with no key check`() {
        val store = MemoryStore()
        val enroll = EnrollPcoc(store)

        enroll.enroll(objectBytes, accountHex, "report.pcoc", SAVED_AT) shouldBe EnrollOutcome.Stored
        store.read(accountHex).contentEquals(objectBytes) shouldBe true
        store.note(accountHex) shouldBe EnrolledNote("report.pcoc", SAVED_AT)
    }

    @Test
    fun `a later pick replaces the stored bytes`() {
        val store = MemoryStore()
        val enroll = EnrollPcoc(store)
        enroll.enroll(byteArrayOf(1), accountHex, "first.pcoc", 1L)

        enroll.enroll(objectBytes, accountHex, "second.pcoc", SAVED_AT) shouldBe EnrollOutcome.Stored
        store.read(accountHex).contentEquals(objectBytes) shouldBe true
        store.note(accountHex) shouldBe EnrolledNote("second.pcoc", SAVED_AT)
    }

    @Test
    fun `empty file bytes are stored`() {
        val store = MemoryStore()
        val enroll = EnrollPcoc(store)

        enroll.enroll(byteArrayOf(), accountHex, "empty.pcoc", SAVED_AT) shouldBe EnrollOutcome.Stored
        store.read(accountHex).contentEquals(byteArrayOf()) shouldBe true
        store.note(accountHex)?.fileName shouldBe "empty.pcoc"
    }

    @Test
    fun `a hex key is not the file name on screen`() {
        displayFileName("ab".repeat(32)) shouldBe "PCOC file"
        displayFileName(null) shouldBe "PCOC file"
        displayFileName("  ") shouldBe "PCOC file"
        displayFileName("report.pcoc") shouldBe "report.pcoc"
    }

    @Test
    fun `an id that is not 64 hex is not stored`() {
        val store = MemoryStore()
        val enroll = EnrollPcoc(store)

        enroll.enroll(objectBytes, "nsec1notauserid", "report.pcoc", SAVED_AT) shouldBe EnrollOutcome.NotStored
        store.contains(accountHex) shouldBe false
        store.contains("nsec1notauserid") shouldBe false
    }

    @Test
    fun `clear deletes only that account object`() {
        val store = MemoryStore()
        store.write(accountHex, objectBytes, "report.pcoc", SAVED_AT)
        store.write(otherHex, byteArrayOf(4), "other.pcoc", SAVED_AT)
        val enroll = EnrollPcoc(store)

        enroll.clear(accountHex)

        store.contains(accountHex) shouldBe false
        store.note(accountHex) shouldBe null
        store.read(otherHex).contentEquals(byteArrayOf(4)) shouldBe true
        store.note(otherHex)?.fileName shouldBe "other.pcoc"
    }

    private companion object {
        const val SAVED_AT = 1_759_622_400_000L
    }
}

private class MemoryStore : EnrolledPcocStore {
    private val files = mutableMapOf<String, ByteArray>()
    private val notes = mutableMapOf<String, EnrolledNote>()

    override fun contains(userIdHex: String): Boolean = files.containsKey(userIdHex.lowercase())

    override fun read(userIdHex: String): ByteArray? = files[userIdHex.lowercase()]?.copyOf()

    override fun note(userIdHex: String): EnrolledNote? = notes[userIdHex.lowercase()]

    override fun write(userIdHex: String, objectBytes: ByteArray) {
        check(userIdHex.matches(Regex("^[0-9a-fA-F]{64}$")))
        files[userIdHex.lowercase()] = objectBytes.copyOf()
    }

    override fun write(userIdHex: String, objectBytes: ByteArray, fileName: String, savedAtEpochMs: Long) {
        write(userIdHex, objectBytes)
        notes[userIdHex.lowercase()] = EnrolledNote(fileName, savedAtEpochMs)
    }

    override fun delete(userIdHex: String) {
        files.remove(userIdHex.lowercase())
        notes.remove(userIdHex.lowercase())
    }
}
