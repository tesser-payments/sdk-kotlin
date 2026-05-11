import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import xyz.tesser.sdk.LocalSigner
import xyz.tesser.sdk.SignedStepResult
import xyz.tesser.sdk.SigningConfig
import xyz.tesser.sdk.StepForSigning
import java.net.InetSocketAddress
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * End-to-end harness for `LocalSigner.signStep` against the Tesser rebalance flow.
 *
 * Flow:
 *   1. Authenticate via OAuth `client_credentials` against `AUTH_TOKEN_URL`.
 *   2. Start a local HTTP server on `WEBHOOK_PORT` to receive Tesser webhook
 *      callbacks. The handler accepts POSTs at any path so the same listener
 *      works behind cloudflared, ngrok, localhost.run, whcli, etc. without
 *      caring about path-rewriting. Register the tunnel's public URL in the
 *      Tesser dashboard subscribed to all `step.*` events (we filter by
 *      `data.object.status` rather than envelope type, so the example
 *      doesn't care which specific event type carries the terminal state).
 *   3. POST `/v1/treasury/rebalances` with the rebalance request body built
 *      from env.
 *   4. Wait for the `step.signature_requested` webhook event.
 *   5. Sign the step locally with `LocalSigner.signStep`.
 *   6. POST the signature to `/v1/treasury/rebalances/{id}/steps/{stepId}/sign`.
 *   7. Wait for a step event carrying `data.object.status == "completed"`.
 *   8. Print the final step summary (using `completed_at`) and shut down.
 *
 * Run:
 *   cp .env.example .env.local && $EDITOR .env.local
 *   set -a && source .env.local && set +a
 *   ./gradlew :examples:sign-rebalance-step:run
 *
 * Webhook signature verification is intentionally not implemented yet (the
 * verification algorithm hasn't been documented in the public Tesser docs at
 * the time of writing); never run this against production until that path is
 * added.
 */
fun main(): Unit =
    runBlocking {
        val config = ExampleConfig.fromEnv()
        val signer = LocalSigner(config.signing)
        println("Signer ready for enclave=${signer.signing.enclaveId}")

        WebhookListener.start(config.webhookPort).use { listener ->
            val token = fetchAccessToken(config)
            val rebalanceId = createRebalance(config, token)

            println("Waiting for `step.signature_requested` event for rebalance $rebalanceId ...")
            val signatureRequested = listener.awaitEventOfType("step.signature_requested")
            val step = buildStepForSigning(config, token, signatureRequested)

            println(
                "Signing step ${step.id} for transfer ${step.transferId} " +
                    "(signWith=${step.signWith}, network=${step.network}) ...",
            )
            val signed = signer.signStep(step)
            println("Local signature produced (${signed.signature.length} chars)")

            submitSignature(config, token, step, signed)

            println("Waiting for step ${step.id} to reach `status=completed` ...")
            val completed =
                listener.awaitEventWhere(
                    timeout = 5.minutes,
                    label = "step ${step.id} status=completed",
                ) { envelope ->
                    val stepObject = envelope["data"]?.jsonObject?.get("object")?.jsonObject
                    val eventStepId = stepObject?.get("id")?.jsonPrimitive?.contentOrNull
                    val eventStatus = stepObject?.get("status")?.jsonPrimitive?.contentOrNull
                    eventStepId == step.id && eventStatus == "completed"
                }
            printCompletedStep(completed)
        }
    }

// =============================================================================
// Config
// =============================================================================

private data class ExampleConfig(
    val tesserBaseUrl: String,
    val authTokenUrl: String,
    val audience: String,
    val clientId: String,
    val clientSecret: String,
    val signing: SigningConfig,
    val webhookPort: Int,
    val rebalance: RebalanceParams,
) {
    companion object {
        fun fromEnv(): ExampleConfig {
            val baseUrl = requireEnv("API_BASE_URL")
            return ExampleConfig(
                tesserBaseUrl = baseUrl,
                authTokenUrl = requireEnv("AUTH_TOKEN_URL"),
                audience = optionalEnv("API_AUDIENCE") ?: baseUrl,
                clientId = requireEnv("API_CLIENT_ID"),
                clientSecret = requireEnv("API_CLIENT_SECRET"),
                signing =
                    SigningConfig(
                        publicKey = requireEnv("SIGNING_PUBLIC_KEY"),
                        privateKey = requireEnv("SIGNING_PRIVATE_KEY"),
                        enclaveId = requireEnv("SIGNING_ENCLAVE_ID"),
                    ),
                webhookPort = optionalEnv("WEBHOOK_PORT")?.toInt() ?: 8787,
                rebalance = RebalanceParams.fromEnv(),
            )
        }
    }
}

private data class RebalanceParams(
    val fromAccountId: String,
    val fromAmount: String,
    val fromCurrency: String,
    val fromNetwork: String,
    val toAccountId: String,
    val toCurrency: String,
    val toNetwork: String,
) {
    companion object {
        fun fromEnv() =
            RebalanceParams(
                fromAccountId = requireEnv("FROM_ACCOUNT_ID"),
                fromAmount = optionalEnv("FROM_AMOUNT") ?: "0.000001",
                fromCurrency = optionalEnv("FROM_CURRENCY") ?: "USDC",
                fromNetwork = optionalEnv("FROM_NETWORK") ?: "BASE_SEPOLIA",
                toAccountId = requireEnv("TO_ACCOUNT_ID"),
                toCurrency = optionalEnv("TO_CURRENCY") ?: "USDC",
                toNetwork = optionalEnv("TO_NETWORK") ?: "BASE_SEPOLIA",
            )
    }
}

// =============================================================================
// Webhook listener
// =============================================================================

/**
 * Buffers incoming webhook envelopes onto an unbounded channel so callers can
 * pull events by `type` in arrival order. Unbounded so events that arrive
 * between awaits (e.g., `step.submitted` and `step.confirmed` while we're
 * waiting for `step.completed`) aren't dropped.
 */
private class WebhookListener private constructor(
    val port: Int,
    private val server: HttpServer,
    private val events: Channel<JsonObject>,
) : AutoCloseable {
    /**
     * Receive events from the channel, discarding any whose `type` doesn't
     * match. Throws if the timeout elapses before a matching event arrives.
     */
    suspend fun awaitEventOfType(
        type: String,
        timeout: Duration = 60.seconds,
    ): JsonObject =
        awaitEventWhere(timeout, "type=$type") { envelope ->
            envelope["type"]?.jsonPrimitive?.content == type
        }

    /**
     * Receive events from the channel, discarding any that don't satisfy
     * [predicate]. Throws if the timeout elapses before a matching event
     * arrives. [label] is used only in skip logging so the run output makes
     * sense to someone reading along.
     */
    suspend fun awaitEventWhere(
        timeout: Duration = 60.seconds,
        label: String = "predicate",
        predicate: (JsonObject) -> Boolean,
    ): JsonObject =
        withTimeout(timeout) {
            while (true) {
                val envelope = events.receive()
                if (predicate(envelope)) return@withTimeout envelope
                val type = envelope["type"]?.jsonPrimitive?.content
                val id = envelope["id"]?.jsonPrimitive?.content
                println("  (skipping webhook event — $label not satisfied; type=$type id=$id)")
            }
            @Suppress("UNREACHABLE_CODE")
            error("unreachable")
        }

    override fun close() {
        server.stop(0)
        events.close()
    }

    companion object {
        fun start(port: Int): WebhookListener {
            val events = Channel<JsonObject>(capacity = Channel.UNLIMITED)
            val server = HttpServer.create(InetSocketAddress(port), 0)
            // Register at "/" so any incoming path works (whcli, ngrok, and
            // similar tunnels typically forward to the bare target URL with no
            // extra path component). HttpServer routes by longest-prefix
            // match — without other contexts, every request lands here.
            server.createContext("/") { exchange ->
                val method = exchange.requestMethod
                val path = exchange.requestURI.path
                try {
                    if (method != "POST") {
                        // Reject non-POST quietly so health checks / browser
                        // visits don't crash the parser.
                        println("  webhook received: method=$method path=$path (ignored, expected POST)")
                        exchange.sendResponseHeaders(405, -1)
                        return@createContext
                    }
                    val body = exchange.requestBody.bufferedReader().use { it.readText() }
                    // TODO: verify the webhook signature header before trusting the payload.
                    //   The exact header name and algorithm aren't currently documented in
                    //   the public Tesser docs; until verification lands, this handler accepts
                    //   every incoming POST. Do NOT run against production.
                    val envelope = json.parseToJsonElement(body).jsonObject
                    println(
                        "  webhook received: path=$path type=${envelope["type"]?.jsonPrimitive?.content}" +
                            " id=${envelope["id"]?.jsonPrimitive?.content}",
                    )
                    events.trySend(envelope)
                    exchange.sendResponseHeaders(204, -1)
                } catch (e: Exception) {
                    println("Webhook handler error on $method $path: ${e.message}")
                    exchange.sendResponseHeaders(500, -1)
                } finally {
                    exchange.close()
                }
            }
            server.start()
            println("Webhook listener started on http://0.0.0.0:$port/ (any path)")
            return WebhookListener(port, server, events)
        }
    }
}

// =============================================================================
// Pipeline steps
// =============================================================================

private fun fetchAccessToken(config: ExampleConfig): String {
    println("Fetching access token from ${config.authTokenUrl} (audience=${config.audience}) ...")
    return fetchToken(config.authTokenUrl, config.clientId, config.clientSecret, config.audience)
}

private fun createRebalance(
    config: ExampleConfig,
    token: String,
): String {
    val body =
        buildJsonObject {
            put("from_account_id", config.rebalance.fromAccountId)
            put("from_amount", config.rebalance.fromAmount)
            put("from_network", config.rebalance.fromNetwork)
            put("from_currency", config.rebalance.fromCurrency)
            put("to_account_id", config.rebalance.toAccountId)
            put("to_network", config.rebalance.toNetwork)
            put("to_currency", config.rebalance.toCurrency)
        }.toString()

    println("Creating rebalance: POST ${config.tesserBaseUrl}/v1/treasury/rebalances")
    println("Request payload: $body")
    val response = postJson("${config.tesserBaseUrl}/v1/treasury/rebalances", token, body)
    val rebalanceId = response.dataField("id", "Rebalance response missing `data.id`: $response")
    println("Rebalance created: id=$rebalanceId")
    return rebalanceId
}

private fun buildStepForSigning(
    config: ExampleConfig,
    token: String,
    event: JsonObject,
): StepForSigning {
    val stepDto =
        event["data"]?.jsonObject?.get("object")?.jsonObject
            ?: error("Webhook event missing data.object: $event")
    // Use the user-facing account/network we sent on the rebalance request,
    // not the step DTO's `from_account_id` / `from_network`. The webhook
    // step's `from_account_id` is Tesser's internal wallet-account id which
    // doesn't resolve via `GET /v1/accounts/{id}`.
    val fromAccountId = config.rebalance.fromAccountId
    val network = config.rebalance.fromNetwork
    val signWith = fetchCryptoWalletAddress(config.tesserBaseUrl, token, fromAccountId)
    return StepForSigning(
        id = stepDto.requireString("id"),
        // Webhook step DTO uses `rebalance_id` for the parent UUID; the GET
        // response uses `transfer_id` for the same value. Read `rebalance_id`
        // here since this code is fed by the webhook event.
        transferId = stepDto.requireString("rebalance_id"),
        unsignedTransaction = stepDto.requireString("unsigned_transaction"),
        signWith = signWith,
        network = network,
    )
}

private fun fetchCryptoWalletAddress(
    baseUrl: String,
    token: String,
    accountId: String,
): String {
    val response = getJson("$baseUrl/v1/accounts/$accountId", token)
    return response.dataField(
        "crypto_wallet_address",
        "Account $accountId has no crypto_wallet_address: $response",
    )
}

private fun submitSignature(
    config: ExampleConfig,
    token: String,
    step: StepForSigning,
    signed: SignedStepResult,
): String {
    val url = "${config.tesserBaseUrl}/v1/treasury/rebalances/${step.transferId}/steps/${step.id}/sign"
    println("Submitting signature: POST $url")
    val body = buildJsonObject { put("signature", signed.signature) }.toString()
    val response = postJson(url, token, body)
    println("Step submitted. API response: $response")
    return response
}

private fun printCompletedStep(event: JsonObject) {
    val stepObject =
        event["data"]?.jsonObject?.get("object")?.jsonObject
            ?: error("Completion event missing data.object: $event")
    println(
        "Rebalance complete. step.id=${stepObject["id"]?.jsonPrimitive?.content}" +
            " status=${stepObject["status"]?.jsonPrimitive?.content}" +
            " completed_at=${stepObject["completed_at"]?.jsonPrimitive?.content}",
    )
}

// =============================================================================
// JSON / env helpers
// =============================================================================

private val json = Json { ignoreUnknownKeys = true }

/** Pull a required string field out of `data.<field>` of a Tesser API response body. */
private fun String.dataField(
    field: String,
    onMissing: String,
): String =
    json.parseToJsonElement(this).jsonObject["data"]?.jsonObject
        ?.get(field)?.jsonPrimitive?.contentOrNull
        ?: error(onMissing)

/** Pull a required string field directly out of a JSON object (no `data` envelope). */
private fun JsonObject.requireString(field: String): String =
    this[field]?.jsonPrimitive?.contentOrNull
        ?: error("Missing required field `$field` in: $this")

private fun requireEnv(name: String): String =
    System.getenv(name)?.takeIf { it.isNotBlank() }
        ?: error("Missing required environment variable: $name")

private fun optionalEnv(name: String): String? = System.getenv(name)?.takeIf { it.isNotBlank() }

// =============================================================================
// HTTP helpers
// =============================================================================

private fun fetchToken(
    authTokenUrl: String,
    clientId: String,
    clientSecret: String,
    audience: String,
): String {
    val client = HttpClient.newHttpClient()
    val form =
        buildString {
            append("grant_type=client_credentials")
            append("&client_id=").append(urlEncode(clientId))
            append("&client_secret=").append(urlEncode(clientSecret))
            append("&audience=").append(urlEncode(audience))
        }
    val request =
        HttpRequest.newBuilder()
            .uri(URI.create(authTokenUrl))
            .header("Content-Type", "application/x-www-form-urlencoded")
            .POST(HttpRequest.BodyPublishers.ofString(form))
            .build()
    val resp = client.send(request, HttpResponse.BodyHandlers.ofString())
    check(resp.statusCode() in 200..299) {
        "OAuth token exchange failed: ${resp.statusCode()} ${resp.body()}"
    }
    val tokenRegex = Regex("\"access_token\"\\s*:\\s*\"([^\"]+)\"")
    return tokenRegex.find(resp.body())?.groupValues?.get(1)
        ?: error("OAuth response did not contain access_token: ${resp.body()}")
}

private fun getJson(
    url: String,
    bearer: String,
): String {
    val client = HttpClient.newHttpClient()
    val request =
        HttpRequest.newBuilder()
            .uri(URI.create(url))
            .header("Authorization", "Bearer $bearer")
            .header("Accept", "application/json")
            .GET()
            .build()
    val resp = client.send(request, HttpResponse.BodyHandlers.ofString())
    check(resp.statusCode() in 200..299) {
        "GET $url failed: ${resp.statusCode()} ${resp.body()}"
    }
    return resp.body()
}

private fun postJson(
    url: String,
    bearer: String,
    body: String,
): String {
    val client = HttpClient.newHttpClient()
    val request =
        HttpRequest.newBuilder()
            .uri(URI.create(url))
            .header("Authorization", "Bearer $bearer")
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build()
    val resp = client.send(request, HttpResponse.BodyHandlers.ofString())
    check(resp.statusCode() in 200..299) {
        "POST $url failed: ${resp.statusCode()} ${resp.body()}"
    }
    return resp.body()
}

private fun urlEncode(s: String): String = URLEncoder.encode(s, Charsets.UTF_8)
