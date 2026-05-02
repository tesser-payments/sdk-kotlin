package xyz.tesser.sdk

/**
 * Output of [LocalSigner.signCreateWallet].
 *
 * @property signature Base64(JSON-encoded `{body, stamp}`) — pass straight into
 *   Tesser's wallet-creation request body as the `signature` field.
 * @property metadata Diagnostic context (the inner stamp header values, the body that
 *   was stamped). Useful for logging and debugging; not required for the request.
 */
public data class SignedResult(
    val signature: String,
    val metadata: SignedResultMetadata,
)

/**
 * Diagnostic metadata attached to a [SignedResult].
 *
 * @property stampHeaderName The Turnkey stamp header name (typically `"X-Stamp"`).
 * @property stampHeaderValue The base64url-encoded stamp value.
 * @property body The exact JSON body that was stamped.
 */
public data class SignedResultMetadata(
    val stampHeaderName: String,
    val stampHeaderValue: String,
    val body: String,
)
