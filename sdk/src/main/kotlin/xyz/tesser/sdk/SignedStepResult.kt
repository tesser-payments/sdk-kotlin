package xyz.tesser.sdk

/**
 * Output of [LocalSigner.signStep].
 *
 * @property signature The opaque value to pass straight into Tesser's
 *   `/sign` request body as the `signature` field:
 *   `POST /v1/treasury/rebalances/{transferId}/steps/{stepId}/sign` with
 *   body `{"signature": signedStepResult.signature}`. Treat this as opaque
 *   wire format; the exact encoding is an SDK implementation detail and
 *   may change between SDK versions without notice.
 * @property unsignedTransaction Echo of the `unsignedTransaction` field from
 *   the input [StepForSigning]. Useful for logging and audit trails.
 * @property metadata Diagnostic context: the inner stamp header values and
 *   the exact body that was stamped. Useful for debugging; not required for
 *   the request itself.
 */
public data class SignedStepResult(
    val signature: String,
    val unsignedTransaction: String,
    val metadata: SignedStepResultMetadata,
)

/**
 * Diagnostic metadata attached to a [SignedStepResult].
 *
 * @property stampHeaderName The stamp header name (typically `"X-Stamp"`).
 * @property stampHeaderValue The base64url-encoded stamp value.
 * @property body The exact JSON bytes that were stamped (the Turnkey
 *   `ACTIVITY_TYPE_SIGN_TRANSACTION_V2` activity request).
 */
public data class SignedStepResultMetadata(
    val stampHeaderName: String,
    val stampHeaderValue: String,
    val body: String,
)
