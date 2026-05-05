package xyz.tesser.sdk.internal.signing

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.bouncycastle.asn1.ASN1Integer
import org.bouncycastle.asn1.DLSequence
import org.bouncycastle.crypto.digests.SHA256Digest
import org.bouncycastle.crypto.params.ECDomainParameters
import org.bouncycastle.crypto.params.ECPrivateKeyParameters
import org.bouncycastle.crypto.signers.ECDSASigner
import org.bouncycastle.jce.ECNamedCurveTable
import xyz.tesser.sdk.SigningConfig
import xyz.tesser.sdk.error.TesserError
import java.math.BigInteger
import java.util.Base64

/**
 * Turnkey API-key stamper for the JVM.
 *
 * Produces a stamp identical in shape to the vendor SDK's wire format:
 *
 *     base64url(JSON({
 *       publicKey: <hex>,
 *       signature: <DER ECDSA(SHA-256(body)) hex>,
 *       scheme:    "SIGNATURE_SCHEME_TK_API_P256",
 *     }))
 *
 * Returned under the header name `"X-Stamp"`.
 *
 * Implementation strategy: P-256 key loading uses Bouncy Castle's
 * [ECNamedCurveTable] (secp256r1 / P-256). ECDSA signing and DER encoding
 * use Bouncy Castle (`bcprov-jdk15to18`). Only the JSON envelope shape and
 * base64url wrap are written here.
 */
internal class ApiKeyStamp : Stamp {
    override suspend fun stamp(
        keys: SigningConfig,
        body: String,
    ): StampResult {
        val sigDer =
            try {
                val privBytes = hexToBytes(keys.privateKey)
                val scalar = BigInteger(1, privBytes)
                val spec = ECNamedCurveTable.getParameterSpec("secp256r1")
                require(scalar >= BigInteger.ONE && scalar < spec.n) {
                    "Private key scalar is out of the valid range [1, n-1]"
                }
                val domainParams = ECDomainParameters(spec.curve, spec.g, spec.n, spec.h)
                val privateKeyParams = ECPrivateKeyParameters(scalar, domainParams)

                val bodyBytes = body.toByteArray(Charsets.UTF_8)
                val hash =
                    ByteArray(32).also {
                        val digest = SHA256Digest()
                        digest.update(bodyBytes, 0, bodyBytes.size)
                        digest.doFinal(it, 0)
                    }

                val signer = ECDSASigner().apply { init(true, privateKeyParams) }
                val rs = signer.generateSignature(hash) // BigInteger[2] = [r, s]
                DLSequence(arrayOf(ASN1Integer(rs[0]), ASN1Integer(rs[1]))).encoded
            } catch (e: Exception) {
                throw TesserError.SigningError(
                    "Signing failed: ${e.message}",
                    e,
                )
            }

        val stampJson =
            buildJsonObject {
                put("publicKey", keys.publicKey)
                put("signature", bytesToHex(sigDer))
                put("scheme", "SIGNATURE_SCHEME_TK_API_P256")
            }.toString()

        val encoded =
            Base64.getUrlEncoder().withoutPadding()
                .encodeToString(stampJson.toByteArray(Charsets.UTF_8))

        return StampResult(stampHeaderName = "X-Stamp", stampHeaderValue = encoded)
    }

    private fun hexToBytes(hex: String): ByteArray {
        require(hex.length % 2 == 0) { "Hex string must have even length" }
        require(hex.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) {
            "Hex string contains non-hex characters"
        }
        return ByteArray(hex.length / 2) { i ->
            ((hex[i * 2].digitToInt(16) shl 4) or hex[i * 2 + 1].digitToInt(16)).toByte()
        }
    }

    private fun bytesToHex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }
}
