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
import xyz.tesser.sdk.WalletType
import xyz.tesser.sdk.error.TesserError
import java.util.Base64

/**
 * Resolves the wallet-creation activity body, stamps it, and returns the
 * composite `base64({body, stamp})` signature consumed by Tesser's API.
 *
 * For [WalletType.STABLECOIN_ETHEREUM] the body is the server-built
 * `ACTIVITY_TYPE_CREATE_WALLET_ACCOUNTS` payload fetched from Tesser and
 * stamped unchanged. Other wallet types keep the locally-built
 * `ACTIVITY_TYPE_CREATE_WALLET` body.
 *
 * Internal-use entry point. Public callers go through [xyz.tesser.sdk.LocalSigner].
 *
 * @param stamp Injected for testability; production calls pass [Stamp.create].
 */
internal suspend fun signCreateWalletInternal(
    signing: SigningConfig,
    params: CreateWalletParams,
    stamp: Stamp,
    api: TesserApiConnection,
): SignedResult {
    val body = resolveCreateWalletBody(signing, params, stamp, api)
    val stamped = stamp.stamp(signing, body)
    return buildSignedResult(body, stamped)
}

private suspend fun resolveCreateWalletBody(
    signing: SigningConfig,
    params: CreateWalletParams,
    stamp: Stamp,
    api: TesserApiConnection,
): String {
    if (params.type == WalletType.STABLECOIN_ETHEREUM) {
        return fetchCheckedCreateWalletAccountsBody(api, signing, params, stamp)
    }
    return buildCreateWalletBody(signing, params)
}

private fun buildCreateWalletBody(
    signing: SigningConfig,
    params: CreateWalletParams,
): String {
    val accounts =
        WALLET_TYPE_ACCOUNTS[params.type]
            ?: throw TesserError.ConfigError("No account spec registered for ${params.type}")

    return buildJsonObject {
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
}

private fun buildSignedResult(
    body: String,
    stamped: StampResult,
): SignedResult {
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
