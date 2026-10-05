package net.primal.android.pcoc.admit

import fr.acinq.secp256k1.Hex
import java.security.MessageDigest
import net.primal.android.pcoc.enroll.EnrolledPcocStore
import net.primal.android.pcoc.hike.hikeObjectPublicKey
import net.primal.core.utils.runCatching
import net.primal.domain.nostr.cryptography.utils.bech32ToHexOrThrow

/**
 * Top-level CBOR `public_key` only.
 * A parsed hike object supplies that field. Otherwise the top-level scan does.
 * This is not hike verify and not an enroll gate. Enroll still stores the file as-is.
 */
internal fun enrolledObjectPublicKey(bytes: ByteArray): ByteArray? = chooseObjectKey(
    parsed = hikeObjectPublicKey(bytes),
    scanned = scanTopPublicKey(bytes),
)

internal fun chooseObjectKey(parsed: ByteArray?, scanned: ByteArray?): ByteArray? {
    val parsedKey = parsed?.takeIf { it.size == PUBLIC_KEY_LEN }
    return if (parsedKey != null) parsedKey else scanned?.takeIf { it.size == PUBLIC_KEY_LEN }
}

internal data class ObjectKeyReport(
    val check: ObjectKeyCheck,
    val loginPrefix: String?,
    val filePrefix: String?,
    val accountPrefix: String? = null,
    val scanPrefix: String? = null,
    val readersDisagree: Boolean = false,
    val keySource: String = "none",
)

/**
 * Match when the object's top-level public key is the logged-in account,
 * or the public key of the nsec that will sign. Otherwise fail closed.
 */
internal fun objectKeyReport(
    accountHex: String,
    signingKey: ByteArray?,
    objectBytes: ByteArray,
): ObjectKeyReport {
    val parsed = hikeObjectPublicKey(objectBytes)
    val scanned = scanTopPublicKey(objectBytes)
    val objectKey = chooseObjectKey(parsed = parsed, scanned = scanned)
    val accountKey = accountPublicKey(accountHex)
    val signerKey = signingKey?.takeIf { it.size == PUBLIC_KEY_LEN }
    val matchedAccount = keysEqual(accountKey, objectKey)
    val matchedSigner = keysEqual(signerKey, objectKey)
    val loginShown = when {
        matchedSigner && !matchedAccount -> signerKey
        accountKey != null -> accountKey
        else -> signerKey
    }
    val disagree = parsed != null && scanned != null && !parsed.contentEquals(scanned)
    return ObjectKeyReport(
        check = keyCheck(objectKey = objectKey, same = matchedAccount || matchedSigner),
        loginPrefix = keyPrefix(loginShown),
        filePrefix = keyPrefix(objectKey),
        accountPrefix = accountPrefixWhenSignerDiffers(
            loginShown = loginShown,
            accountKey = accountKey,
            signerKey = signerKey,
        ),
        scanPrefix = if (disagree) keyPrefix(scanned) else null,
        readersDisagree = disagree,
        keySource = keySource(parsed = parsed, scanned = scanned),
    )
}

internal fun checkObjectKey(accountHex: String, objectBytes: ByteArray): ObjectKeyCheck =
    objectKeyReport(accountHex = accountHex, signingKey = null, objectBytes = objectBytes).check

internal fun canonicalAccountHex(raw: String): String? {
    val trimmed = raw.trim().removePrefix("0x").removePrefix("0X")
    val hex = if (ACCOUNT_HEX.matches(trimmed)) trimmed.lowercase() else npubHex(trimmed)
    return hex?.takeIf { ACCOUNT_HEX.matches(it) }
}

internal fun enrolledObject(store: EnrolledPcocStore, accountHex: String): ByteArray? {
    val canonical = canonicalAccountHex(accountHex) ?: return store.read(accountHex)
    return store.read(canonical)
}

internal fun accountHasObject(store: EnrolledPcocStore, accountHex: String): Boolean {
    val canonical = canonicalAccountHex(accountHex) ?: return store.contains(accountHex)
    return store.contains(canonical)
}

internal fun keyPrefix(bytes: ByteArray?): String? {
    if (bytes == null || bytes.size != PUBLIC_KEY_LEN) return null
    val prefix = Hex.encode(bytes).lowercase().take(KEY_PREFIX_LEN)
    return prefix.takeIf { KEY_PREFIX.matches(it) }
}

internal fun shownKeyPrefix(prefix: String?): String {
    val text = prefix?.trim()?.lowercase() ?: return NONE
    return if (KEY_PREFIX.matches(text)) text else NONE
}

