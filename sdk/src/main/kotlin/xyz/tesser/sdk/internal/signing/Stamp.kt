package xyz.tesser.sdk.internal.signing

import xyz.tesser.sdk.SigningConfig

/**
 * Result of a Turnkey API-key stamp operation.
 *
 * @property stampHeaderName The header name (typically `"X-Stamp"`).
 * @property stampHeaderValue Base64url-encoded JSON `{publicKey, signature, scheme}`.
 */
internal data class StampResult(
    val stampHeaderName: String,
    val stampHeaderValue: String,
)

/**
 * SDK-internal abstraction over Turnkey's API-key stamper.
 *
 * The single concrete implementation — [ApiKeyStamp] — is wired up in Task 7.
 */
internal interface Stamp {
    suspend fun stamp(
        keys: SigningConfig,
        body: String,
    ): StampResult

    companion object {
        /**
         * Returns the concrete [Stamp] implementation, currently [ApiKeyStamp].
         */
        fun create(): Stamp = ApiKeyStamp()
    }
}
