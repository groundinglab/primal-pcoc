package net.primal.android.pcoc.dm

import fr.acinq.secp256k1.Hex
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import java.io.File
import net.primal.android.pcoc.enroll.EnrollOutcome
import net.primal.android.pcoc.enroll.EnrollPcoc
import net.primal.android.pcoc.enroll.FileEnrolledPcocStore
import org.junit.Test

/**
 * The wire is the desktop's NIP-04 kind-4 attachment.
 * `SHARED_OBJECT_HEX` and `SHARED_NIP04_CONTENT` are the vector in
 * `apps/desktop/core/src/dm.rs`.
 */
class PcocDmTest {
    private class RecordingNotifier : PcocDmNotifier {
        var shown = 0
        var cleared = 0

        override fun show() {
            shown += 1
        }

        override fun clear() {
            cleared += 1
        }
    }

    @Test
    fun `matching DM notifies and stores nothing until approval`() {
        withInbox { store, inbox, notifier ->
            val plain = decryptVector()
            plain.contentEquals(objectBytes()) shouldBe true

            inbox.consider(
                accountHex = ACCOUNT,
                eventId = EVENT,
                kind = PCOC_DM_KIND,
                recipientHex = ACCOUNT,
                plaintext = plain,
            ) shouldBe true

            notifier.shown shouldBe 1
            store.contains(ACCOUNT) shouldBe false
            store.read(ACCOUNT).shouldBeNull()
            inbox.pending.value?.bytes?.contentEquals(objectBytes()) shouldBe true
        }
    }

    @Test
    fun `a DM that is not that attachment is ignored`() {
        withInbox { store, inbox, notifier ->
            inbox.consider(
                accountHex = ACCOUNT,
                eventId = "text",
                kind = PCOC_DM_KIND,
                recipientHex = ACCOUNT,
                plaintext = "hello".encodeToByteArray(),
            ) shouldBe false
            inbox.consider(
                accountHex = ACCOUNT,
                eventId = "other-kind",
                kind = 1,
                recipientHex = ACCOUNT,
                plaintext = objectBytes(),
            ) shouldBe false
            inbox.consider(
                accountHex = ACCOUNT,
                eventId = "other-account",
                kind = PCOC_DM_KIND,
                recipientHex = OTHER,
                plaintext = objectBytes(),
            ) shouldBe false
            inbox.consider(
                accountHex = ACCOUNT,
                eventId = "undecryptable",
                kind = PCOC_DM_KIND,
                recipientHex = ACCOUNT,
                plaintext = null,
            ) shouldBe false

            notifier.shown shouldBe 0
            store.contains(ACCOUNT) shouldBe false
            inbox.pending.value.shouldBeNull()
            inbox.problem.value.shouldBeNull()
        }
    }

    @Test
    fun `a cbor map that is not a PCOC says it failed to parse`() {
        withInbox { _, inbox, notifier ->
            inbox.consider(
                accountHex = ACCOUNT,
                eventId = "empty-map",
                kind = PCOC_DM_KIND,
                recipientHex = ACCOUNT,
                plaintext = byteArrayOf(0xa0.toByte()),
            ) shouldBe false

            inbox.problem.value shouldBe "Failed to parse the PCOC .cbor DM."
            inbox.pending.value.shouldBeNull()
            notifier.shown shouldBe 0
            isCborMap("hello".encodeToByteArray()) shouldBe false
        }
    }

    @Test
    fun `a self DM with a PCOC plaintext is offered`() {
        withInbox { _, inbox, notifier ->
            inbox.consider(
                accountHex = ACCOUNT,
                eventId = "self",
                kind = PCOC_DM_KIND,
                recipientHex = ACCOUNT.uppercase(),
                plaintext = objectBytes(),
            ) shouldBe true
            inbox.pending.value.shouldNotBeNull()
            notifier.shown shouldBe 1
        }
    }

    @Test
    fun `a decrypt failure is visible`() {
        withInbox { _, inbox, _ ->
            inbox.decryptFailed()
            inbox.problem.value shouldBe "Failed to parse the PCOC .cbor DM."
        }
    }

