package xyz.tesser.sdk.error

/**
 * Sealed root of all SDK errors. Branch on `when (err)` for exhaustive matching.
 *
 * The signer path throws [ConfigError] for bad caller input and [SigningError]
 * for cryptographic failures. [APIError], [ConnectionError], and [TimeoutError]
 * are part of the public surface so future HTTP-issuing operations can throw
 * them without breaking the API.
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
     * Local signing failure. Wraps cryptographic stamper errors and any
     * signing-pipeline failure. Flat (not sealed); sealing remains a
     * non-breaking change later if variants are needed.
     */
    public class SigningError(message: String, cause: Throwable? = null) : TesserError(message, cause)
}
