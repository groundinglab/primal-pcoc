package net.primal.android.pcoc.admit

import fr.acinq.secp256k1.Hex
import io.kotest.matchers.shouldBe
import java.io.File
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import net.primal.core.utils.serialization.CommonJson
import net.primal.domain.nostr.cryptography.utils.CryptoUtils
import org.junit.Test

/**
 * Loads `pcoc/sign-in.json`, the §15.1 sign-in vectors. Until the pcoc conformance pack is reachable from
 * this build, that file is a stand-in built by `gen_sign_in.py` with the BIP-340 reference algorithm,
 * and its first entry is the published §15.1 EXAMPLE.
 */
class SignInVectorsTest {

    private val pack: JsonObject = CommonJson.parseToJsonElement(vectorFile().readText()).jsonObject
    private val vectors = pack.getValue("vectors").jsonArray.map { it.jsonObject }

    @Test
    fun `pack names the locked domain, origin, and door`() {
        pack.string("domain") shouldBe String(SignInChallenge.DOMAIN, Charsets.US_ASCII)
        pack.string("origin") shouldBe SIGN_IN_ORIGIN
        pack.string("door") shouldBe SIGN_IN_DOOR
    }

    @Test
    fun `every vector link parses to its fields and rebuilds its challenge bytes and message`() {
        vectors.forEach { vector ->
            val link = (SignInLinks.parse(vector.string("link")) as SignInLinkParse.Ready).link
            link.origin shouldBe vector.string("origin")
            link.nonceHex shouldBe vector.string("nonce")
            link.exp.toString() shouldBe vector.getValue("exp").jsonPrimitive.content
            link.objectHex shouldBe vector.string("object")

            val challenge = SignInChallenge.challengeBytes(link)
            Hex.encode(challenge) shouldBe vector.string("challenge_bytes")
            challenge.size shouldBe CHALLENGE_LENGTH_FOR_PCOC_APP
            Hex.encode(SignInChallenge.bip340Message(challenge)) shouldBe vector.string("bip340_message_32")
        }
    }

    @Test
    fun `the published section 15_1 example is the first vector`() {
        val example = vectors.first()
        example.string("name") shouldBe "spec-15.1-example"
        example.string("bip340_message_32") shouldBe
            "69f340e811a370b22200bc7be89e8fd844548156bddcae55f449cb599470733a"
    }

    @Test
    fun `signed vectors match BIP-340 over the single SHA-256 and not over a second hash`() {
        val signed = vectors.filter { it.containsKey("signature") }
        signed.size shouldBe SIGNED_VECTORS
        signed.forEach { vector ->
            val secret = Hex.decode(vector.string("secret_key"))
            val publicKey = Hex.decode(vector.string("public_key"))
            val message = Hex.decode(vector.string("bip340_message_32"))
            val signature = Hex.decode(vector.string("signature"))
            val link = (SignInLinks.parse(vector.string("link")) as SignInLinkParse.Ready).link

            vector.string("aux_rand") shouldBe "00".repeat(32)
            signingPublicKey(secret)!!.contentEquals(publicKey) shouldBe true
            AdmissionSigner.sign(privateKey = secret, link = link) shouldBe vector.string("signature")
            CryptoUtils.verify(signature = signature, hash = message, pubKey = publicKey) shouldBe true
            CryptoUtils.verify(signature = signature, hash = sha256(message), pubKey = publicKey) shouldBe false
            CryptoUtils.verify(
                signature = Hex.decode(AdmissionSigner.signMessage(secret, sha256(message))),
                hash = message,
                pubKey = publicKey,
            ) shouldBe false
        }
    }

    @Test
    fun `signed vectors encode the six field body`() {
        vectors.filter { it.containsKey("body") }.forEach { vector ->
            val objectBytes = Hex.decode(vector.string("object_bytes_hex"))
            Hex.encode(sha256(objectBytes)) shouldBe vector.string("object")
            val link = (SignInLinks.parse(vector.string("link")) as SignInLinkParse.Ready).link

            val encoded = AdmissionPayload.encode(
                link = link,
                objectBytes = objectBytes,
                signatureHex = vector.string("signature"),
            )
            val body = CommonJson.parseToJsonElement(encoded).jsonObject
            body shouldBe vector.getValue("body").jsonObject
            body.keys shouldBe setOf("origin", "nonce", "exp", "object", "object_b64", "signature")
        }
    }

    @Test
    fun `accepted links parse and rejected links stop`() {
        val links = pack.getValue("links").jsonObject
        links.getValue("accept").jsonArray.forEach { raw ->
            (SignInLinks.parse(raw.jsonPrimitive.content) is SignInLinkParse.Ready) shouldBe true
        }
        val rejected = links.getValue("reject").jsonArray.map { it.jsonPrimitive.content }
        rejected.size shouldBe REJECTED_LINKS
        rejected.forEach { raw ->
            (SignInLinks.parse(raw) is SignInLinkParse.Ready) shouldBe false
        }
    }

    private fun JsonObject.string(name: String): String = getValue(name).jsonPrimitive.content

    private fun vectorFile(): File =
        listOf(
            File("src/test/resources/pcoc/sign-in.json"),
            File("app/src/test/resources/pcoc/sign-in.json"),
        ).first { it.isFile }

    private companion object {
        const val CHALLENGE_LENGTH_FOR_PCOC_APP = 109
        const val SIGNED_VECTORS = 3
        const val REJECTED_LINKS = 26
    }
}
