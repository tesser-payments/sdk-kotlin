package xyz.tesser.sdk.internal.signing

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import xyz.tesser.sdk.SignStepOptions
import xyz.tesser.sdk.SignedStepResult
import xyz.tesser.sdk.SignedStepResultMetadata
import xyz.tesser.sdk.SigningConfig
import xyz.tesser.sdk.StepForSigning
import java.util.Base64

/**
 * Builds an `ACTIVITY_TYPE_SIGN_TRANSACTION_V2` Turnkey activity for the
 * supplied rebalance step, stamps it with the caller's API key, and returns
 * the composite `base64({body, stamp})` envelope consumed by Tesser's
 * `/v1/treasury/rebalances/{transferId}/steps/{stepId}/sign` endpoint. Tesser
 * forwards the activity to Turnkey on the caller's behalf — same calling
 * pattern as `signCreateWallet`.
 *
 * Internal-use entry point. Public callers go through [xyz.tesser.sdk.LocalSigner].
 *
 * @param opts Reserved for future per-call tuning. Currently unused but kept
 *   in the method signature so callers can stay source-compatible when
 *   options are added in a later release.
 * @param stamp Injected for testability; production calls pass [Stamp.create].
 */
internal suspend fun signStepInternal(
    signing: SigningConfig,
    step: StepForSigning,
    @Suppress("UNUSED_PARAMETER") opts: SignStepOptions,
    stamp: Stamp,
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

    val composite =
        buildJsonObject {
            put("body", body)
            put("stamp", stamped.stampHeaderValue)
        }.toString()

    val signature = Base64.getEncoder().encodeToString(composite.toByteArray(Charsets.UTF_8))

    return SignedStepResult(
        signature = signature,
        unsignedTransaction = step.unsignedTransaction,
        metadata =
            SignedStepResultMetadata(
                stampHeaderName = stamped.stampHeaderName,
                stampHeaderValue = stamped.stampHeaderValue,
                body = body,
            ),
    )
}
