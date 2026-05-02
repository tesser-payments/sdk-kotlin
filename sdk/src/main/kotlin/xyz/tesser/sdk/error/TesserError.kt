package xyz.tesser.sdk.error

/**
 * Sealed root of all SDK errors. Branch on `when (err)` for exhaustive matching.
 *
 * v0.0.1 (signer-only) only ever throws [ConfigError] and [SigningError];
 * [APIError], [ConnectionError], and [TimeoutError] are part of the public surface
 * now so Phase B can wire them up without breaking the API.
 */
public sealed class TesserError(message: String, cause: Throwable? = null) : Exception(message, cause) {
    /** Bad input at construction or call site. SDK-side validation, not Tesser API. */
    public class ConfigError(message: String, cause: Throwable? = null) : TesserError(message, cause)

    /**
     * Non-2xx HTTP from Tesser. Carries the parsed `errors[]` envelope per
     * https://docs.tesser.xyz/overviews/errors.
     *
     * **Never thrown in v0.0.1** — included in the public surface so Phase B
     * doesn't need to break the API. Phase B's HTTP layer (§7.11 of the design
     * spec) constructs this with `requestId = response.headers["request-id"]?.firstOrNull()`.
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

    /** Network/transport failure (DNS, ECONNRESET, IOException). Phase B. */
    public class ConnectionError(message: String, cause: Throwable? = null) : TesserError(message, cause)

    /** Request exceeded the configured timeout. Phase B. */
    public class TimeoutError(message: String, cause: Throwable? = null) : TesserError(message, cause)

    /**
     * Local signing-related failure. Wraps Turnkey stamper failures and any
     * signing-pipeline error. Flat (not sealed) — sealing remains a non-breaking
     * change later if variants are needed.
     */
    public class SigningError(message: String, cause: Throwable? = null) : TesserError(message, cause)
}
