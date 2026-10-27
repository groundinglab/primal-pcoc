package net.primal.android.pcoc.dm

import fr.acinq.secp256k1.Hex
import fr.acinq.secp256k1.Secp256k1
import java.io.IOException
import korlibs.crypto.AES.Companion.decryptAesCbc
import korlibs.crypto.CipherPadding
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import net.primal.android.pcoc.admit.SignInLog
import net.primal.android.pcoc.admit.SignInNotice
import net.primal.android.pcoc.admit.SignInNotices
import net.primal.android.pcoc.admit.enrolledObjectPublicKey
import net.primal.android.pcoc.enroll.EnrollOutcome
import net.primal.android.pcoc.enroll.EnrollPcoc

/** NIP-04 kind 4. The attachment is the raw `.cbor` plaintext. */
internal const val PCOC_DM_KIND = 4

private const val FILE_NAME = "pcoc.cbor"

/**
 * One offered file. Nothing here is the enrolled object.
 * Approve is what writes the Import store.
 */
class PcocDmOffer(
    val accountHex: String,
    val eventId: String,
    val bytes: ByteArray,
)

interface PcocDmNotifier {
    fun show()

    fun clear()
}

object SilentPcocDmNotifier : PcocDmNotifier {
    override fun show() = Unit

    override fun clear() = Unit
}

/**
 * Holds a matching DM until the person approves or dismisses it.
 * Dismiss does not write the Import store. This class does not POST to the gate.
 */
class PcocDmInbox(
    private val enroll: EnrollPcoc,
    private val notifier: PcocDmNotifier = SilentPcocDmNotifier,
) {
    private val pendingFlow = MutableStateFlow<PcocDmOffer?>(null)
    val pending: StateFlow<PcocDmOffer?> = pendingFlow.asStateFlow()
    private val problemFlow = MutableStateFlow<String?>(null)
    val problem: StateFlow<String?> = problemFlow.asStateFlow()
    private val handled = HashSet<String>()

    @Synchronized
    fun consider(
        accountHex: String,
        eventId: String,
        kind: Int,
        recipientHex: String?,
        plaintext: ByteArray?,
    ): Boolean {
        if (kind != PCOC_DM_KIND) return false
        if (recipientHex == null || !recipientHex.equals(accountHex, ignoreCase = true)) return false
        if (plaintext == null) return false
        if (!isPcocObject(plaintext)) {
            if (isCborMap(plaintext)) show(SignInNotices.dm(parsed = false))
            return false
        }
        // Already offered. Still claim it so a later sync does not store the bytes.
        if (eventId.isNotEmpty() && !handled.add(eventId)) return true
        show(SignInNotices.dm(parsed = true))
        pendingFlow.value = PcocDmOffer(
            accountHex = accountHex,
            eventId = eventId,
            bytes = plaintext.copyOf(),
        )
        notifier.show()
        return true
    }

    /** A kind-4 addressed to this account has a local key but did not decrypt. */
    @Synchronized
    fun decryptFailed() {
        show(SignInNotices.dm(parsed = false))
    }

    @Synchronized
    fun clearProblem() {
        problemFlow.value = null
    }

    private fun show(notice: SignInNotice) {
        SignInLog.write(notice)
        problemFlow.value = if (notice == SignInNotices.dm(parsed = false)) notice.userText else null
    }

    @Synchronized
    fun dismiss() {
        pendingFlow.value = null
        notifier.clear()
    }

    @Synchronized
    fun approve(savedAtEpochMs: Long): EnrollOutcome {
        val offer = pendingFlow.value ?: return EnrollOutcome.NotStored
        val outcome = try {
            enroll.enroll(
                objectBytes = offer.bytes,
                accountHex = offer.accountHex,
                fileName = FILE_NAME,
                savedAtEpochMs = savedAtEpochMs,
            )
        } catch (error: IOException) {
            return EnrollOutcome.NotStored
        }
        if (outcome is EnrollOutcome.Stored) {
            pendingFlow.value = null
            notifier.clear()
        }
        return outcome
    }
}

internal fun isPcocObject(bytes: ByteArray): Boolean = enrolledObjectPublicKey(bytes)?.size == PUBLIC_KEY_LEN

/** A CBOR map. Text DMs are not maps, so a normal message is not a parse failure. */
internal fun isCborMap(bytes: ByteArray): Boolean {
    if (bytes.isEmpty()) return false
    return bytes[0].toInt() and CBOR_MAJOR_MASK == CBOR_MAP
}

/**
 * NIP-04 decrypt to bytes. The desktop content is `base64(ciphertext)?iv=base64(iv)`.
 * The AES key is the x coordinate of the shared point. The plaintext is not decoded as text.
 */
@OptIn(ExperimentalEncodingApi::class)
internal fun nip04DecryptToBytes(
    privateKey: ByteArray,
    senderPubKey: ByteArray,
    content: String,
): ByteArray? {
    val parts = content.split("?iv=")
    if (parts.size != 2 || privateKey.size != PUBLIC_KEY_LEN || senderPubKey.size != PUBLIC_KEY_LEN) {
        return null
    }
    return try {
        val encrypted = Base64.decode(parts[0])
        val iv = Base64.decode(parts[1])
        if (iv.size != AES_BLOCK) return null
        decryptAesCbc(
            data = encrypted,
            key = sharedSecret(privateKey, senderPubKey),
            iv = iv,
            padding = CipherPadding.PKCS7Padding,
        )
    } catch (error: IllegalArgumentException) {
        null
    } catch (error: RuntimeException) {
        null
    }
}

private fun sharedSecret(privateKey: ByteArray, senderPubKey: ByteArray): ByteArray =
    secp256k1.pubKeyTweakMul(Hex.decode("02") + senderPubKey, privateKey).copyOfRange(1, 33)

private val secp256k1 = Secp256k1.get()

private const val PUBLIC_KEY_LEN = 32
private const val AES_BLOCK = 16
private const val CBOR_MAJOR_MASK = 0xE0
private const val CBOR_MAP = 0xA0
