# Tesser Kotlin SDK

Kotlin SDK for the [Tesser API](https://docs.tesser.xyz). v0.0.1 ships local
Turnkey signing for `signCreateWallet`. JVM 17+, Kotlin 2.0+.

## Install (Maven Central — once published)

```kotlin
// build.gradle.kts
dependencies {
  implementation("xyz.tesser:sdk:0.0.1")
}
```

## Quick start

```kotlin
import kotlinx.coroutines.runBlocking
import xyz.tesser.sdk.CreateWalletParams
import xyz.tesser.sdk.LocalSigner
import xyz.tesser.sdk.SigningConfig
import xyz.tesser.sdk.WalletType

fun main() = runBlocking {
  val signer = LocalSigner(
    SigningConfig(
      publicKey  = System.getenv("SIGNING_PUBLIC_KEY"),
      privateKey = System.getenv("SIGNING_PRIVATE_KEY"),
      enclaveId  = System.getenv("SIGNING_ENCLAVE_ID"),
    ),
  )

  val signed = signer.signCreateWallet(
    CreateWalletParams(name = "My wallet", type = WalletType.STABLECOIN_ETHEREUM),
  )

  // Pass `signed.signature` directly into Tesser's POST /v1/accounts/wallets request body:
  // { "signature": signed.signature, "name": "My wallet", "type": "stablecoin_ethereum", "is_managed": true }
  println(signed.signature)
}
```

See [`examples/create-wallet`](./examples/create-wallet) for an end-to-end script
that performs the OAuth handshake and submits the request against Tesser staging.
The [examples README](./examples/README.md) walks through env-file setup and
troubleshooting step-by-step.

## What's in v0.0.1

- `LocalSigner.signCreateWallet(...)` — pure local Turnkey stamp; no HTTP, no RPC.
- Wallet types: `STABLECOIN_ETHEREUM`, `STABLECOIN_SOLANA`, `STABLECOIN_STELLAR`.
- Sealed `TesserError` hierarchy with `errors[]` envelope support — Phase B's HTTP
  layer fills it in; v0.0.1 only ever throws `ConfigError` or `SigningError`.

## What's coming in Phase B

- `TesserClient` with pluggable `HttpTransport` (default JDK 11; opt-in `:sdk-ktor`
  companion module for Ktor users).
- `LocalSigner.signStep(...)` for ERC-20 transfer signing.
- Caller-managed OAuth via `TesserAuthConfig.Lambda { ... }`.

See [`docs/superpowers/specs/2026-04-30-kotlin-sdk-design.md`](./docs/superpowers/specs/2026-04-30-kotlin-sdk-design.md)
(local-only) for the full Phase B design.

## Building

```bash
./gradlew build       # compile + test + lint
./gradlew test        # tests only
./gradlew ktlintCheck # lint only
```

## License

[Apache 2.0](./LICENSE).