private fun scanTopPublicKey(bytes: ByteArray): ByteArray? {
    if (bytes.isEmpty()) return null
    val scan = CborScan(bytes)
    val key = scan.topPublicKey()
    return if (scan.broken) null else key
}

private fun accountPublicKey(accountHex: String): ByteArray? {
    val hex = canonicalAccountHex(accountHex) ?: return null
    val decoded = runCatching { Hex.decode(hex) }.getOrNull()
    return decoded?.takeIf { it.size == PUBLIC_KEY_LEN }
}

private fun npubHex(text: String): String? {
    val decoded = if (text.startsWith(NPUB_PREFIX, ignoreCase = true)) {
        runCatching { text.lowercase().bech32ToHexOrThrow() }.getOrNull()
    } else {
        null
    }
    return decoded?.lowercase()
}

private fun accountPrefixWhenSignerDiffers(
    loginShown: ByteArray?,
    accountKey: ByteArray?,
    signerKey: ByteArray?,
): String? {
    val comparable = loginShown != null && accountKey != null && signerKey != null
    val show = comparable && keysEqual(loginShown, signerKey) && !keysEqual(accountKey, signerKey)
    return if (show) keyPrefix(accountKey) else null
}

private fun keysEqual(left: ByteArray?, right: ByteArray?): Boolean =
    left != null && right != null && MessageDigest.isEqual(left, right)

private fun keyCheck(objectKey: ByteArray?, same: Boolean): ObjectKeyCheck =
    when {
        objectKey == null -> ObjectKeyCheck.Unreadable
        same -> ObjectKeyCheck.Match
        else -> ObjectKeyCheck.Mismatch
    }

private fun keySource(parsed: ByteArray?, scanned: ByteArray?): String =
    when {
        parsed != null -> SOURCE_OBJECT
        scanned != null -> SOURCE_SCAN
        else -> NONE
    }

private class CborScan(private val data: ByteArray) {
    var broken: Boolean = false
    private var index: Int = 0

    fun topPublicKey(): ByteArray? {
        val head = u8()
        val isMap = head != null && major(head) == MAJOR_MAP
        if (head != null && !isMap) broken = true
        return if (head == null || !isMap) null else readMap(additional(head))
    }

    private fun readMap(info: Int): ByteArray? {
        val count = count(info)
        var found: ByteArray? = null
        if (count != null) {
            var pair = 0
            while (pair < count && !broken) {
                found = readPair(found)
                pair += 1
            }
        }
        return if (broken) null else found
    }

    private fun readPair(found: ByteArray?): ByteArray? {
        val key = readFieldName()
        val next = if (broken) {
            null
        } else if (key != null && key.contentEquals(PUBLIC_KEY_NAME)) {
            takePublicKey(found)
        } else {
            skipItem()
            found
        }
        return if (broken) null else next
    }

    private fun takePublicKey(found: ByteArray?): ByteArray? {
        val value = readPublicKeyBytes()
        val bad = value == null || found != null
        return if (broken || bad) {
            broken = true
            null
        } else {
            value
        }
    }

    private fun readFieldName(): ByteArray? {
        val head = u8() ?: return null
        val named = major(head) == MAJOR_TEXT || major(head) == MAJOR_BYTES
        if (!named) skipAfterHead(head)
        return if (named) payload(head) else null
    }

    private fun readPublicKeyBytes(): ByteArray? {
        val head = u8() ?: return null
        return when (major(head)) {
            MAJOR_BYTES -> rawKey(payload(head))
            MAJOR_TEXT -> hexKey(payload(head))
            else -> {
                skipAfterHead(head)
                markBroken()
            }
        }
    }

    private fun rawKey(raw: ByteArray?): ByteArray? =
        raw?.takeIf { it.size == PUBLIC_KEY_LEN } ?: markBroken()

    private fun hexKey(raw: ByteArray?): ByteArray? {
        val text = raw?.let { bytes -> runCatching { bytes.toString(Charsets.UTF_8) }.getOrNull() }
        val hex = text?.takeIf { ACCOUNT_HEX.matches(it) }
        val decoded = if (hex == null) null else runCatching { Hex.decode(hex) }.getOrNull()
        return decoded?.takeIf { it.size == PUBLIC_KEY_LEN } ?: markBroken()
    }

    private fun markBroken(): ByteArray? {
        broken = true
        return null
    }

    private fun payload(head: Int): ByteArray? {
        val length = count(additional(head))
        return if (length == null) null else take(length)
    }

