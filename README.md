# Tesser Kotlin SDK

Kotlin SDK for the [Tesser API](https://docs.tesser.xyz). Produces locally-signed
payloads (wallet creation, rebalance step signing) ready to submit to the
Tesser API. Targets JVM 17+ and Kotlin 2.0+.

## Install

**Gradle (Kotlin DSL):**

```kotlin
dependencies {
    implementation("xyz.tesser:sdk:0.0.2")
}
```

**Maven:**

```xml
<dependency>
    <groupId>xyz.tesser</groupId>
    <artifactId>sdk</artifactId>
    <version>0.0.2</version>
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

For runnable end-to-end scripts, see [`examples/create-wallet`](./examples/create-wallet)
(wallet creation), [`examples/sign-rebalance-step-webhooks`](./examples/sign-rebalance-step-webhooks)
(rebalance step signing driven by Tesser webhooks), and
[`examples/sign-rebalance-step-polling`](./examples/sign-rebalance-step-polling)
(same flow, polling-based — use this while webhook delivery is unreliable).
Each example's README walks through env setup, the runtime sequence, and
troubleshooting.

## What's included

- `LocalSigner.signCreateWallet(...)` builds and signs an
  `ACTIVITY_TYPE_CREATE_WALLET` payload locally. No network calls; the private
  key never leaves the JVM.
- `LocalSigner.signStep(...)` signs the unsigned transaction bytes that the
  Tesser API delivers via a `step.signature_requested` webhook event. The
  resulting signature is the value submitted back to
  `POST /v1/treasury/rebalances/{transferId}/steps/{stepId}/sign`.
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
