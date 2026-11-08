package xyz.tesser.sdk

/**
 * Signing-key configuration for [LocalSigner].
 *
 * @property publicKey 33-byte compressed P-256 (secp256r1) public key in hex
 *   (66 characters, prefixed with `02` or `03`). The SDK does NOT auto-compress
 *   on the client side. If your key was registered in uncompressed form, fix
 *   the registration upstream.
 * @property privateKey Raw 32-byte P-256 private scalar in hex (64 characters).
 * @property enclaveId Enclave ID shown with your signing key in the Tesser
 *   dashboard.
 */
public data class SigningConfig(
    val publicKey: String,
    val privateKey: String,
    val enclaveId: String,
)
