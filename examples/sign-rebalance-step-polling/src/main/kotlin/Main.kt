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
import xyz.tesser.sdk.LocalSigner
import xyz.tesser.sdk.SignedStepResult
import xyz.tesser.sdk.SigningConfig
import xyz.tesser.sdk.StepForSigning
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * End-to-end harness for `LocalSigner.signStep` against the Tesser rebalance
 * flow — **polling variant**. Use this when webhook delivery is unreliable;
 * the example simply polls `GET /v1/treasury/rebalances/{id}` for state
 * transitions.
 *
 * Flow:
 *   1. Authenticate via OAuth `client_credentials` against `AUTH_TOKEN_URL`.
 *   2. POST `/v1/treasury/rebalances` with the rebalance request body built
 *      from env. Read the rebalance id from the response.
 *   3. Poll the rebalance until the first step's `status` is
 *      `signature_requested` and `unsigned_transaction` is populated.
 *   4. Sign the step locally with `LocalSigner.signStep`.
 *   5. POST the signature to `/v1/treasury/rebalances/{id}/steps/{stepId}/sign`.
 *   6. Poll the rebalance until the step's `status` is `finalized`
 *      (or fail loudly if any step reports `failed_at`).
 *   7. Print the final step summary and shut down.
 *
 * Run:
 *   cp .env.example .env.local && $EDITOR .env.local
 *   set -a && source .env.local && set +a
 *   ./gradlew :examples:sign-rebalance-step-polling:run
 *
 * No tunnel, no webhook subscription, no public URL — everything is
 * driven by the polling thread.
 */
fun main(): Unit =
    runBlocking {
        val config = ExampleConfig.fromEnv()
        val signer = LocalSigner(config.signing)
        println("Signer ready for enclave=${signer.signing.enclaveId}")

        val token = fetchAccessToken(config)
        val rebalanceId = createRebalance(config, token)

        println("Polling rebalance $rebalanceId until step is `signature_requested` ...")
        val readyStep = pollUntilSignatureRequested(config.tesserBaseUrl, token, rebalanceId)
        val step = buildStepForSigning(config, token, readyStep)

        println(
            "Signing step ${step.id} for transfer ${step.transferId} " +
                "(signWith=${step.signWith}, network=${step.network}) ...",
        )
        val signed = signer.signStep(step)
        println("Local signature produced (${signed.signature.length} chars)")

        submitSignature(config, token, step, signed)

        println("Polling rebalance until the step's status is `finalized` ...")
        val finalStep = pollUntilStepFinalized(config.tesserBaseUrl, token, rebalanceId, step.id)
        printFinalStep(finalStep)
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
    stepDto: JsonObject,
): StepForSigning {
    // Use the user-facing account/network we sent on the rebalance request,
    // not the step DTO's `from_account_id` / `from_network`. The step's
    // `from_account_id` is Tesser's internal wallet-account id which doesn't
    // resolve via `GET /v1/accounts/{id}`.
    val fromAccountId = config.rebalance.fromAccountId
    val network = config.rebalance.fromNetwork
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

private fun printFinalStep(stepDto: JsonObject) {
    println(
        "Rebalance complete. step.id=${stepDto["id"]?.jsonPrimitive?.content}" +
            " status=${stepDto["status"]?.jsonPrimitive?.content}" +
            " finalized_at=${stepDto["finalized_at"]?.jsonPrimitive?.content}",
    )
}

// =============================================================================
// Polling helpers
// =============================================================================

/**
 * Poll the rebalance until the first step is in `signature_requested` status
 * with a populated `unsigned_transaction`. Returns the step DTO. Throws on
 * timeout or if any step reports `failed_at` before reaching the ready state.
 */
private suspend fun pollUntilSignatureRequested(
    baseUrl: String,
    token: String,
    rebalanceId: String,
): JsonObject =
    withTimeout(2.minutes) {
        var lastReportedStatus: String? = null
        while (true) {
            val rebalance = getRebalanceData(baseUrl, token, rebalanceId)
            val step = (rebalance["steps"] as? JsonArray)?.firstOrNull()?.jsonObject
                ?: error("Rebalance has no steps yet: $rebalance")
            val status = step["status"]?.jsonPrimitive?.contentOrNull
            val unsignedTx = step["unsigned_transaction"]?.jsonPrimitive?.contentOrNull
            val failedAt = step["failed_at"]?.jsonPrimitive?.contentOrNull
            if (status != lastReportedStatus) {
                println("  step status=$status (unsigned_transaction=${if (unsignedTx.isNullOrBlank()) "null" else "present"})")
                lastReportedStatus = status
            }
            if (failedAt != null) {
                val reasons = step["status_reasons"]?.toString() ?: "[]"
                error("Step ${step["id"]?.jsonPrimitive?.content} failed_at=$failedAt status_reasons=$reasons")
            }
            if (status == "signature_requested" && !unsignedTx.isNullOrBlank()) return@withTimeout step
            delay(2.seconds)
        }
        @Suppress("UNREACHABLE_CODE")
        error("unreachable")
    }

/**
 * Poll the rebalance until the step matching [stepId] has `status == "finalized"`.
 * Throws on timeout or if any step reports `failed_at`.
 */
private suspend fun pollUntilStepFinalized(
    baseUrl: String,
    token: String,
    rebalanceId: String,
    stepId: String,
): JsonObject =
    withTimeout(5.minutes) {
        var lastReportedStatus: String? = null
        while (true) {
            val rebalance = getRebalanceData(baseUrl, token, rebalanceId)
            val steps = rebalance["steps"] as? JsonArray
                ?: error("Rebalance response missing `data.steps`: $rebalance")
            val step = steps.map { it.jsonObject }.firstOrNull { it["id"]?.jsonPrimitive?.content == stepId }
                ?: error("Rebalance has no step with id $stepId: $rebalance")
            val status = step["status"]?.jsonPrimitive?.contentOrNull
            val finalizedAt = step["finalized_at"]?.jsonPrimitive?.contentOrNull
            val failedAt = step["failed_at"]?.jsonPrimitive?.contentOrNull
            if (status != lastReportedStatus) {
                println("  step status=$status finalized_at=$finalizedAt failed_at=$failedAt")
                lastReportedStatus = status
            }
            if (failedAt != null) {
                val reasons = step["status_reasons"]?.toString() ?: "[]"
                error("Step $stepId failed_at=$failedAt status_reasons=$reasons")
            }
            if (status == "finalized") return@withTimeout step
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

private fun urlEncode(s: String): String = URLEncoder.encode(s, Charsets.UTF_8)
