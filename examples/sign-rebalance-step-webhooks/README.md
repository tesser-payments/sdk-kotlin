# `sign-rebalance-step-webhooks` example

End-to-end harness that exercises the full Tesser rebalance flow:

1. Authenticate via OAuth `client_credentials`.
2. Listen on a local webhook endpoint for `step.signature_requested` and
   `step.completed` events.
3. Create a rebalance via `POST /v1/treasury/rebalances`.
4. Receive the signing event, sign the step locally with `LocalSigner.signStep`,
   and POST the signature to `/v1/treasury/rebalances/{id}/steps/{stepId}/sign`.
5. Wait for a step event with `data.object.status == "completed"` and report `completed_at`.

> **Status:** webhook delivery from Tesser staging is currently **unreliable**.
> If you don't see events arrive within the configured timeout, switch to the
> polling variant in [`../sign-rebalance-step-polling`](../sign-rebalance-step-polling).
> Webhook signature verification is also still intentionally skipped pending
> the staging probe that captures the verification algorithm; do not run this
> against production until that lands.

---

## Prerequisites

Same baseline as [`examples/create-wallet`](../create-wallet/README.md): Java 17, the Gradle wrapper (`./gradlew`), and Tesser staging credentials. Also:

- A tunnel tool to expose your local webhook listener to the public internet so Tesser can POST to it. Any of the following works:
  - **cloudflared** (recommended; no signup): `brew install cloudflared`. Run `cloudflared tunnel --url http://localhost:8787`. The command prints a public `https://....trycloudflare.com` URL.
  - **ngrok**: `brew install --cask ngrok`, then `ngrok http 8787`. Free tier rotates the URL on every run.
  - **localhost.run**: `ssh -R 80:localhost:8787 localhost.run`. No install required.
- A webhook subscription registered in the Tesser dashboard (Settings, then Webhooks) pointing at `<tunnel-url>/webhook`, subscribed to `step.signature_requested`. Note the webhook secret from the dashboard; you'll set `WEBHOOK_SECRET` to that value.

## Setup

```bash
cp examples/sign-rebalance-step-webhooks/.env.example examples/sign-rebalance-step-webhooks/.env.local
$EDITOR examples/sign-rebalance-step-webhooks/.env.local
```

Fill in:

- API and signing-key vars (same shape as `examples/create-wallet/.env.example`).
- `WEBHOOK_PUBLIC_URL` to the tunnel URL from the prereq above (e.g., `https://abc-123.trycloudflare.com`).
- `WEBHOOK_PORT` if you want something other than `8787`.
- `WEBHOOK_SECRET` from the Tesser dashboard.
- `FROM_ACCOUNT_ID` / `FROM_AMOUNT` / `FROM_CURRENCY` / `FROM_NETWORK` (optional) for the source.
- `TO_ACCOUNT_ID` / `TO_CURRENCY` / `TO_NETWORK` (optional) for the destination.

## Run

```bash
# In one terminal, run the tunnel:
cloudflared tunnel --url http://localhost:8787

# In another terminal:
set -a && source examples/sign-rebalance-step-webhooks/.env.local && set +a
./gradlew :examples:sign-rebalance-step-webhooks:run
```

## Expected output

```text
Signer ready for enclave=org_...
Starting webhook listener on http://0.0.0.0:8787/webhook
Public URL the Tesser dashboard should POST to: https://....trycloudflare.com/webhook
Fetching access token from https://auth.tesser.xyz/oauth/token (audience=https://staging.tesser.xyz) ...
Creating rebalance: POST https://staging.tesser.xyz/v1/treasury/rebalances
Rebalance created: id=reb_...
Waiting up to 60s for `step.signature_requested` event ...
Received event: id=evt_...
Signing step step_... for transfer reb_... ...
Local signature produced (... chars)
Submitting signature: POST https://staging.tesser.xyz/v1/treasury/rebalances/reb_.../steps/step_.../sign
Step submitted. API response: {...}
```

If you see `Step submitted.` and a 2xx response body, the round-trip succeeded
end-to-end.

## Troubleshooting

| Symptom | Diagnosis | Fix |
|---|---|---|
| `Missing required environment variable: ...` | `set -a && source ...` did not run, or the variable is uncommented | Re-source `.env.local`. Confirm the variable has a value. |
| `OAuth token exchange failed: 401` | Bad `API_CLIENT_ID` / `API_CLIENT_SECRET` | Re-copy from the Tesser dashboard. |
| `POST .../v1/treasury/rebalances failed: 422` | Bad `desired` block (missing fields, currency mismatch, etc.) | Inspect the API error message. Confirm `from` and `to` fields match Tesser's account/currency expectations. |
| `Waiting up to 60s for ... event` then timeout | Webhook is not registered with Tesser, or the tunnel URL changed | Re-check the dashboard subscription URL. Re-run the tunnel and update `WEBHOOK_PUBLIC_URL`. |
| Webhook arrives but is ignored | The `type` field is something other than `step.signature_requested` | Inspect the raw event in the server log. The harness only acts on `step.signature_requested`. |
| `step DTO missing transfer_id` (or similar) | The step DTO field names changed on the server side | File an issue with the captured event payload. The expected fields are `id`, `transfer_id`, `unsigned_transaction`. |
| `POST .../sign failed: 422 ... bad signature` | Stamp wire format does not match what the server expects | Capture the unsigned transaction bytes and the produced signature; file an issue with both. The current implementation stamps the unsigned transaction hex string verbatim using the X-Stamp envelope. |
| `POST .../sign failed: 409 ... step already signed / expired` | Either a previous run already submitted, or the rebalance timed out | Create a fresh rebalance and try again. |
| `Could not resolve org.bouncycastle:...` | Network blocked Maven Central | Check VPN/proxy. Maven Central must be reachable for the SDK dependency. |
