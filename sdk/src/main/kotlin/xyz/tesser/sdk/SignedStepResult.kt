package xyz.tesser.sdk

/**
 * Output of [LocalSigner.signStep].
 *
 * @property signature Signature for the step's transaction. Pass this
 *   straight into Tesser's `/sign` request body as the `signature` field:
 *   `POST /v1/treasury/rebalances/{transferId}/steps/{stepId}/sign` with
 *   body `{"signature": signedStepResult.signature}`. Tesser completes the
 *   transfer and broadcasts it.
 * @property unsignedTransaction Echo of the `unsignedTransaction` field from
 *   the input [StepForSigning]. Useful for logging and audit trails.
 * @property metadata Diagnostic context: the signature header and the exact
 *   request that was signed. Useful for debugging; not required for the
 *   request itself.
 */
public data class SignedStepResult(
    val signature: String,
    val unsignedTransaction: String,
    val metadata: SignedStepResultMetadata,
)

/**
 * Diagnostic metadata attached to a [SignedStepResult].
 *
 * @property stampHeaderName The signature header name.
 * @property stampHeaderValue The base64url-encoded signature header value.
 * @property body The exact JSON request that was signed.
 */
public data class SignedStepResultMetadata(
    val stampHeaderName: String,
    val stampHeaderValue: String,
    val body: String,
)
