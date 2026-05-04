# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

## [0.0.2] - 2026-05-03

### Added
- `LocalSigner.signStep(...)` for producing locally-signed signatures over
  the unsigned transaction bytes carried by a Tesser rebalance step.
- Public types: `StepForSigning`, `SignedStepResult`, `SignedStepResultMetadata`,
  and `SignStepOptions`.
- `examples/sign-rebalance-step` reference script that creates a rebalance,
  receives the `step.signature_requested` webhook, signs locally, and submits
  the signature to the Tesser API.

## [0.0.1] - 2026-05-03

### Added
- `LocalSigner.signCreateWallet(...)` for producing locally-signed
  wallet-creation payloads ready to submit to the Tesser API.
- Public types: `SigningConfig`, `WalletType`, `CreateWalletParams`,
  `SignedResult`, and a sealed `TesserError` hierarchy.
- `examples/create-wallet` reference script that performs the full OAuth
  handshake and submits a wallet-creation request against Tesser staging.

[Unreleased]: https://github.com/tesser-payments/sdk-kotlin/compare/v0.0.2...HEAD
[0.0.2]: https://github.com/tesser-payments/sdk-kotlin/compare/v0.0.1...v0.0.2
[0.0.1]: https://github.com/tesser-payments/sdk-kotlin/releases/tag/v0.0.1
