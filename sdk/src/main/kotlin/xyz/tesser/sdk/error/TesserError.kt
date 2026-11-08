package xyz.tesser.sdk.error

/**
 * Sealed root of all SDK errors. Branch on `when (err)` for exhaustive matching.
 *
 * The signer path throws [ConfigError] for bad caller input and [SigningError]
 * for cryptographic failures or a rejected unsigned payload. [APIError],
 * [ConnectionError], and [TimeoutError] come from the request
 * `LocalSigner.signCreateWallet` makes to Tesser for Ethereum wallets.
 * [ConnectionError], [TimeoutError], and an [APIError] with status 429 or 5xx
 * are safe to retry; an [APIError] with any other 4xx status fails the same
 * way on retry.
 */
public sealed class TesserError(message: String, cause: Throwable? = null) : Exception(message, cause) {
    /** Bad input at construction or call site. SDK-side validation, not Tesser API. */
    public class ConfigError(message: String, cause: Throwable? = null) : TesserError(message, cause)

    /**
     * Non-2xx HTTP from Tesser. Carries the parsed `errors[]` envelope per
     * https://docs.tesser.xyz/overviews/errors.
     */
    public class APIError(
        message: String,
        public val status: Int,
        public val headers: Map<String, List<String>> = emptyMap(),
        public val requestId: String? = null,
        public val errors: List<TesserErrorDetail> = emptyList(),
        cause: Throwable? = null,
    ) : TesserError(message, cause) {
        /** Convenience: the first `errorCode` from [errors], or `null` if empty. */
        public val errorCode: String? get() = errors.firstOrNull()?.errorCode

        /** Returns true if any entry in [errors] has an `errorCode` matching one of [codes]. */
        public fun hasCode(vararg codes: String): Boolean = errors.any { it.errorCode in codes }
    }

    /** Network or transport failure (DNS, ECONNRESET, IOException). */
    public class ConnectionError(message: String, cause: Throwable? = null) : TesserError(message, cause)

    /** Request exceeded the configured timeout. */
    public class TimeoutError(message: String, cause: Throwable? = null) : TesserError(message, cause)

    /**
     * Local signing failure. Wraps cryptographic signing errors and any
     * signing-pipeline failure. Flat (not sealed); sealing remains a
     * non-breaking change later if variants are needed.
     */
    public class SigningError(message: String, cause: Throwable? = null) : TesserError(message, cause)
}
