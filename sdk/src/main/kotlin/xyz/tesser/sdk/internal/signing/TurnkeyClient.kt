package xyz.tesser.sdk.internal.signing

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import xyz.tesser.sdk.error.TesserError
import java.io.IOException
import java.net.ConnectException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.net.http.HttpTimeoutException
import java.time.Duration

/**
 * Submits a stamped Turnkey activity to Turnkey's REST endpoint and returns
 * the resulting signed transaction.
 *
 * **Temporary integration.** Tesser's `/sign` endpoint should accept the same
 * `base64({body, stamp})` envelope as `signCreateWallet` and forward to
 * Turnkey internally; until that lands the SDK does the Turnkey hop itself
 * so callers see a single consistent contract: hand `SignedStepResult.signature`
 * straight to Tesser's `/sign` body. When the upstream behavior is fixed,
 * this client gets deleted and the SDK reverts to a pure-compute base64 wrap.
 */
internal interface TurnkeyClient {
    /**
     * @param body the Turnkey activity request JSON (already JSON-encoded).
     * @param stamp the X-Stamp header values produced by stamping [body].
     * @return the `signedTransaction` from Turnkey's response, normalized to
     *   include a `0x` prefix.
     */
    suspend fun signTransaction(
        body: String,
        stamp: StampResult,
    ): String

    companion object {
        fun create(): TurnkeyClient = HttpTurnkeyClient()
    }
}

internal class HttpTurnkeyClient(
    private val baseUrl: String = "https://api.turnkey.com",
    private val timeout: Duration = Duration.ofSeconds(30),
) : TurnkeyClient {
    override suspend fun signTransaction(
        body: String,
        stamp: StampResult,
    ): String {
        val client = HttpClient.newHttpClient()
        val request =
            HttpRequest.newBuilder()
                .uri(URI.create("$baseUrl/public/v1/submit/sign_transaction"))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .header(stamp.stampHeaderName, stamp.stampHeaderValue)
                .timeout(timeout)
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build()

        val resp =
            try {
                client.send(request, HttpResponse.BodyHandlers.ofString())
            } catch (e: HttpTimeoutException) {
                throw TesserError.TimeoutError("Turnkey sign_transaction timed out", e)
            } catch (e: ConnectException) {
                throw TesserError.ConnectionError("Turnkey sign_transaction connect failed: ${e.message}", e)
            } catch (e: IOException) {
                throw TesserError.ConnectionError("Turnkey sign_transaction transport failed: ${e.message}", e)
            }

        if (resp.statusCode() !in 200..299) {
            throw TesserError.SigningError(
                "Turnkey sign_transaction returned ${resp.statusCode()}: ${resp.body()}",
            )
        }

        val parsed =
            try {
                Json.parseToJsonElement(resp.body()).jsonObject
            } catch (e: Exception) {
                throw TesserError.SigningError("Turnkey response was not JSON: ${resp.body()}", e)
            }

        val activity =
            parsed["activity"]?.jsonObject
                ?: throw TesserError.SigningError("Turnkey response missing `activity`: ${resp.body()}")

        val status = activity["status"]?.jsonPrimitive?.contentOrNull
        if (status != "ACTIVITY_STATUS_COMPLETED") {
            val failureMessage =
                activity["failure"]?.jsonObject?.get("message")?.jsonPrimitive?.contentOrNull
                    ?: "no failure message"
            throw TesserError.SigningError(
                "Turnkey activity not completed (status=$status): $failureMessage",
            )
        }

        val signedTransaction =
            activity["result"]?.jsonObject
                ?.get("signTransactionResult")?.jsonObject
                ?.get("signedTransaction")?.jsonPrimitive?.contentOrNull
                ?: throw TesserError.SigningError(
                    "Turnkey response missing `activity.result.signTransactionResult.signedTransaction`: ${resp.body()}",
                )

        return if (signedTransaction.startsWith("0x")) signedTransaction else "0x$signedTransaction"
    }
}
