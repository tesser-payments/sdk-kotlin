package xyz.tesser.sdk

/**
 * Output of [LocalSigner.signCreateWallet].
 *
 * @property signature Signature for the wallet-creation request. Pass this
 *   value straight into Tesser's wallet-creation request body as the
 *   `signature` field.
 * @property metadata Diagnostic context: the signature header and the exact
 *   request that was signed. Useful for logging and debugging; not required
 *   for the request itself.
 */
public data class SignedResult(
    val signature: String,
    val metadata: SignedResultMetadata,
)

/**
 * Diagnostic metadata attached to a [SignedResult].
 *
 * @property stampHeaderName The signature header name.
 * @property stampHeaderValue The base64url-encoded signature header value.
 * @property body The exact JSON request that was signed.
 */
public data class SignedResultMetadata(
    val stampHeaderName: String,
    val stampHeaderValue: String,
    val body: String,
)
