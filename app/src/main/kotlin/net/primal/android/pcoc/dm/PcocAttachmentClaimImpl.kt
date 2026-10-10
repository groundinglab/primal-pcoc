package net.primal.android.pcoc.dm

import fr.acinq.secp256k1.Hex
import javax.inject.Inject
import javax.inject.Singleton
import net.primal.data.repository.messages.PcocAttachmentClaim
import net.primal.domain.nostr.NostrEvent
import net.primal.domain.nostr.cryptography.utils.bechToBytesOrThrow
import net.primal.domain.nostr.cryptography.utils.hexToNpubHrp
import net.primal.domain.nostr.findFirstProfileId
import net.primal.android.user.credentials.CredentialsStore

/**
 * Kind-4 DMs whose plaintext is a PCOC `.cbor` are offered for approval.
 * Anything else returns false and stays on the normal message path.
 * An external signer has no local nsec, so those DMs are not claimed.
 */
@Singleton
class PcocAttachmentClaimImpl @Inject constructor(
    private val inbox: PcocDmInbox,
    private val credentialsStore: CredentialsStore,
) : PcocAttachmentClaim {
    override fun claim(userId: String, event: NostrEvent): Boolean {
        val plaintext = try {
            plaintextFor(userId, event)
        } catch (error: IllegalArgumentException) {
            null
        } catch (error: RuntimeException) {
            null
        }
        return inbox.consider(
            accountHex = userId,
            eventId = event.id,
            kind = event.kind,
            recipientHex = event.tags.findFirstProfileId(),
            plaintext = plaintext,
        )
    }

    private fun plaintextFor(userId: String, event: NostrEvent): ByteArray? {
        if (event.kind != PCOC_DM_KIND) return null
        val recipient = event.tags.findFirstProfileId() ?: return null
        if (!recipient.equals(userId, ignoreCase = true)) return null
        val nsec = credentialsStore.findOrThrow(userId.hexToNpubHrp()).nsec ?: return null
        val privateKey = nsec.bechToBytesOrThrow(hrp = "nsec")
        val sender = Hex.decode(event.pubKey)
        return nip04DecryptToBytes(
            privateKey = privateKey,
            senderPubKey = sender,
            content = event.content,
        )
    }
}
