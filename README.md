# Tesser Kotlin SDK

Kotlin SDK for the [Tesser API](https://docs.tesser.xyz). Produces locally-signed
payloads (wallet creation, rebalance step signing) ready to submit to the
Tesser API. Targets JVM 17+ and Kotlin 2.0+.

## Install

**Gradle (Kotlin DSL):**

```kotlin
dependencies {
    implementation("xyz.tesser:sdk:0.1.0")
}
```

**Maven:**

```xml
<dependency>
    <groupId>xyz.tesser</groupId>
    <artifactId>sdk</artifactId>
    <version>0.1.0</version>
</dependency>
```

Published on [Maven Central](https://central.sonatype.com/artifact/xyz.tesser/sdk).

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
    apiBaseUrl = "https://api.tesser.xyz", // sandbox: https://api-sandbox.tesser.xyz
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

- `LocalSigner(signing, apiBaseUrl)` requires the Tesser API base URL
  (`https://api.tesser.xyz` or `https://api-sandbox.tesser.xyz`). An optional
  `java.net.http.HttpClient` can be passed to configure a proxy.
- `LocalSigner.signCreateWallet(...)` signs a wallet-creation request. For
  `STABLECOIN_ETHEREUM`, it first fetches the request to sign from the Tesser
  API and checks that it matches your parameters. The private key never leaves
  the JVM.
- `LocalSigner.signStep(...)` signs the unsigned transaction bytes that the
  Tesser API delivers via a `step.signature_requested` webhook event. The
  resulting signature is the value submitted back to
  `POST /v1/treasury/rebalances/{transferId}/steps/{stepId}/sign`.
- Three wallet types: `STABLECOIN_ETHEREUM`, `STABLECOIN_SOLANA`, and
  `STABLECOIN_STELLAR`. Tesser currently supports managed wallets for
  `STABLECOIN_ETHEREUM` only.
- Sealed `TesserError` hierarchy. The constructor throws
  `IllegalArgumentException` for blank keys or an invalid `apiBaseUrl`. The
  signer throws `ConfigError` for an unknown wallet type and `SigningError`
  for any signing failure or a fetched request that does not match your
  parameters. Fetching
  the request can throw `APIError`, `ConnectionError`, or `TimeoutError`.
  `ConnectionError`, `TimeoutError`, and an `APIError` with status 429 or 5xx
  are safe to retry; an `APIError` with any other 4xx status fails the same
  way on retry.

## Tempo transfers

1. Pass the Tempo transaction Tesser provides (`0x76...`), your wallet
   address, and `TEMPO` or `TEMPO_MODERATO` in `StepForSigning` to
   `LocalSigner.signStep(...)`.
2. The SDK authorizes the transaction with your API key, without changing
   it. Submit the returned `signature` to the Tesser signing endpoint.
3. Tesser completes the transfer, broadcasts it, and pays the network fee.

Unknown networks raise `TesserError.ConfigError` before anything is signed.

## Contributing

Building, testing, lint, binary compatibility checks, and the release runbook
live in [CONTRIBUTING.md](./CONTRIBUTING.md).

## License

[Apache 2.0](./LICENSE).
