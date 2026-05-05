package xyz.tesser.sdk.internal.signing

import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import xyz.tesser.sdk.CreateWalletParams
import xyz.tesser.sdk.SignedResult
import xyz.tesser.sdk.SignedResultMetadata
import xyz.tesser.sdk.SigningConfig
import xyz.tesser.sdk.error.TesserError
import java.util.Base64

/**
 * Builds an `ACTIVITY_TYPE_CREATE_WALLET` payload, stamps it, and returns the
 * composite `base64({body, stamp})` signature consumed by Tesser's API.
 *
 * Internal-use entry point. Public callers go through [xyz.tesser.sdk.LocalSigner].
 *
 * @param stamp Injected for testability; production calls pass [Stamp.create].
 */
internal suspend fun signCreateWalletInternal(
    signing: SigningConfig,
    params: CreateWalletParams,
    stamp: Stamp,
): SignedResult {
    val accounts =
        WALLET_TYPE_ACCOUNTS[params.type]
            ?: throw TesserError.ConfigError("No account spec registered for ${params.type}")

    val body =
        buildJsonObject {
            put("type", "ACTIVITY_TYPE_CREATE_WALLET")
            put("timestampMs", System.currentTimeMillis().toString())
            put("organizationId", signing.enclaveId)
            putJsonObject("parameters") {
                put("walletName", params.name)
                putJsonArray("accounts") {
                    accounts.forEach { spec ->
                        addJsonObject {
                            put("curve", spec.curve)
                            put("pathFormat", spec.pathFormat)
                            put("path", spec.path)
                            put("addressFormat", spec.addressFormat)
                        }
                    }
                }
            }
        }.toString()

    val stamped = stamp.stamp(signing, body)

    val composite =
        buildJsonObject {
            put("body", body)
            put("stamp", stamped.stampHeaderValue)
        }.toString()

    val signature = Base64.getEncoder().encodeToString(composite.toByteArray(Charsets.UTF_8))

    return SignedResult(
        signature = signature,
        metadata =
            SignedResultMetadata(
                stampHeaderName = stamped.stampHeaderName,
                stampHeaderValue = stamped.stampHeaderValue,
                body = body,
            ),
    )
}
