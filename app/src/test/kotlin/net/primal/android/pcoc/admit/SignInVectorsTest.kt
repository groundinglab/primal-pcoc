package net.primal.android.pcoc.admit

import fr.acinq.secp256k1.Hex
import fr.acinq.secp256k1.Secp256k1
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import java.io.File
import java.math.BigInteger
import java.util.Base64
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import net.primal.core.utils.serialization.CommonJson
import net.primal.domain.nostr.cryptography.utils.CryptoUtils
import org.junit.Test

/**
 * Loads `pcoc/sign-in.json`, the locked §15.1 wire from `conformance/v2.0/sign-in.json`.
 * Checks every link, the example challenge, message, and signature, and every POST body.
 * The window rows are the Gate's check. SPEC §15.1 says the signing client does not enforce them.
 */
class SignInVectorsTest {

    private val pack: JsonObject = CommonJson.parseToJsonElement(vectorFile().readText()).jsonObject
    private val example = pack.getValue("example").jsonObject
    private val links = pack.getValue("links").jsonArray.map { it.jsonObject }
    private val bodies = pack.getValue("bodies").jsonArray.map { it.jsonObject }
    private val window = pack.getValue("window").jsonArray.map { it.jsonObject }

    @Test
    fun `pack names the locked sign-in wire`() {
        pack.keys shouldBe PACK_KEYS
        pack.string("id") shouldBe "sign-in"
        pack.string("spec") shouldBe "Satoshi Hike v2.0 section 15.1"
        pack.string("domain") shouldBe String(SignInChallenge.DOMAIN, Charsets.US_ASCII)
        pack.string("origin") shouldBe SIGN_IN_ORIGIN
        pack.string("door") shouldBe SIGN_IN_DOOR
        pack.string("method") shouldBe "POST"
        pack.string("content_type") shouldBe "application/json"
        pack.long("lifetime_s") shouldBe LIFETIME_SECONDS
        pack.long("skew_s") shouldBe SKEW_SECONDS
        pack.string("client_ok") shouldBe "Sign-in submitted"
        pack.getValue("client_codes").jsonArray.map { it.jsonPrimitive.content } shouldBe CLIENT_CODES
        links.isNotEmpty() shouldBe true
        bodies.isNotEmpty() shouldBe true
        window.isNotEmpty() shouldBe true
    }

    @Test
    fun `every link vector is accepted or refused by its class`() {
        links.forEach { item ->
            val input = item.string("input")
            val parsed = SignInLinks.parse(input)
            withClue(item.string("note")) {
                when (item.string("class")) {
                    "link" -> {
                        val link = (parsed as SignInLinkParse.Ready).link
                        link.text.contains("object=") shouldBe false
                        if (input.endsWith("&exp=0") || input.trimEnd().endsWith("&exp=0")) {
                            link.exp shouldBe 0uL
                        }
                    }
                    "malformed" -> (parsed is SignInLinkParse.Ready) shouldBe false
                    else -> error("unknown link class ${item.string("class")}")
                }
                if ("?h=" in input) parsed shouldBe SignInLinkParse.RetiredCode
            }
        }
    }

    @Test
    fun `the example challenge bytes and message match the vector`() {
        val fromFields = SignInChallenge.challengeBytes(
            origin = example.string("origin"),
            nonce = Hex.decode(example.string("nonce_hex")),
            exp = example.long("expiry_unix_s").toULong(),
            objectSha256 = Hex.decode(example.string("object_sha256_hex")),
        )
        val length = example.long("challenge_bytes_len").toInt()
        fromFields.size shouldBe length
        length shouldBe CHALLENGE_LENGTH_FOR_PCOC_APP
        Hex.encode(fromFields) shouldBe example.string("challenge_bytes_hex")

        val link = (SignInLinks.parse(example.string("link")) as SignInLinkParse.Ready).link
        link.origin shouldBe example.string("origin")
        link.nonceHex shouldBe example.string("nonce_hex")
        link.exp shouldBe example.long("expiry_unix_s").toULong()
        link.text.contains("object=") shouldBe false
        val challenge = SignInChallenge.challengeBytes(
            link,
            Hex.decode(example.string("object_sha256_hex")),
        )
        Hex.encode(challenge) shouldBe example.string("challenge_bytes_hex")
        val message = SignInChallenge.bip340Message(challenge)
        Hex.encode(message) shouldBe example.string("bip340_message_32_hex")
        Hex.encode(sha256(message)) shouldBe example.string("double_hash_hex")

        val sameLink = links.filter { item ->
            val parsed = SignInLinks.parse(item.string("input"))
            parsed is SignInLinkParse.Ready && parsed.link.text == example.string("link")
        }
        sameLink.map { it.string("note") } shouldContainExactly listOf("canonical", "ASCII trim")

        val objectBytes = Base64.getDecoder().decode(example.string("object_b64"))
        objectBytes.contentEquals(ByteArray(FIELD_BYTES) { 0xaa.toByte() }) shouldBe true
        val fileHash = Hex.encode(sha256(objectBytes))
        fileHash shouldBe example.string("file_of_0xaa_sha256")
        (fileHash == example.string("object_sha256_hex")) shouldBe false
        example.bool("hash_matches_object_field") shouldBe false
    }

