package xyz.tesser.sdk

/**
 * Turnkey signing-key configuration.
 *
 * @property publicKey 33-byte compressed P-256 (secp256r1) public key in hex (66 chars,
 *   prefix '02' or '03'). This is the format Turnkey's API-key registry stores.
 *   The SDK does NOT auto-compress on the client side — if your dashboard registered
 *   an uncompressed key, fix the registration upstream.
 * @property privateKey Raw 32-byte P-256 private scalar in hex (64 chars).
 * @property enclaveId Turnkey organization ID (sub-organization) the API key belongs to.
 */
public data class SigningConfig(
    val publicKey: String,
    val privateKey: String,
    val enclaveId: String,
)
