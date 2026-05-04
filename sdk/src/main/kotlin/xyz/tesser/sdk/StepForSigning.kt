package xyz.tesser.sdk

/**
 * A rebalance step that needs a local signature before the Tesser API can
 * execute it. The values come from the `data.object` field of a
 * `step.signature_requested` webhook event (or from
 * `GET /v1/treasury/rebalances/{id}` once the step is populated); see
 * https://docs.tesser.xyz/how-tos/rebalance-funds for the full payload shape.
 *
 * @property id The step ID. Used as the `{stepId}` URL path component when
 *   submitting the signature back to
 *   `POST /v1/treasury/rebalances/{transferId}/steps/{stepId}/sign`.
 * @property transferId The parent transfer/rebalance ID. Used as the
 *   `{transferId}` URL path component on the `/sign` endpoint.
 * @property unsignedTransaction Hex-encoded raw transaction bytes the Tesser
 *   API has prepared for this step (for example, an EIP-1559 transaction
 *   beginning `0x02...`).
 * @property signWith The on-chain address that must sign the transaction.
 *   Fetch via `GET /v1/accounts/{from_account_id}` and use
 *   `crypto_wallet_address`.
 * @property network The Tesser-style network identifier from the step
 *   (`step.from_network`), e.g. `BASE_SEPOLIA`, `ETHEREUM`, `SOLANA`. Used
 *   to derive the Turnkey `TRANSACTION_TYPE_*` for the underlying activity.
 *   Throws [xyz.tesser.sdk.error.TesserError.ConfigError] at sign time for
 *   unsupported networks.
 */
public data class StepForSigning(
    val id: String,
    val transferId: String,
    val unsignedTransaction: String,
    val signWith: String,
    val network: String,
)
