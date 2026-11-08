# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Added

- `LocalSigner.signStep(...)` supports `TEMPO` and `TEMPO_MODERATO` with `TRANSACTION_TYPE_TEMPO`. The SDK preserves the backend-provided transaction and authorizes customer signing; Tesser adds the sponsor signature.
- `LocalSigner` accepts an optional `java.net.http.HttpClient` for the
  unsigned-payload request, for example to configure a proxy.

### Changed

- **Breaking:** `LocalSigner` requires an `apiBaseUrl`
  (`LocalSigner(signing, apiBaseUrl)`): `https://api.tesser.xyz` in
  production, `https://api-sandbox.tesser.xyz` in sandbox. A trailing `/` or
  `/v1` is removed.
- `LocalSigner.signCreateWallet(...)` for `STABLECOIN_ETHEREUM` fetches an
  `ACTIVITY_TYPE_CREATE_WALLET_ACCOUNTS` body for the workspace's master
  wallet from `GET /v1/accounts/wallets/unsigned-payload` (one stamped request
  per call, 10 second timeout, no cache), checks its activity type,
  organization ID, single account name, and Ethereum address format, and
  stamps it unchanged. The method signature and the `base64({body, stamp})`
  result are unchanged. Solana and Stellar keep the locally-built
  `ACTIVITY_TYPE_CREATE_WALLET` body.
- `signCreateWallet` can throw `TesserError.APIError`,
  `TesserError.ConnectionError`, and `TesserError.TimeoutError` from the
  unsigned-payload request; all three are retryable. A payload that fails the
  checks throws `TesserError.SigningError` and nothing is stamped.

### Removed

- **Breaking:** the `LocalSigner(signing: SigningConfig)` constructor.

## [0.0.4] - 2026-07-07

### Added
- `LocalSigner.signStep(...)` supports `ETHEREUM_SEPOLIA`
  (`TRANSACTION_TYPE_ETHEREUM`).

### Fixed
- The rebalance examples send the `{"desired": {"from", "to"}}` request body.

## [0.0.3] - 2026-05-11

### Fixed
- The rebalance examples wait for the signed step's `completed` status instead
  of `finalized`.

## [0.0.2] - 2026-05-05

### Added
- `LocalSigner.signStep(...)` for producing a stamped Turnkey
  `ACTIVITY_TYPE_SIGN_TRANSACTION_V2` payload for a Tesser rebalance step.
  Returns a `base64({body, stamp})` envelope that drops straight into
  Tesser's `/sign` request body — same calling pattern as `signCreateWallet`.
- Public types: `StepForSigning` (carries `id`, `transferId`,
  `unsignedTransaction`, `signWith`, `network`), `SignedStepResult`,
  `SignedStepResultMetadata`, and `SignStepOptions`.
- `examples/sign-rebalance-step` reference script that creates a rebalance,
  signs the step locally, and submits the signature to the Tesser API.

## [0.0.1] - 2026-05-03

### Added
- `LocalSigner.signCreateWallet(...)` for producing locally-signed
  wallet-creation payloads ready to submit to the Tesser API.
- Public types: `SigningConfig`, `WalletType`, `CreateWalletParams`,
  `SignedResult`, and a sealed `TesserError` hierarchy.
- `examples/create-wallet` reference script that performs the full OAuth
  handshake and submits a wallet-creation request against Tesser staging.

[Unreleased]: https://github.com/tesser-payments/sdk-kotlin/compare/v0.0.4...HEAD
[0.0.4]: https://github.com/tesser-payments/sdk-kotlin/compare/v0.0.3...v0.0.4
[0.0.3]: https://github.com/tesser-payments/sdk-kotlin/compare/v0.0.2...v0.0.3
[0.0.2]: https://github.com/tesser-payments/sdk-kotlin/compare/v0.0.1...v0.0.2
[0.0.1]: https://github.com/tesser-payments/sdk-kotlin/releases/tag/v0.0.1
