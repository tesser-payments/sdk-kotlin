import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
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
import java.time.Instant
import java.util.UUID
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * End-to-end harness for `LocalSigner.signStep` against the Tesser rebalance flow.
 *
 * Flow:
 *   1. Authenticate via OAuth `client_credentials` against `AUTH_TOKEN_URL`.
 *   2. Start a local HTTP server on `WEBHOOK_PORT`. NOTE: real webhook delivery
 *      from Tesser is currently broken on the API side, so this example
 *      self-delivers a synthetic `step.signature_requested` event (see step 4).
 *      The listener and channel plumbing are kept intact so this code reverts
 *      cleanly once upstream webhook delivery is fixed.
 *   3. POST `/v1/treasury/rebalances` with the rebalance request body built
 *      from env.
 *   4. Poll `GET /v1/treasury/rebalances/{id}` until the API populates
 *      `unsigned_transaction` on the first step, then build a synthetic
 *      `step.signature_requested` envelope from that real step data and POST
 *      it to the local listener.
 *   5. Sign the step locally with `LocalSigner.signStep`.
 *   6. POST the signature to `/v1/treasury/rebalances/{id}/steps/{stepId}/sign`.
 *   7. Poll the rebalance until the last step's `completed_at` is set (or
 *      throw if any step reports `failed_at`).
 *   8. Print the final rebalance summary and shut down.
 *
 * Run:
 *   cp .env.example .env.local && $EDITOR .env.local
 *   set -a && source .env.local && set +a
 *   ./gradlew :examples:sign-rebalance-step:run
 *
 * No tunnel or webhook registration is required while in self-delivery mode.
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
            val event = awaitSignatureRequestEvent(config, token, rebalanceId, listener)
            val step = buildStepForSigning(config, token, event)

            println(
                "Signing step ${step.id} for transfer ${step.transferId} " +
                    "(signWith=${step.signWith}, network=${step.network}) ...",
            )
            val signed = signer.signStep(step)
            println("Local signature produced (${signed.signature.length} chars)")

            submitSignature(config, token, step, signed)

            println("Polling rebalance until the last step's completed_at is set ...")
            val finalRebalance = pollUntilLastStepCompleted(config.tesserBaseUrl, token, rebalanceId)
            printFinalRebalance(finalRebalance)
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