    private fun skipItem() {
        val head = u8() ?: return
        skipAfterHead(head)
    }

    private fun skipAfterHead(head: Int) {
        when (major(head)) {
            MAJOR_UINT, MAJOR_NEGATIVE -> argument(additional(head))
            MAJOR_BYTES, MAJOR_TEXT -> skipCounted(additional(head)) { length -> skip(length) }
            MAJOR_ARRAY -> skipCounted(additional(head)) { length -> repeat(length) { skipItem() } }
            MAJOR_MAP -> skipCounted(additional(head)) { length ->
                repeat(length) {
                    skipItem()
                    skipItem()
                }
            }
            MAJOR_TAG -> {
                argument(additional(head))
                if (!broken) skipItem()
            }
            MAJOR_SIMPLE -> skipSimple(additional(head))
            else -> broken = true
        }
    }

    private fun skipCounted(info: Int, block: (Int) -> Unit) {
        val length = count(info)
        if (length != null) block(length)
    }

    private fun skipSimple(info: Int) {
        val extra = when (info) {
            in 0..SHORT_MAX -> 0
            ONE_BYTE -> 1
            TWO_BYTES -> TWO_BYTES_LEN
            FOUR_BYTES -> FOUR_BYTES_LEN
            EIGHT_BYTES -> EIGHT
            else -> {
                broken = true
                return
            }
        }
        skip(extra)
    }

    private fun count(info: Int): Int? {
        val length = argument(info)
        return if (length != null && length >= 0L && length <= remaining()) {
            length.toInt()
        } else {
            broken = true
            null
        }
    }

    private fun argument(info: Int): Long? {
        val value = when {
            info < ONE_BYTE -> info.toLong()
            info == ONE_BYTE -> u8()?.toLong()
            info == TWO_BYTES -> readUnsigned(TWO_BYTES_LEN)
            info == FOUR_BYTES -> readUnsigned(FOUR_BYTES_LEN)
            info == EIGHT_BYTES -> readUnsigned(EIGHT)
            else -> null
        }
        if (value == null) broken = true
        return value
    }

    private fun readUnsigned(size: Int): Long? {
        val enough = !broken && size <= remaining()
        if (!enough) broken = true
        var value = 0L
        if (enough) {
            repeat(size) {
                val byte = u8()
                if (byte != null) value = (value shl BYTE_BITS) or byte.toLong()
            }
        }
        return if (broken) null else value
    }

    private fun take(length: Int): ByteArray? {
        if (broken || length < 0 || length > remaining()) {
            broken = true
            return null
        }
        val out = data.copyOfRange(index, index + length)
        index += length
        return out
    }

    private fun skip(length: Int) {
        if (broken || length < 0 || length > remaining()) {
            broken = true
            return
        }
        index += length
    }

    private fun u8(): Int? {
        if (broken || index >= data.size) {
            broken = true
            return null
        }
        return data[index++].toInt() and BYTE_MASK
    }

    private fun remaining(): Int = data.size - index

    private fun major(head: Int): Int = head ushr MAJOR_SHIFT

    private fun additional(head: Int): Int = head and ADDITIONAL_MASK
}

internal const val KEY_PREFIX_LEN = 8
private const val PUBLIC_KEY_LEN = 32
private const val NPUB_PREFIX = "npub"
private const val SOURCE_OBJECT = "object"
private const val SOURCE_SCAN = "scan"
private const val NONE = "none"
private const val BYTE_MASK = 0xff
private const val ADDITIONAL_MASK = 0x1f
private const val MAJOR_SHIFT = 5
private const val BYTE_BITS = 8
private const val SHORT_MAX = 23
private const val ONE_BYTE = 24
private const val TWO_BYTES = 25
private const val FOUR_BYTES = 26
private const val EIGHT_BYTES = 27
private const val TWO_BYTES_LEN = 2
private const val FOUR_BYTES_LEN = 4
private const val EIGHT = 8
private const val MAJOR_UINT = 0
private const val MAJOR_NEGATIVE = 1
private const val MAJOR_BYTES = 2
private const val MAJOR_TEXT = 3
private const val MAJOR_ARRAY = 4
private const val MAJOR_MAP = 5
private const val MAJOR_TAG = 6
private const val MAJOR_SIMPLE = 7
private val ACCOUNT_HEX = Regex("^[0-9a-fA-F]{64}$")
internal val KEY_PREFIX = Regex("^[0-9a-f]{8}$")
private val PUBLIC_KEY_NAME = "public_key".toByteArray(Charsets.UTF_8)
