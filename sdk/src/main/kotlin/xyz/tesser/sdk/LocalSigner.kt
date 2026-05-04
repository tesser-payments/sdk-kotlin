package xyz.tesser.sdk

import xyz.tesser.sdk.internal.signing.Stamp
import xyz.tesser.sdk.internal.signing.TurnkeyClient
import xyz.tesser.sdk.internal.signing.signCreateWalletInternal
import xyz.tesser.sdk.internal.signing.signStepInternal
import xyz.tesser.sdk.internal.util.logger

/**
 * Produces locally-signed activity payloads for Tesser API operations.
 *
 * Construction validates that all three [SigningConfig] fields are non-blank.
 *
 * **Thread-safe and reentrant.** Holds no mutable state. A single [LocalSigner]
 * may be shared across coroutines and dispatchers.
 *
 * @param signing API-key configuration (public key, private key, enclave ID).
 * @param stamp Injected for testability. Production callers omit this; the SDK
 *   creates the platform-appropriate stamper via [Stamp.create].
 */
public class LocalSigner internal constructor(
    public val signing: SigningConfig,
    private val stamp: Stamp,
    private val turnkey: TurnkeyClient,
) {
    /** Public constructor used by production callers. */
    public constructor(signing: SigningConfig) : this(signing, Stamp.create(), TurnkeyClient.create())

    init {
        require(signing.publicKey.isNotBlank()) {
            "SigningConfig.publicKey must not be blank"
        }
        require(signing.privateKey.isNotBlank()) {
            "SigningConfig.privateKey must not be blank"
        }
        require(signing.enclaveId.isNotBlank()) {
            "SigningConfig.enclaveId must not be blank"
        }
        log.debug("LocalSigner constructed for enclaveId={}", signing.enclaveId)
    }

    /**
     * Build and stamp an `ACTIVITY_TYPE_CREATE_WALLET` payload locally.
     *
     * The returned [SignedResult.signature] is the exact value to pass into Tesser's
     * wallet-creation request body. No HTTP is performed; this is a pure compute call.
     *
     * @throws xyz.tesser.sdk.error.TesserError.ConfigError if the wallet type
     *   has no registered account spec.
     * @throws xyz.tesser.sdk.error.TesserError.SigningError if the underlying
     *   stamper fails (malformed key, OS crypto provider error, etc.).
     */
    public suspend fun signCreateWallet(params: CreateWalletParams): SignedResult = signCreateWalletInternal(signing, params, stamp)

    /**
     * Sign a rebalance step locally and return the value Tesser's `/sign`
     * endpoint expects.
     *
     * The returned [SignedStepResult.signature] is the exact value to pass
     * into the Tesser API's `/sign` request body for that step:
     * `POST /v1/treasury/rebalances/{transferId}/steps/{stepId}/sign` with
     * body `{"signature": result.signature}`.
     *
     * **Network use.** This function currently performs an HTTPS roundtrip
     * to Turnkey under the hood (an internal SDK detail; see the SDK
     * implementation plan). Treat the call as I/O. A future SDK release
     * will move that hop server-side without changing this method's
     * signature or the meaning of [SignedStepResult.signature].
     *
     * @throws xyz.tesser.sdk.error.TesserError.ConfigError if [StepForSigning.network]
     *   is not a supported network identifier.
     * @throws xyz.tesser.sdk.error.TesserError.SigningError if the local
     *   stamper fails or Turnkey returns a non-success activity status.
     * @throws xyz.tesser.sdk.error.TesserError.ConnectionError if the
     *   Turnkey call fails at the transport layer (DNS, connect, IO).
     * @throws xyz.tesser.sdk.error.TesserError.TimeoutError if the Turnkey
     *   call exceeds its internal timeout.
     */
    public suspend fun signStep(
        step: StepForSigning,
        opts: SignStepOptions = SignStepOptions(),
    ): SignedStepResult = signStepInternal(signing, step, opts, stamp, turnkey)

    private companion object {
        private val log = logger("xyz.tesser.sdk.LocalSigner")
    }
}
