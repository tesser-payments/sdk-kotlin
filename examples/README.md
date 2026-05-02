# Tesser Kotlin SDK — Examples

End-to-end reference scripts that double as manual verification harnesses against Tesser staging. If you can run an example and it succeeds, the SDK is wired correctly on your machine.

---

## `create-wallet` — verify `LocalSigner.signCreateWallet` against staging

This example performs the full happy path:

1. OAuth `client_credentials` token exchange against `${API_BASE_URL}/oauth/token`.
2. Build a `CreateWalletParams` and call `LocalSigner.signCreateWallet(...)` to produce the signed payload locally.
3. POST the signed payload to `${API_BASE_URL}/v1/accounts/wallets`.
4. Print the API response (or fail loudly if any step errors).

### Prerequisites

| | |
|---|---|
| **Java 17** | `java -version` should print 17 (or newer minor). On macOS: `brew install openjdk@17`. |
| **Gradle wrapper** | Already in this repo (`./gradlew`). No separate Gradle install required. |
| **Tesser staging credentials** | OAuth client ID + secret. Get from the Tesser dashboard → Settings → API Credentials. Also the OAuth token URL (`AUTH_TOKEN_URL`) — a separate host from the API base; ask Tesser support if you don't have it. |
| **Turnkey signing key** | Public key, private key, enclave ID. Surfaced in the Tesser dashboard → Settings → Signing Keys (or your Turnkey console). |

### Step-by-step

**1. Verify Java 17 is on PATH.**

```bash
java -version
```

You should see `openjdk version "17.x.x"`. If you see `Unable to locate a Java Runtime` (macOS's stub message) or a version other than 17:

```bash
# macOS / Homebrew — install once, then add to PATH:
brew install openjdk@17
export PATH="/opt/homebrew/opt/openjdk@17/bin:$PATH"

# Make it permanent for future shells:
echo 'export PATH="/opt/homebrew/opt/openjdk@17/bin:$PATH"' >> ~/.zshrc
```

Other tools that work: `sdkman install java 17.0.13-tem`, `asdf install java openjdk-17`, `mise use java@17`.

**2. Copy the env template and fill in real values.**

```bash
cd /path/to/sdk-kotlin
cp examples/create-wallet/.env.example examples/create-wallet/.env.local
$EDITOR examples/create-wallet/.env.local
```

`.env.local` is gitignored — your secrets won't leave your machine. Each variable in the template has a comment explaining where to get the value and what format it needs.

**3. Source the env file into your shell.**

```bash
set -a && source examples/create-wallet/.env.local && set +a
```

`set -a` makes all subsequent variable assignments automatically exported, so child processes (Gradle, the JVM, the example) inherit them.

If you prefer not to source manually, alternatives that accomplish the same thing:
- **direnv**: `direnv allow` in the example directory, with `.env.local` linked from `.envrc`.
- **dotenv-cli**: `dotenv -e examples/create-wallet/.env.local ./gradlew :examples:create-wallet:run`.
- **IntelliJ run config**: paste the contents of `.env.local` into the run configuration's "Environment variables" field.

The SDK itself does **not** bundle a `.env` loader — `Main.kt` uses plain `System.getenv()` and fails fast on missing variables.

**4. Run the example.**

```bash
./gradlew :examples:create-wallet:run --no-daemon
```

### Expected output

```
Fetching access token from https://staging.tesser.xyz ...
Signing CreateWallet activity for type=STABLECOIN_ETHEREUM ...
Submitting to https://staging.tesser.xyz/v1/accounts/wallets ...
Wallet created. Response: {"wallet_id":"wal_...","address":"0x...", ...}
```

The exact response shape depends on Tesser's API version; the key signal is that `Wallet created.` printed and the response contains a `wallet_id`. If you see that line, the SDK round-tripped successfully end-to-end.

### Troubleshooting

| Symptom | Diagnosis | Fix |
|---|---|---|
| `Unable to locate a Java Runtime` / `Please visit http://www.java.com` | Java 17 not on PATH (macOS stub `/usr/bin/java` showing) | `brew install openjdk@17 && export PATH="/opt/homebrew/opt/openjdk@17/bin:$PATH"`. Add the export to `~/.zshrc` to persist. |
| `Missing required environment variable: ...` | Step 3 didn't run, or `.env.local` is missing the listed var | Re-run `set -a && source .env.local && set +a`; check the var is uncommented in `.env.local` |
| `OAuth token exchange failed: 401` | Bad `API_CLIENT_ID` / `API_CLIENT_SECRET` | Re-copy from Tesser dashboard → Settings → API Credentials |
| `OAuth token exchange failed: 404 ... "/oauth/token"` | `AUTH_TOKEN_URL` set to the API base URL (Tesser hosts OAuth on a separate auth host) | Set `AUTH_TOKEN_URL` to your environment's auth endpoint (e.g., `https://auth.tesser.xyz/oauth/token`); confirm with Tesser support |
| `OAuth token exchange failed: 403 ... "access_denied" ... "No audience parameter was provided"` | Should not happen — example defaults audience to `API_BASE_URL`. If it does, you've overridden `API_AUDIENCE` to an empty string explicitly | Unset `API_AUDIENCE` (or set it to `$API_BASE_URL`) and re-source `.env.local` |
| `OAuth response did not contain access_token` | Wrong `AUTH_TOKEN_URL` host or trailing slash | Verify URL ends in `/oauth/token` exactly; check no trailing whitespace in `.env.local` |
| `POST .../v1/accounts/wallets failed: 401` | Token authenticated but wrong audience | Coordinate with Tesser support — token may need a specific scope |
| `POST .../v1/accounts/wallets failed: 400 ... bad signature` | Stamp wire-format mismatch (rare) | Compare `signed.metadata.body` against TS SDK output for identical params; activity payload should be byte-identical. File an issue with both bodies attached. |
| `TesserError.SigningError: Hex string contains non-hex characters` | `SIGNING_PRIVATE_KEY` has whitespace or `0x` prefix | Clean to bare 64 hex chars (no `0x`, no spaces) |
| `TesserError.SigningError: P-256 private key must be 32 bytes` | Key is the wrong length | Re-export — needs to be exactly 64 hex chars (32 bytes) |
| `... CURVE_ED25519 ...` rejected for Solana/Stellar | Account spec is best-guess (spec §10 Open Item 2) | File the exact API error against the SDK; spec needs adjustment |
| `Could not resolve org.bouncycastle:...` | Network blocked Maven Central | Check VPN/proxy; the smoke test in Task 14 (`publishToMavenLocal`) requires Maven Central access |
| Tests pass but example errors with `NoClassDefFoundError` | Stale build cache | `./gradlew clean :examples:create-wallet:run` |

### Recording a successful run

Once it works, append a one-line note to `docs/superpowers/probes/2026-04-30-staging-verification.md` (gitignored, kept locally as an audit trail):

```markdown
- 2026-MM-DD — STABLECOIN_ETHEREUM — wallet_id `wal_...` — PASS
```

If you also test Solana or Stellar, record those separately. Their account specs are best-guesses inherited from the TS SDK; if either fails with a spec-related API error, that's the signal to file a follow-up against `docs/superpowers/specs/2026-04-30-kotlin-sdk-design.md` §10 Open Item 2.

### Cleanup

```bash
rm examples/create-wallet/.env.local   # if you don't need the secrets locally anymore
```

Created wallets stay in your Tesser staging account — delete them via the dashboard if needed.
