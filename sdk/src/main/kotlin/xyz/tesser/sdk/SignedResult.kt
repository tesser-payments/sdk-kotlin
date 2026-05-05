package xyz.tesser.sdk

/**
 * Output of [LocalSigner.signCreateWallet].
 *
 * @property signature Base64-encoded JSON `{body, stamp}` envelope. Pass this
 *   value straight into Tesser's wallet-creation request body as the
 *   `signature` field.
 * @property metadata Diagnostic context: the inner stamp header values and the
 *   exact body that was stamped. Useful for logging and debugging; not
 *   required for the request itself.
 */
public data class SignedResult(
    val signature: String,
    val metadata: SignedResultMetadata,
)

/**
 * Diagnostic metadata attached to a [SignedResult].
 *
 * @property stampHeaderName The stamp header name (typically `"X-Stamp"`).
 * @property stampHeaderValue The base64url-encoded stamp value.
 * @property body The exact JSON body that was stamped.
 */
public data class SignedResultMetadata(
    val stampHeaderName: String,
    val stampHeaderValue: String,
    val body: String,
)
