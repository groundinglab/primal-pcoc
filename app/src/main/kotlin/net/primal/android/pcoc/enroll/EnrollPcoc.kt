package net.primal.android.pcoc.enroll

import fr.acinq.secp256k1.Hex
import io.github.aakira.napier.Napier
import net.primal.android.pcoc.hike.ACCOUNT_KEY_SIZE
import net.primal.android.pcoc.hike.HikeReaderUnavailable
import net.primal.android.pcoc.hike.HikeYieldedPublicKeyReader
import net.primal.android.pcoc.hike.YieldedPublicKey
import net.primal.core.utils.runCatching
import net.primal.domain.nostr.cryptography.utils.toHex

/**
 * Enroll outcomes. [Stored] keeps the object bytes for admission. It is not a hike verdict.
 * [Withheld] is a key mismatch. It is not an admission denial.
 * [NoPublicKey] and [Undecodable] stay apart.
 */
sealed class EnrollOutcome {
    data object Stored : EnrollOutcome()

    data object Withheld : EnrollOutcome()

    data object NoPublicKey : EnrollOutcome()

    data object Undecodable : EnrollOutcome()

    data object ReaderUnavailable : EnrollOutcome()

    data object NoAccountKey : EnrollOutcome()
}

class EnrollPcoc(
    private val reader: HikeYieldedPublicKeyReader,
    private val store: EnrolledPcocStore,
) {
    fun enroll(objectBytes: ByteArray, accountHex: String): EnrollOutcome {
        val read = try {
            reader.yieldedPublicKey(objectBytes)
        } catch (error: HikeReaderUnavailable) {
            Napier.w(error) { "hike::yielded_public_key is unavailable." }
            null
        }
        return when (read) {
            null -> EnrollOutcome.ReaderUnavailable
            YieldedPublicKey.NoPublicKey -> EnrollOutcome.NoPublicKey
            YieldedPublicKey.Undecodable -> EnrollOutcome.Undecodable
            is YieldedPublicKey.PublicKey -> enrollMatchingKey(
                yielded = read.bytes,
                accountHex = accountHex,
                objectBytes = objectBytes,
            )
        }
    }

    fun clear(accountHex: String) {
        store.delete(accountHex)
    }

    private fun enrollMatchingKey(
        yielded: ByteArray,
        accountHex: String,
        objectBytes: ByteArray,
    ): EnrollOutcome {
        val outcome = when {
            !isAccountHex(accountHex) -> EnrollOutcome.NoAccountKey
            accountKeysMatch(yielded = yielded, accountHex = accountHex) -> EnrollOutcome.Stored
            else -> EnrollOutcome.Withheld
        }
        if (outcome == EnrollOutcome.Stored) {
            store.write(accountHex, objectBytes)
        }
        return outcome
    }
}

internal fun isAccountHex(accountHex: String): Boolean = ACCOUNT_HEX.matches(accountHex)

/**
 * Match the 32 yielded bytes to the account key. Bytes, or the store's hex spelling.
 * npub text compared with hex text is not a match.
 */
internal fun accountKeysMatch(yielded: ByteArray, accountHex: String): Boolean {
    val hexMatch = yielded.size == ACCOUNT_KEY_SIZE &&
        yielded.toHex().equals(accountHex, ignoreCase = true)
    val decoded = runCatching { Hex.decode(accountHex) }.getOrNull()
    val byteMatch = yielded.size == ACCOUNT_KEY_SIZE &&
        decoded != null &&
        decoded.size == ACCOUNT_KEY_SIZE &&
        decoded.contentEquals(yielded)
    return hexMatch || byteMatch
}

private val ACCOUNT_HEX = Regex("^[0-9a-fA-F]{64}$")