    @Test
    fun `the example signature verifies once and the signer reproduces it`() {
        val secret = Hex.decode(example.string("secret_hex"))
        val publicKey = Hex.decode(example.string("public_key_hex"))
        val message = Hex.decode(example.string("bip340_message_32_hex"))
        val signature = Hex.decode(example.string("signature_hex"))
        val link = (SignInLinks.parse(example.string("link")) as SignInLinkParse.Ready).link

        example.string("aux_hex") shouldBe "00".repeat(FIELD_BYTES)
        example.string("parity") shouldBe "even"
        compressedPrefix(secret) shouldBe EVEN_PUBLIC_KEY_PREFIX
        signingPublicKey(secret)!!.contentEquals(publicKey) shouldBe true
        AdmissionSigner.sign(
            privateKey = secret,
            link = link,
            objectSha256 = Hex.decode(example.string("object_sha256_hex")),
        ) shouldBe example.string("signature_hex")
        CryptoUtils.verify(signature = signature, hash = message, pubKey = publicKey) shouldBe true
        CryptoUtils.verify(
            signature = signature,
            hash = Hex.decode(example.string("double_hash_hex")),
            pubKey = publicKey,
        ) shouldBe false
        CryptoUtils.verify(
            signature = Hex.decode(AdmissionSigner.signMessage(secret, sha256(message))),
            hash = message,
            pubKey = publicKey,
        ) shouldBe false
    }

    @Test
    fun `every body vector matches the submit grammar and the client encodes the ok body`() {
        val objectBytes = Base64.getDecoder().decode(example.string("object_b64"))
        val link = (SignInLinks.parse(example.string("link")) as SignInLinkParse.Ready).link
        val encoded = CommonJson.parseToJsonElement(
            AdmissionPayload.encode(
                link = link,
                objectBytes = objectBytes,
                signatureHex = example.string("signature_hex"),
            ),
        ).jsonObject
        encoded.keys.toList() shouldBe BODY_KEYS
        encoded.getValue("exp").jsonPrimitive.isString shouldBe false
        encoded.getValue("object").jsonPrimitive.content shouldBe example.string("file_of_0xaa_sha256")
        grammarOk(encoded) shouldBe true

        var okBodies = 0
        bodies.forEach { item ->
            val body = item.getValue("body").jsonObject
            withClue(item.string("note")) {
                when (item.string("class")) {
                    "grammar_ok" -> {
                        grammarOk(body) shouldBe true
                        okBodies += 1
                    }
                    "malformed" -> grammarOk(body) shouldBe false
                    else -> error("unknown body class ${item.string("class")}")
                }
            }
        }
        okBodies shouldBe 1
    }

    @Test
    fun `window vectors use the wide sum and the client still accepts the example link`() {
        val lifetime = pack.long("lifetime_s")
        val skew = pack.long("skew_s")
        window.forEach { item ->
            withClue("now ${item.long("now")} exp ${item.long("exp")}") {
                windowAccepts(now = item.long("now"), exp = item.long("exp"), lifetime = lifetime, skew = skew) shouldBe
                    item.bool("accept")
            }
        }
        val acceptsExample = windowAccepts(
            now = example.long("window_now_2026_10_10"),
            exp = example.long("expiry_unix_s"),
            lifetime = lifetime,
            skew = skew,
        )
        acceptsExample shouldBe example.bool("window_accepts_example_at_2026_10_10")
        acceptsExample shouldBe false
        (SignInLinks.parse(example.string("link")) is SignInLinkParse.Ready) shouldBe true
    }