private class WebhookListener private constructor(
    val port: Int,
    private val server: HttpServer,
    private val events: Channel<JsonObject>,
) : AutoCloseable {
    val webhookUrl: String get() = "http://localhost:$port/webhook"

    suspend fun awaitNextEvent(timeout: Duration = 60.seconds): JsonObject = withTimeout(timeout) { events.receive() }

    override fun close() {
        server.stop(0)
        events.close()
    }

    companion object {
        fun start(port: Int): WebhookListener {
            val events = Channel<JsonObject>(capacity = 1)
            val server = HttpServer.create(InetSocketAddress(port), 0)
            server.createContext("/webhook") { exchange ->
                try {
                    val body = exchange.requestBody.bufferedReader().use { it.readText() }
                    // TODO: verify the webhook signature header before trusting the payload.
                    //   The exact header name and algorithm aren't currently documented in
                    //   the public Tesser docs; until verification lands, this handler accepts
                    //   every incoming POST. Do NOT run against production.
                    val envelope = json.parseToJsonElement(body).jsonObject
                    if (envelope["type"]?.jsonPrimitive?.content == "step.signature_requested") {
                        events.trySend(envelope)
                    }
                    exchange.sendResponseHeaders(204, -1)
                } catch (e: Exception) {
                    println("Webhook handler error: ${e.message}")
                    exchange.sendResponseHeaders(500, -1)
                } finally {
                    exchange.close()
                }
            }
            server.start()
            println("Webhook listener started on http://0.0.0.0:$port/webhook")
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

/**
 * Workaround while upstream webhook delivery is broken: poll the rebalance
 * until `unsigned_transaction` is populated, build a synthetic
 * `step.signature_requested` envelope from that real step data, and self-POST
 * it to the local listener. The listener's handler/channel plumbing is the
 * same it would use for a real Tesser-delivered event, so this reverts
 * cleanly once upstream is fixed.
 */
private suspend fun awaitSignatureRequestEvent(
    config: ExampleConfig,
    token: String,
    rebalanceId: String,
    listener: WebhookListener,
): JsonObject {
    val populatedStep = pollForUnsignedTransaction(config.tesserBaseUrl, token, rebalanceId)
    val envelope =
        buildJsonObject {
            put("id", "mock_${UUID.randomUUID()}")
            put("type", "step.signature_requested")
            put("created_at", Instant.now().toString())
            putJsonObject("data") {
                putJsonObject("object") {
                    populatedStep.forEach { (k, v) -> put(k, v) }
                }
            }
        }.toString()

    println("Self-delivering webhook event to ${listener.webhookUrl}")
    postLocalWebhook(listener.webhookUrl, envelope)
    val event = listener.awaitNextEvent()
    println("Received event: id=${event["id"]?.jsonPrimitive?.content}")
    return event
}

private fun buildStepForSigning(
    config: ExampleConfig,
    token: String,
    event: JsonObject,
): StepForSigning {
    val stepDto =
        event["data"]?.jsonObject?.get("object")?.jsonObject
            ?: error("Webhook event missing data.object: $event")
    val fromAccountId = stepDto.requireString("from_account_id")
    val network = stepDto.requireString("from_network")
    val signWith = fetchCryptoWalletAddress(config.tesserBaseUrl, token, fromAccountId)
    return StepForSigning(
        id = stepDto.requireString("id"),
        transferId = stepDto.requireString("transfer_id"),
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

private fun printFinalRebalance(rebalance: JsonObject) {
    val lastStep = (rebalance["steps"] as JsonArray).last().jsonObject
    println(
        "Rebalance complete. last_step.id=${lastStep["id"]?.jsonPrimitive?.content}" +
            " status=${lastStep["status"]?.jsonPrimitive?.content}" +
            " completed_at=${lastStep["completed_at"]?.jsonPrimitive?.content}",
    )
}

// =============================================================================
// Polling helpers
// =============================================================================

/**
 * Poll `GET /v1/treasury/rebalances/{id}` until `data.steps[0].unsigned_transaction`
 * is populated. Workaround while upstream webhook delivery is broken; throws on
 * timeout so the example fails loudly rather than self-POSTing a half-baked event.
 */
private suspend fun pollForUnsignedTransaction(
    baseUrl: String,
    token: String,
    rebalanceId: String,
): JsonObject =
    withTimeout(30.seconds) {
        while (true) {
            val rebalance = getRebalanceData(baseUrl, token, rebalanceId)
            val step = (rebalance["steps"] as? JsonArray)?.firstOrNull()?.jsonObject
                ?: error("Rebalance has no steps yet: $rebalance")
            val unsignedTx = step["unsigned_transaction"]?.jsonPrimitive?.contentOrNull
            if (!unsignedTx.isNullOrBlank()) return@withTimeout step
            delay(1.seconds)
        }
        @Suppress("UNREACHABLE_CODE")
        error("unreachable")
    }

/**
 * Poll `GET /v1/treasury/rebalances/{id}` until the last step has a non-null
 * `completed_at`. Throws if any step reports `failed_at` (the rebalance can't
 * recover) or if the timeout elapses with no terminal state.
 */
private suspend fun pollUntilLastStepCompleted(
    baseUrl: String,
    token: String,
    rebalanceId: String,
): JsonObject =
    withTimeout(5.minutes) {
        var lastReportedStatus: String? = null
        while (true) {
            val rebalance = getRebalanceData(baseUrl, token, rebalanceId)
            val lastStep = (rebalance["steps"] as? JsonArray)?.lastOrNull()?.jsonObject
                ?: error("Rebalance has no steps: $rebalance")
            val status = lastStep["status"]?.jsonPrimitive?.contentOrNull
            val completedAt = lastStep["completed_at"]?.jsonPrimitive?.contentOrNull
            val failedAt = lastStep["failed_at"]?.jsonPrimitive?.contentOrNull
            if (status != lastReportedStatus) {
                println("  last_step status=$status completed_at=$completedAt failed_at=$failedAt")
                lastReportedStatus = status
            }
            if (failedAt != null) {
                val reasons = lastStep["status_reasons"]?.toString() ?: "[]"
                error("Step ${lastStep["id"]?.jsonPrimitive?.content} failed_at=$failedAt status_reasons=$reasons")
            }
            if (completedAt != null) return@withTimeout rebalance
            delay(2.seconds)
        }
        @Suppress("UNREACHABLE_CODE")
        error("unreachable")
    }

private fun getRebalanceData(
    baseUrl: String,
    token: String,
    rebalanceId: String,
): JsonObject {
    val response = getJson("$baseUrl/v1/treasury/rebalances/$rebalanceId", token)
    return json.parseToJsonElement(response).jsonObject["data"]?.jsonObject
        ?: error("Rebalance GET missing `data` envelope: $response")
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

private fun postLocalWebhook(
    url: String,
    body: String,
) {
    val client = HttpClient.newHttpClient()
    val request =
        HttpRequest.newBuilder()
            .uri(URI.create(url))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build()
    val resp = client.send(request, HttpResponse.BodyHandlers.ofString())
    check(resp.statusCode() in 200..299) {
        "Self-delivery to $url failed: ${resp.statusCode()} ${resp.body()}"
    }
}

private fun urlEncode(s: String): String = URLEncoder.encode(s, Charsets.UTF_8)
