package net.primal.android.pcoc.enroll

import fr.acinq.secp256k1.Hex
import io.kotest.matchers.shouldBe
import net.primal.android.pcoc.hike.HikeReaderUnavailable
import net.primal.android.pcoc.hike.HikeYieldedPublicKeyReader
import net.primal.android.pcoc.hike.YieldedPublicKey
import net.primal.domain.nostr.cryptography.utils.toNpub
import org.junit.Test

class EnrollPcocTest {

    private val accountHex = "11".repeat(32)
    private val otherHex = "22".repeat(32)
    private val objectBytes = byteArrayOf(9, 8, 7)

    @Test
    fun `public key match stores the object bytes and is not a hike verdict`() {
        val store = MemoryStore()
        val enroll = enroll(store, YieldedPublicKey.PublicKey(Hex.decode(accountHex)))

        enroll.enroll(objectBytes, accountHex) shouldBe EnrollOutcome.Stored
        store.read(accountHex).contentEquals(objectBytes) shouldBe true
    }

    @Test
    fun `uppercase account hex matches the yielded bytes`() {
        val store = MemoryStore()
        val enroll = enroll(store, YieldedPublicKey.PublicKey(Hex.decode(accountHex)))

        enroll.enroll(objectBytes, accountHex.uppercase()) shouldBe EnrollOutcome.Stored
        store.contains(accountHex) shouldBe true
    }

    @Test
    fun `public key mismatch withholds and does not replace a stored object`() {
        val store = MemoryStore()
        store.write(accountHex, byteArrayOf(1))
        val enroll = enroll(store, YieldedPublicKey.PublicKey(Hex.decode(otherHex)))

        enroll.enroll(objectBytes, accountHex) shouldBe EnrollOutcome.Withheld
        store.read(accountHex).contentEquals(byteArrayOf(1)) shouldBe true
    }

    @Test
    fun `no public key stops without storing and is not a withhold`() {
        val store = MemoryStore()
        store.write(accountHex, byteArrayOf(1))
        val enroll = enroll(store, YieldedPublicKey.NoPublicKey)

        enroll.enroll(objectBytes, accountHex) shouldBe EnrollOutcome.NoPublicKey
        store.read(accountHex).contentEquals(byteArrayOf(1)) shouldBe true
    }

    @Test
    fun `undecodable stops on its own path and does not store`() {
        val store = MemoryStore()
        val enroll = enroll(store, YieldedPublicKey.Undecodable)

        enroll.enroll(objectBytes, accountHex) shouldBe EnrollOutcome.Undecodable
        store.contains(accountHex) shouldBe false
    }

    @Test
    fun `reader unavailable does not store`() {
        val store = MemoryStore()
        val enroll = EnrollPcoc(
            reader = HikeYieldedPublicKeyReader { throw HikeReaderUnavailable("not linked") },
            store = store,
        )

        enroll.enroll(objectBytes, accountHex) shouldBe EnrollOutcome.ReaderUnavailable
        store.contains(accountHex) shouldBe false
    }

    @Test
    fun `npub text is not compared as hex and nothing is stored`() {
        val store = MemoryStore()
        val npub = Hex.decode(accountHex).toNpub()
        val enroll = enroll(store, YieldedPublicKey.PublicKey(Hex.decode(accountHex)))

        enroll.enroll(objectBytes, npub) shouldBe EnrollOutcome.NoAccountKey
        store.contains(accountHex) shouldBe false
        store.contains(npub) shouldBe false
    }

    @Test
    fun `clear deletes only that account object`() {
        val store = MemoryStore()
        store.write(accountHex, objectBytes)
        store.write(otherHex, byteArrayOf(4))
        val enroll = enroll(store, YieldedPublicKey.NoPublicKey)

        enroll.clear(accountHex)

        store.contains(accountHex) shouldBe false
        store.read(otherHex).contentEquals(byteArrayOf(4)) shouldBe true
    }

    private fun enroll(store: MemoryStore, yielded: YieldedPublicKey) =
        EnrollPcoc(reader = HikeYieldedPublicKeyReader { yielded }, store = store)
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
