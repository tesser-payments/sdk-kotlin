package xyz.tesser.sdk.internal.signing

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import xyz.tesser.sdk.SignStepOptions
import xyz.tesser.sdk.SignedStepResult
import xyz.tesser.sdk.SignedStepResultMetadata
import xyz.tesser.sdk.SigningConfig
import xyz.tesser.sdk.StepForSigning

/**
 * Builds an `ACTIVITY_TYPE_SIGN_TRANSACTION_V2` Turnkey activity for the
 * supplied rebalance step, stamps it with the caller's API key, and (for
 * now) submits it to Turnkey directly. The returned
 * [SignedStepResult.signature] is the post-Turnkey signed-transaction hex
 * string, ready to drop into Tesser's `/sign` request body.
 *
 * Internal-use entry point. Public callers go through [xyz.tesser.sdk.LocalSigner].
 *
 * **Temporary Turnkey hop.** Tesser's `/sign` endpoint should accept the
 * `base64({body, stamp})` envelope (as `/v1/accounts/wallets` does for
 * `signCreateWallet`) and forward to Turnkey internally. While that work is
 * in progress on the API side, the SDK does the Turnkey roundtrip itself so
 * the public contract is stable: `result.signature` always means "the value
 * to put in the `signature` field of the Tesser request." When the API
 * change ships, this function is rewritten to produce the base64 wrap and
 * skip [TurnkeyClient]; no SDK consumer code changes.
 *
 * @param opts Reserved for future per-call tuning. Currently unused but kept
 *   in the method signature so callers can stay source-compatible when
 *   options are added in a later release.
 * @param stamp Injected for testability; production calls pass [Stamp.create].
 * @param turnkey Injected for testability; production calls pass [TurnkeyClient.create].
 */
internal suspend fun signStepInternal(
    signing: SigningConfig,
    step: StepForSigning,
    @Suppress("UNUSED_PARAMETER") opts: SignStepOptions,
    stamp: Stamp,
    turnkey: TurnkeyClient,
): SignedStepResult {
    val turnkeyType = networkToTurnkeyType(step.network)

    val body =
        buildJsonObject {
            put("type", "ACTIVITY_TYPE_SIGN_TRANSACTION_V2")
            put("timestampMs", System.currentTimeMillis().toString())
            put("organizationId", signing.enclaveId)
            putJsonObject("parameters") {
                put("signWith", step.signWith)
                put("unsignedTransaction", step.unsignedTransaction)
                put("type", turnkeyType)
            }
        }.toString()

    val stamped = stamp.stamp(signing, body)

    val signedTransaction = turnkey.signTransaction(body, stamped)

    return SignedStepResult(
        signature = signedTransaction,
        unsignedTransaction = step.unsignedTransaction,
        metadata =
            SignedStepResultMetadata(
                stampHeaderName = stamped.stampHeaderName,
                stampHeaderValue = stamped.stampHeaderValue,
                body = body,
            ),
    )
}
