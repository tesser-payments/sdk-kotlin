package xyz.tesser.sdk.internal.signing

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.ints.shouldBeGreaterThanOrEqual
import io.kotest.matchers.ints.shouldBeLessThanOrEqual
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldMatch
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.bouncycastle.asn1.ASN1InputStream
import org.bouncycastle.asn1.ASN1Sequence
import org.bouncycastle.crypto.digests.SHA256Digest
import org.bouncycastle.crypto.params.ECDomainParameters
import org.bouncycastle.crypto.params.ECPublicKeyParameters
import org.bouncycastle.crypto.signers.ECDSASigner
import org.bouncycastle.jce.ECNamedCurveTable
import org.junit.jupiter.api.Test
import xyz.tesser.sdk.SigningConfig
import xyz.tesser.sdk.error.TesserError
import java.util.Base64
import org.bouncycastle.asn1.ASN1Integer as Asn1Int

class StampTest {
    // Throwaway test-only P-256 scalar (the Bitcoin generator's `1` private key
    // in compressed-pub form). NEVER use for real signing.
    private val testCfg =
        SigningConfig(
            publicKey = "036b17d1f2e12c4247f8bce6e563a440f277037d812deb33a0f4a13945d898c296",
            privateKey = "0000000000000000000000000000000000000000000000000000000000000001",
            enclaveId = "org_test",
        )

    @Test
    fun `stamp returns the X-Stamp header name`() =
        runTest {
            val result = ApiKeyStamp().stamp(testCfg, body = """{"hello":"world"}""")
            result.stampHeaderName shouldBe "X-Stamp"
        }

    @Test
    fun `stamp value decodes to the canonical Turnkey stamp JSON envelope`() =
        runTest {
            val result = ApiKeyStamp().stamp(testCfg, body = """{"hello":"world"}""")
            val decoded = String(Base64.getUrlDecoder().decode(result.stampHeaderValue))
            val obj = Json.parseToJsonElement(decoded).jsonObject

            obj["publicKey"]!!.jsonPrimitive.content shouldBe testCfg.publicKey
            obj["scheme"]!!.jsonPrimitive.content shouldBe "SIGNATURE_SCHEME_TK_API_P256"
            val sigHex = obj["signature"]!!.jsonPrimitive.content
            sigHex shouldMatch Regex("^[0-9a-f]+$")
            // P-256 DER ECDSA signatures are 138–144 hex chars (69–72 bytes) in practice.
            sigHex.length shouldBeGreaterThanOrEqual 138
            sigHex.length shouldBeLessThanOrEqual 144
        }

    @Test
    fun `stamp wraps malformed private key as SigningError`() =
        runTest {
            val malformed = testCfg.copy(privateKey = "not-hex-zzz")
            shouldThrow<TesserError.SigningError> {
                ApiKeyStamp().stamp(malformed, body = "{}")
            }
        }

    @Test
    fun `stamp produces a signature that verifies against the body and public key`() =
        runTest {
            val body = """{"hello":"world"}"""
            val result = ApiKeyStamp().stamp(testCfg, body)

            // Decode envelope
            val decoded = String(Base64.getUrlDecoder().decode(result.stampHeaderValue))
            val obj = Json.parseToJsonElement(decoded).jsonObject
            val sigHex = obj["signature"]!!.jsonPrimitive.content
            val pubHex = obj["publicKey"]!!.jsonPrimitive.content

            // Decompose DER signature into (r, s)
            val sigBytes = sigHex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
            val seq = ASN1InputStream(sigBytes).use { it.readObject() as ASN1Sequence }
            val r = (seq.getObjectAt(0) as Asn1Int).value
            val s = (seq.getObjectAt(1) as Asn1Int).value

            // SHA-256 of body
            val bodyBytes = body.toByteArray(Charsets.UTF_8)
            val hash =
                ByteArray(32).also {
                    val d = SHA256Digest()
                    d.update(bodyBytes, 0, bodyBytes.size)
                    d.doFinal(it, 0)
                }

            // Decompress the 33-byte hex public key into a BC EC point and verify
            val spec = ECNamedCurveTable.getParameterSpec("secp256r1")
            val domain = ECDomainParameters(spec.curve, spec.g, spec.n, spec.h)
            val pubBytes = pubHex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
            val q = spec.curve.decodePoint(pubBytes)
            val pubKeyParams = ECPublicKeyParameters(q, domain)

            val verifier = ECDSASigner().apply { init(false, pubKeyParams) }
            verifier.verifySignature(hash, r, s) shouldBe true
        }
}