    @Test
    fun `response vectors are the two client codes and delivery ok`() {
        val responses = pack.getValue("responses").jsonObject
        CLIENT_CODES.forEach { code ->
            AdmissionPayload.errorCode(responses.getValue(code).toString()) shouldBe code
        }
        AdmissionPayload.errorCode(responses.getValue("delivery_ok").toString()) shouldBe null
        responses.getValue("delivery_ok").jsonObject.keys shouldBe setOf("delivery")
        responses.getValue("bad_signature").jsonObject.keys shouldBe setOf("error")
        responses.getValue("malformed").jsonObject.keys shouldBe setOf("error")
    }

    private fun grammarOk(body: JsonObject): Boolean {
        val nonce = body.textOrNull("nonce")
        val objectHex = body.textOrNull("object")
        val signature = body.textOrNull("signature")
        val exp = body["exp"] as? JsonPrimitive
        val encoded = body.textOrNull("object_b64")
        return body.size == BODY_KEYS.size &&
            body.keys == BODY_KEYS.toSet() &&
            body.textOrNull("origin") == SIGN_IN_ORIGIN &&
            nonce != null && nonce.isLowerHex(NONCE_HEX) &&
            objectHex != null && objectHex.isLowerHex(NONCE_HEX) &&
            signature != null && signature.isLowerHex(SIGNATURE_HEX) &&
            exp != null && !exp.isString && exp.content.toULongOrNull() != null &&
            encoded != null && standardBase64(encoded)
    }

    /** Standard base64, `=` padding, no whitespace. Java's decoder also accepts a missing pad. */
    private fun standardBase64(encoded: String): Boolean {
        if (encoded.isEmpty() || encoded.length % 4 != 0 || encoded.any { it.isWhitespace() }) return false
        return try {
            Base64.getEncoder().encodeToString(Base64.getDecoder().decode(encoded)) == encoded
        } catch (_: IllegalArgumentException) {
            false
        }
    }

    private fun JsonObject.string(name: String): String = getValue(name).jsonPrimitive.content

    private fun JsonObject.long(name: String): Long = string(name).toLong()

    private fun JsonObject.bool(name: String): Boolean = string(name).toBoolean()

    private fun JsonObject.textOrNull(name: String): String? {
        val primitive = this[name] as? JsonPrimitive ?: return null
        return primitive.takeIf { it.isString }?.content
    }

    private fun String.isLowerHex(length: Int): Boolean =
        this.length == length && all { it in '0'..'9' || it in 'a'..'f' }

    private fun vectorFile(): File =
        listOf(
            File("src/test/resources/pcoc/sign-in.json"),
            File("app/src/test/resources/pcoc/sign-in.json"),
        ).first { it.isFile }

    private companion object {
        const val CHALLENGE_LENGTH_FOR_PCOC_APP = 109
        const val FIELD_BYTES = 32
        const val NONCE_HEX = 64
        const val SIGNATURE_HEX = 128
        const val LIFETIME_SECONDS = 120L
        const val SKEW_SECONDS = 5L
        const val EVEN_PUBLIC_KEY_PREFIX: Byte = 2
        val CLIENT_CODES = listOf("bad_signature", "malformed")
        val BODY_KEYS = listOf("origin", "nonce", "exp", "object", "object_b64", "signature")
        val PACK_KEYS = setOf(
            "id",
            "spec",
            "label",
            "domain",
            "origin",
            "lifetime_s",
            "skew_s",
            "door",
            "method",
            "content_type",
            "client_ok",
            "client_codes",
            "website_outcomes",
            "responses",
            "example",
            "links",
            "window",
            "bodies",
        )
    }
}

/**
 * SPEC §15.1. The Gate accepts when `now < exp + skew` and `exp <= now + lifetime + skew`.
 * The sums are wide so the addition does not wrap. The signing client does not apply this.
 */
private fun windowAccepts(
    now: Long,
    exp: Long,
    lifetime: Long,
    skew: Long,
): Boolean {
    val nowWide = BigInteger.valueOf(now)
    val expWide = BigInteger.valueOf(exp)
    val lifetimeWide = BigInteger.valueOf(lifetime)
    val skewWide = BigInteger.valueOf(skew)
    return nowWide < expWide + skewWide && expWide <= nowWide + lifetimeWide + skewWide
}

private fun compressedPrefix(secret: ByteArray): Byte {
    val secp256k1 = Secp256k1.get()
    return secp256k1.pubKeyCompress(secp256k1.pubkeyCreate(secret))[0]
}
