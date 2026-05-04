# Tesser Kotlin SDK

Kotlin SDK for the [Tesser API](https://docs.tesser.xyz). Produces locally-signed
wallet-creation payloads ready to submit to the Tesser API. Targets JVM 17+ and
Kotlin 2.0+.

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

Published on Maven Central once the first release tag is cut.

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

  // Pass `signed.signature` straight into Tesser's POST /v1/accounts/wallets body:
  //   { "signature": signed.signature,
  //     "name": "My wallet",
  //     "type": "stablecoin_ethereum",
  //     "is_managed": true }
  println(signed.signature)
}
```

For a runnable end-to-end script that performs the OAuth handshake and submits
the request against Tesser staging, see [`examples/create-wallet`](./examples/create-wallet).
The [examples README](./examples/README.md) walks through env setup and
troubleshooting.

## What's included

- `LocalSigner.signCreateWallet(...)` builds and signs an
  `ACTIVITY_TYPE_CREATE_WALLET` payload locally. No network calls; the private
  key never leaves the JVM.
- Three wallet types: `STABLECOIN_ETHEREUM`, `STABLECOIN_SOLANA`, and
  `STABLECOIN_STELLAR`. Ethereum is exercised end-to-end against Tesser
  staging. Solana and Stellar are not yet verified against the live API.
- Sealed `TesserError` hierarchy. The signer throws `ConfigError` for bad
  input (blank keys, unknown wallet type) and `SigningError` for any
  cryptographic failure.

## Contributing

Building, testing, lint, binary compatibility checks, and the release runbook
live in [CONTRIBUTING.md](./CONTRIBUTING.md).

## License

[Apache 2.0](./LICENSE).
