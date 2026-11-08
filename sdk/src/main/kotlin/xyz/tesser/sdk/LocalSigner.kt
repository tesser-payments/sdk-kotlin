package xyz.tesser.sdk

import xyz.tesser.sdk.internal.signing.DEFAULT_API_BASE_URLS
import xyz.tesser.sdk.internal.signing.Stamp
import xyz.tesser.sdk.internal.signing.TesserApiConnection
import xyz.tesser.sdk.internal.signing.createDefaultHttpClient
import xyz.tesser.sdk.internal.signing.signCreateWalletInternal
import xyz.tesser.sdk.internal.signing.signStepInternal
import xyz.tesser.sdk.internal.util.logger
import java.net.http.HttpClient

/**
 * Signs Tesser API requests locally with your API key.
 *
 * Construction validates that all three [SigningConfig] fields are non-blank
 * and that `apiBaseUrl`, if set, is an https URL.
 *
 * **Thread-safe and reentrant.** The only state is the environment remembered
 * after the first Ethereum wallet request; a single [LocalSigner] may be shared
 * across concurrent calls.
 *
 * @param signing API-key configuration (public key, private key, enclave ID).
 * @param apiBaseUrl Optional Tesser API base URL. Leave it unset and the signer
 *   finds the environment your signing key belongs to, production or sandbox.
 *   When set, only this URL is used: `https://api.tesser.xyz` in production,
 *   `https://api-sandbox.tesser.xyz` in sandbox. A trailing `/` or `/v1` is removed.
 * @param httpClient HTTP client used for requests to Tesser. Inject one
 *   to configure a proxy or TLS settings.
 * @param stamp Injected for testability. Production callers use the public
 *   constructor, which omits it.
 */
public class LocalSigner internal constructor(
    public val signing: SigningConfig,
    apiBaseUrl: String?,
    httpClient: HttpClient,
    private val stamp: Stamp,
    defaultApiBaseUrls: List<String>,
) {
    internal constructor(
        signing: SigningConfig,
        apiBaseUrl: String?,
        httpClient: HttpClient,
        stamp: Stamp,
    ) : this(signing, apiBaseUrl, httpClient, stamp, DEFAULT_API_BASE_URLS)

    /**
     * Public constructor used by production callers.
     *
     * @param signing API-key configuration (public key, private key, enclave ID).
     * @param apiBaseUrl Optional Tesser API base URL. Leave it unset and the signer
     *   finds the environment your signing key belongs to, production or sandbox.
     *   When set, only this URL is used: `https://api.tesser.xyz` in production,
     *   `https://api-sandbox.tesser.xyz` in sandbox. A trailing `/` or `/v1` is removed.
     * @param httpClient HTTP client used for requests to Tesser. Defaults to
     *   a JDK [HttpClient].
     */
    @JvmOverloads
    public constructor(
        signing: SigningConfig,
        apiBaseUrl: String? = null,
        httpClient: HttpClient = createDefaultHttpClient(),
    ) : this(signing, apiBaseUrl, httpClient, Stamp.create())

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

    private val api = TesserApiConnection.create(apiBaseUrl, httpClient, defaultApiBaseUrls)

    /**
     * Sign a wallet-creation request.
     *
     * For [WalletType.STABLECOIN_ETHEREUM] the SDK first fetches the request to sign
     * from the Tesser API and checks that it matches [params]. Other wallet types are
     * signed locally without a request to Tesser.
     *
     * The returned [SignedResult.signature] is the exact value to pass into Tesser's
     * wallet-creation request body. The private key never leaves the JVM.
     *
     * @throws xyz.tesser.sdk.error.TesserError.ConfigError if the wallet type
     *   has no registered account spec.
     * @throws xyz.tesser.sdk.error.TesserError.SigningError if signing fails
     *   (malformed key, OS crypto provider error, etc.) or the request fetched
     *   from Tesser does not match [params].
     * @throws xyz.tesser.sdk.error.TesserError.APIError if Tesser answers with an
     *   error status or an invalid response. Safe to retry for status 429 or 5xx;
     *   other 4xx statuses fail the same way on retry. Without `apiBaseUrl`, if
     *   neither production nor sandbox accepts your signing key, the error from
     *   production is thrown.
     * @throws xyz.tesser.sdk.error.TesserError.ConnectionError if the request
     *   to Tesser fails at the transport level. Safe to retry.
     * @throws xyz.tesser.sdk.error.TesserError.TimeoutError if the request to
     *   Tesser times out. Safe to retry.
     */
    public suspend fun signCreateWallet(params: CreateWalletParams): SignedResult = signCreateWalletInternal(signing, params, stamp, api)

    /**
     * Sign the transaction of a rebalance step.
     *
     * The signature authorizes the transaction with your API key, without
     * changing it. The returned [SignedStepResult.signature] is the exact value
     * to pass into the Tesser API's `/sign` request body for that step:
     * `POST /v1/treasury/rebalances/{transferId}/steps/{stepId}/sign` with
     * body `{"signature": result.signature}`. No HTTP is performed; this is a
     * pure compute call.
     *
     * @throws xyz.tesser.sdk.error.TesserError.ConfigError if [StepForSigning.network]
     *   is not a supported network identifier.
     * @throws xyz.tesser.sdk.error.TesserError.SigningError if signing fails
     *   (malformed key, OS crypto provider error, etc.).
     */
    public suspend fun signStep(
        step: StepForSigning,
        opts: SignStepOptions = SignStepOptions(),
    ): SignedStepResult = signStepInternal(signing, step, opts, stamp)

    private companion object {
        private val log = logger("xyz.tesser.sdk.LocalSigner")
    }
}
