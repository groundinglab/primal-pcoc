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

        enroll.enroll(objectBytes, accountHex) shouldBe EnrollOutcome.Stored
        store.read(accountHex).contentEquals(objectBytes) shouldBe true
    }

    @Test
    fun `a later pick replaces the stored bytes`() {
        val store = MemoryStore()
        val enroll = EnrollPcoc(store)
        enroll.enroll(byteArrayOf(1), accountHex)

        enroll.enroll(objectBytes, accountHex) shouldBe EnrollOutcome.Stored
        store.read(accountHex).contentEquals(objectBytes) shouldBe true
    }

    @Test
    fun `empty file bytes are stored`() {
        val store = MemoryStore()
        val enroll = EnrollPcoc(store)

        enroll.enroll(byteArrayOf(), accountHex) shouldBe EnrollOutcome.Stored
        store.read(accountHex).contentEquals(byteArrayOf()) shouldBe true
    }

    @Test
    fun `an id that is not 64 hex is not stored`() {
        val store = MemoryStore()
        val enroll = EnrollPcoc(store)

        enroll.enroll(objectBytes, "nsec1notauserid") shouldBe EnrollOutcome.NotStored
        store.contains(accountHex) shouldBe false
        store.contains("nsec1notauserid") shouldBe false
    }

    @Test
    fun `clear deletes only that account object`() {
        val store = MemoryStore()
        store.write(accountHex, objectBytes)
        store.write(otherHex, byteArrayOf(4))
        val enroll = EnrollPcoc(store)

        enroll.clear(accountHex)

        store.contains(accountHex) shouldBe false
        store.read(otherHex).contentEquals(byteArrayOf(4)) shouldBe true
    }
}

private class MemoryStore : EnrolledPcocStore {
    private val files = mutableMapOf<String, ByteArray>()

    override fun contains(userIdHex: String): Boolean = files.containsKey(userIdHex.lowercase())

    override fun read(userIdHex: String): ByteArray? = files[userIdHex.lowercase()]?.copyOf()

    override fun write(userIdHex: String, objectBytes: ByteArray) {
        check(userIdHex.matches(Regex("^[0-9a-fA-F]{64}$")))
        files[userIdHex.lowercase()] = objectBytes.copyOf()
    }

    override fun delete(userIdHex: String) {
        files.remove(userIdHex.lowercase())
    }
}
