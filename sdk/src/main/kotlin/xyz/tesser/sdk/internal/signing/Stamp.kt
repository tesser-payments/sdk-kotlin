package xyz.tesser.sdk.internal.signing

import xyz.tesser.sdk.SigningConfig

/**
 * Result of an API-key stamp operation.
 *
 * @property stampHeaderName The header name (typically `"X-Stamp"`).
 * @property stampHeaderValue Base64url-encoded JSON `{publicKey, signature, scheme}`.
 */
internal data class StampResult(
    val stampHeaderName: String,
    val stampHeaderValue: String,
)

/**
 * SDK-internal abstraction over the API-key stamper. The single concrete
 * implementation is [ApiKeyStamp]; the interface exists to keep the signing
 * pipeline testable.
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
