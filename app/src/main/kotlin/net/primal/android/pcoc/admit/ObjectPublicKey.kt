package net.primal.android.pcoc.admit

import fr.acinq.secp256k1.Hex
import java.security.MessageDigest

internal enum class ObjectKeyCheck {
    Match,
    Mismatch,
    Unreadable,
}

/**
 * Reads only the top-level CBOR `public_key` byte string.
 * This is not hike verify and not an enroll gate. Enroll still stores the file as-is.
 */
internal fun enrolledObjectPublicKey(bytes: ByteArray): ByteArray? {
    if (bytes.isEmpty()) return null
    val scan = CborScan(bytes)
    val key = scan.topPublicKey()
    return if (scan.broken) null else key
}

internal fun checkObjectKey(accountHex: String, objectBytes: ByteArray): ObjectKeyCheck {
    val objectKey = enrolledObjectPublicKey(objectBytes) ?: return ObjectKeyCheck.Unreadable
    val accountKey = accountPublicKey(accountHex) ?: return ObjectKeyCheck.Mismatch
    val same = accountKey.size == PUBLIC_KEY_LEN && MessageDigest.isEqual(accountKey, objectKey)
    return if (same) ObjectKeyCheck.Match else ObjectKeyCheck.Mismatch
}

private fun accountPublicKey(accountHex: String): ByteArray? {
    if (!ACCOUNT_HEX.matches(accountHex)) return null
    val decoded = Hex.decode(accountHex)
    return decoded.takeIf { it.size == PUBLIC_KEY_LEN }
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
        val key = readTextKey()
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
        val value = readByteString()
        val bad = value == null || value.size != PUBLIC_KEY_LEN || found != null
        return if (broken || bad) {
            broken = true
            null
        } else {
            value
        }
    }

    private fun readTextKey(): ByteArray? {
        val head = u8() ?: return null
        if (major(head) != MAJOR_TEXT) {
            skipAfterHead(head)
            return null
        }
        return payload(head)
    }

    private fun readByteString(): ByteArray? {
        val head = u8() ?: return null
        if (major(head) != MAJOR_BYTES) {
            skipAfterHead(head)
            broken = true
            return null
        }
        return payload(head)
    }

    private fun payload(head: Int): ByteArray? {
        val length = count(additional(head)) ?: return null
        return take(length)
    }

    private fun skipItem() {
        val head = u8() ?: return
        skipAfterHead(head)
    }

    private fun skipAfterHead(head: Int) {
        when (major(head)) {
            MAJOR_UINT, MAJOR_NEGATIVE -> argument(additional(head))
            MAJOR_BYTES, MAJOR_TEXT -> {
                val length = count(additional(head)) ?: return
                skip(length)
            }
            MAJOR_ARRAY -> {
                val length = count(additional(head)) ?: return
                repeat(length) { skipItem() }
            }
            MAJOR_MAP -> {
                val length = count(additional(head)) ?: return
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

    private fun skipSimple(info: Int) {
        val extra = when (info) {
            in 0..SHORT_MAX -> 0
            ONE_BYTE -> 1
            TWO_BYTES -> 2
            FOUR_BYTES -> 4
            EIGHT_BYTES -> EIGHT
            else -> {
                broken = true
                return
            }
        }
        skip(extra)
    }

    private fun count(info: Int): Int? {
        val length = argument(info) ?: return null
        if (length < 0L || length > remaining()) {
            broken = true
            return null
        }
        return length.toInt()
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
        if (size > remaining()) {
            broken = true
            return null
        }
        var value = 0L
        repeat(size) {
            val byte = u8() ?: return null
            value = (value shl BYTE_BITS) or byte.toLong()
        }
        return value
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

private const val PUBLIC_KEY_LEN = 32
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
private val PUBLIC_KEY_NAME = "public_key".toByteArray(Charsets.UTF_8)
