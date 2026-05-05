# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Added
- `LocalSigner.signCreateWallet(...)` for producing locally-signed
  wallet-creation payloads ready to submit to the Tesser API.
- Public types: `SigningConfig`, `WalletType`, `CreateWalletParams`,
  `SignedResult`, and a sealed `TesserError` hierarchy.
- `examples/create-wallet` reference script that performs the full OAuth
  handshake and submits a wallet-creation request against Tesser staging.

[Unreleased]: https://github.com/tesser-payments/sdk-kotlin/compare/v0.0.1...HEAD