    @Test
    fun `dismiss stores nothing`() {
        withInbox { store, inbox, notifier ->
            store.write(OTHER, byteArrayOf(4), "other.pcoc", 1L)
            inbox.consider(
                accountHex = ACCOUNT,
                eventId = EVENT,
                kind = PCOC_DM_KIND,
                recipientHex = ACCOUNT,
                plaintext = decryptVector(),
            ) shouldBe true

            inbox.dismiss()

            notifier.cleared shouldBe 1
            inbox.pending.value.shouldBeNull()
            store.contains(ACCOUNT) shouldBe false
            store.read(ACCOUNT).shouldBeNull()
            store.read(OTHER).contentEquals(byteArrayOf(4)) shouldBe true
            inbox.consider(
                accountHex = ACCOUNT,
                eventId = EVENT,
                kind = PCOC_DM_KIND,
                recipientHex = ACCOUNT,
                plaintext = objectBytes(),
            ) shouldBe true
            notifier.shown shouldBe 1
            store.contains(ACCOUNT) shouldBe false
        }
    }

    @Test
    fun `approve enrols the exact bytes through the import store`() {
        withInbox { store, inbox, _ ->
            val plain = decryptVector()
            inbox.consider(
                accountHex = ACCOUNT,
                eventId = EVENT,
                kind = PCOC_DM_KIND,
                recipientHex = ACCOUNT.uppercase(),
                plaintext = plain,
            ) shouldBe true

            inbox.approve(SAVED_AT) shouldBe EnrollOutcome.Stored

            inbox.pending.value.shouldBeNull()
            store.read(ACCOUNT).contentEquals(objectBytes()) shouldBe true
            store.read(ACCOUNT).contentEquals(plain) shouldBe true
            store.note(ACCOUNT)?.fileName shouldBe "pcoc.cbor"
        }
    }

    private fun decryptVector(): ByteArray {
        val plain = nip04DecryptToBytes(
            privateKey = phoneKey(),
            senderPubKey = Hex.decode(SENDER_PUB_HEX),
            content = SHARED_NIP04_CONTENT,
        )
        check(plain != null) { "shared NIP-04 vector did not decrypt" }
        return plain
    }

    private fun withInbox(block: (FileEnrolledPcocStore, PcocDmInbox, RecordingNotifier) -> Unit) {
        val root = File(System.getProperty("java.io.tmpdir"), "pcoc-dm-" + System.nanoTime())
        try {
            val store = FileEnrolledPcocStore(root)
            val notifier = RecordingNotifier()
            val inbox = PcocDmInbox(enroll = EnrollPcoc(store), notifier = notifier)
            block(store, inbox, notifier)
        } finally {
            root.deleteRecursively()
        }
    }

    private fun objectBytes(): ByteArray = Hex.decode(SHARED_OBJECT_HEX)

    private fun phoneKey(): ByteArray = ByteArray(32).also { it[31] = 1 }

    private companion object {
        const val ACCOUNT = "79be667ef9dcbbac55a06295ce870b07029bfcdb2dce28d959f2815b16f81798"
        const val OTHER = "2222222222222222222222222222222222222222222222222222222222222222"
        const val EVENT = "dm-193"
        const val SAVED_AT = 1_760_000_000_000L
        const val SENDER_PUB_HEX = "7e7e9c42a91bfef19fa929e5fda1b72e0ebc1a4c1141673e2794234d86addf4e"

        /** CBOR map `{ "public_key": <generator x> }`. Same bytes as desktop `SHARED_OBJECT_HEX`. */
        const val SHARED_OBJECT_HEX =
            "a16a7075626c69635f6b6579582079be667ef9dcbbac55a06295ce870b07029bfcdb2dce28d959f2815b16f81798"

        /** Desktop `prepare_send_with_iv` for that object, IV `0x11` × 16, sender nsec1vl029…. */
        const val SHARED_NIP04_CONTENT =
            "UFcSxGbxDZHZadW7k1QFYO52XdoH747bAJ69t0LDpboA4OZ00306bFft3LgvZ+yv?iv=EREREREREREREREREREREQ=="
    }
}
