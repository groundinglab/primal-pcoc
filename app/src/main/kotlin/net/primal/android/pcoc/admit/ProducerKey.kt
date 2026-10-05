package net.primal.android.pcoc.admit

import fr.acinq.secp256k1.Hex
import net.primal.android.user.credentials.CredentialsStore
import net.primal.core.utils.runCatching
import net.primal.domain.nostr.cryptography.utils.toNpub

fun producerPrivateKey(credentialsStore: CredentialsStore, userIdHex: String): ByteArray? {
    val npub = runCatching { Hex.decode(userIdHex).toNpub() }.getOrNull()
    val credential = if (npub == null) {
        null
    } else {
        runCatching { credentialsStore.findOrThrow(npub) }.getOrNull()
    }
    return credential?.admissionPrivateKeyOrNull()
}
