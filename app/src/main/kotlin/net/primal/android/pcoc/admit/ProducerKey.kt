package net.primal.android.pcoc.admit

import net.primal.android.user.credentials.CredentialsStore
import net.primal.android.user.domain.Credential
import net.primal.android.user.domain.CredentialType

fun producerPrivateKey(credentialsStore: CredentialsStore, userIdHex: String): ByteArray? {
    val credential = selectProducerCredential(credentialsStore.credentials.value, userIdHex)
    return credential?.admissionPrivateKeyOrNull()
}

/**
 * Producer nsec for this login. npub-only, the internal signer, and an external signer are skipped.
 * The account id may be hex or npub. Letter case is ignored.
 */
internal fun selectProducerCredential(credentials: Iterable<Credential>, userIdHex: String): Credential? {
    val hex = canonicalAccountHex(userIdHex) ?: return null
    return credentials.find { credential ->
        credential.type == CredentialType.PrivateKey && canonicalAccountHex(credential.npub) == hex
    }
}
