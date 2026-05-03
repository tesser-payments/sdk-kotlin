# Tesser Kotlin SDK

Kotlin SDK for the [Tesser API](https://docs.tesser.xyz). JVM 17+, Kotlin 2.0+.

> **Status:** v0.0.1 — signer-only (`signCreateWallet`). HTTP layer, OAuth integration, and a `:sdk-ktor` companion module are planned for Phase B.

## Install

**Gradle (Kotlin DSL):**

```kotlin
dependencies {
    implementation("xyz.tesser:sdk:0.0.1")
}
```

**Maven:**

```xml
<dependency>
    <groupId>xyz.tesser</groupId>
    <artifactId>sdk</artifactId>
    <version>0.0.1</version>
</dependency>
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

## What's in v0.0.1

- `LocalSigner.signCreateWallet(...)` — pure local Turnkey stamp; no HTTP, no RPC.
- Wallet types: `STABLECOIN_ETHEREUM`, `STABLECOIN_SOLANA`, `STABLECOIN_STELLAR`.
- Sealed `TesserError` hierarchy with `errors[]` envelope support — Phase B's HTTP layer fills it in; v0.0.1 only ever throws `ConfigError` or `SigningError`.

## What's coming in Phase B

- `TesserClient` with pluggable `HttpTransport` (default JDK 11; opt-in `:sdk-ktor` companion module for Ktor users).
- `LocalSigner.signStep(...)` for ERC-20 transfer signing.
- Caller-managed OAuth via `TesserAuthConfig.Lambda { ... }`.

## End-to-end example

See [`examples/create-wallet`](./examples/create-wallet) for a runnable script that performs the OAuth handshake, signs the request locally, and submits it to Tesser staging. The [examples README](./examples/README.md) walks through env-file setup (`.env.example` template, `set -a && source` pattern), expected output, and a troubleshooting table covering common failure modes (bad credentials, wrong audience, key-format mistakes).

## Contributing / development

Building, testing, lint, binary-compatibility, and the release runbook live in [CONTRIBUTING.md](./CONTRIBUTING.md). Start there if you're working on the SDK itself rather than consuming it.

## License

[Apache 2.0](./LICENSE).
