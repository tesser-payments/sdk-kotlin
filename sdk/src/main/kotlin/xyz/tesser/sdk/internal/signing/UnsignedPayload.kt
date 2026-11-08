package xyz.tesser.sdk.internal.signing

import kotlinx.coroutines.future.await
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import xyz.tesser.sdk.CreateWalletParams
import xyz.tesser.sdk.SigningConfig
import xyz.tesser.sdk.error.TesserError
import xyz.tesser.sdk.error.TesserErrorDetail
import xyz.tesser.sdk.internal.util.SDK_VERSION
import xyz.tesser.sdk.internal.util.json
import xyz.tesser.sdk.internal.util.logger
import java.io.IOException
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.net.http.HttpTimeoutException
import java.time.Duration

private const val UNSIGNED_PAYLOAD_PATH = "/v1/accounts/wallets/unsigned-payload"
private const val CREATE_WALLET_ACCOUNTS_ACTIVITY_TYPE = "ACTIVITY_TYPE_CREATE_WALLET_ACCOUNTS"
private const val ETHEREUM_ADDRESS_FORMAT = "ADDRESS_FORMAT_ETHEREUM"
private val UNSIGNED_PAYLOAD_TIMEOUT: Duration = Duration.ofSeconds(10)
private const val SIGNING_KEY_REJECTED_ERROR_CODE = "signing-4010"
private const val SIGNING_KEY_REJECTED_STATUS = 401
internal const val PRODUCTION_API_BASE_URL = "https://api.tesser.xyz"
internal const val SANDBOX_API_BASE_URL = "https://api-sandbox.tesser.xyz"
internal val DEFAULT_API_BASE_URLS: List<String> = listOf(PRODUCTION_API_BASE_URL, SANDBOX_API_BASE_URL)

private val log = logger("xyz.tesser.sdk.LocalSigner")

internal class TesserApiConnection private constructor(
    val httpClient: HttpClient,
    val defaultBaseUrls: List<String>,
    explicitBaseUrl: String?,
) {
    @Volatile
    var resolvedBaseUrl: String? = explicitBaseUrl
        private set

    fun remember(baseUrl: String) {
        resolvedBaseUrl = baseUrl
    }

    companion object {
        fun create(
            apiBaseUrl: String?,
            httpClient: HttpClient,
            defaultBaseUrls: List<String> = DEFAULT_API_BASE_URLS,
        ): TesserApiConnection {
            val explicitBaseUrl = apiBaseUrl?.let(::normalizeApiBaseUrl)
            return TesserApiConnection(httpClient, defaultBaseUrls, explicitBaseUrl)
        }
    }
}

private data class UnsignedPayloadQuery(
    val organizationId: String,
    val name: String,
    val type: String,
    val timestampMs: String,
)

@Serializable
private data class UnsignedPayloadResponse(
    val data: UnsignedPayloadData,
)

@Serializable
private data class UnsignedPayloadData(
    val body: String,
)

@Serializable
private data class ErrorEnvelope(
    val errors: List<ErrorEnvelopeEntry> = emptyList(),
)

@Serializable
private data class ErrorEnvelopeEntry(
    @SerialName("error_code")
    val errorCode: String,
    @SerialName("error_message")
    val errorMessage: String,
    @SerialName("ui_message")
    val uiMessage: String? = null,
)

internal fun createDefaultHttpClient(): HttpClient =
    HttpClient.newBuilder()
        .connectTimeout(UNSIGNED_PAYLOAD_TIMEOUT)
        .build()

internal fun normalizeApiBaseUrl(apiBaseUrl: String): String {
    val withoutTrailingSlash = apiBaseUrl.trim().removeSuffix("/")
    val normalized = withoutTrailingSlash.removeSuffix("/v1").removeSuffix("/")
    require(isHttpsOrLoopbackHttpUrl(normalized)) {
        "apiBaseUrl must be an https URL, for example https://api.tesser.xyz"
    }
    return normalized
}

private val LOOPBACK_HOSTS = setOf("localhost", "127.0.0.1", "[::1]")

private fun isHttpsOrLoopbackHttpUrl(value: String): Boolean {
    val uri = runCatching { URI(value) }.getOrNull() ?: return false
    val host = uri.host
    if (host.isNullOrBlank()) {
        return false
    }
    if (uri.scheme == "https") {
        return true
    }
    return uri.scheme == "http" && host.lowercase() in LOOPBACK_HOSTS
}

internal suspend fun fetchCheckedCreateWalletAccountsBody(
    api: TesserApiConnection,
    signing: SigningConfig,
    params: CreateWalletParams,
    stamp: Stamp,
): String {
    val resolvedBaseUrl = api.resolvedBaseUrl
    if (resolvedBaseUrl != null) {
        return fetchCheckedBodyFrom(resolvedBaseUrl, api.httpClient, signing, params, stamp)
    }
    return fetchCheckedBodyFromSigningKeyEnvironment(api, signing, params, stamp)
}

private suspend fun fetchCheckedBodyFromSigningKeyEnvironment(
    api: TesserApiConnection,
    signing: SigningConfig,
    params: CreateWalletParams,
    stamp: Stamp,
): String {
    val signingKeyRejections = mutableListOf<TesserError.APIError>()
    for (baseUrl in api.defaultBaseUrls) {
        try {
            val body = fetchCheckedBodyFrom(baseUrl, api.httpClient, signing, params, stamp)
            rememberSigningKeyEnvironment(api, baseUrl)
            return body
        } catch (e: TesserError) {
            if (e !is TesserError.APIError || !isSigningKeyRejection(e)) {
                throw withSuppressedRejections(e, signingKeyRejections)
            }
            log.debug("Signing key not accepted by {}", baseUrl)
            signingKeyRejections += e
        }
    }
    throw withSuppressedRejections(signingKeyRejections.first(), signingKeyRejections.drop(1))
}

private fun rememberSigningKeyEnvironment(
    api: TesserApiConnection,
    baseUrl: String,
) {
    api.remember(baseUrl)
    log.debug("Signing key accepted by {}, using it for later requests", baseUrl)
}

private fun isSigningKeyRejection(error: TesserError.APIError): Boolean =
    error.status == SIGNING_KEY_REJECTED_STATUS && error.errorCode == SIGNING_KEY_REJECTED_ERROR_CODE

private fun withSuppressedRejections(
    error: TesserError,
    signingKeyRejections: List<TesserError.APIError>,
): TesserError {
    signingKeyRejections.forEach { error.addSuppressed(it) }
    return error
}

private suspend fun fetchCheckedBodyFrom(
    baseUrl: String,
    httpClient: HttpClient,
    signing: SigningConfig,
    params: CreateWalletParams,
    stamp: Stamp,
): String {
    val query = buildUnsignedPayloadQuery(signing, params)
    val stamped = stamp.stamp(signing, buildCanonicalUnsignedPayloadMessage(query))
    val request = buildUnsignedPayloadRequest(baseUrl, query, stamped)
    val response = sendUnsignedPayloadRequest(httpClient, request)
    val body = parseUnsignedPayloadBody(response)
    assertUnsignedPayloadMatchesRequest(body, signing, params)
    return body
}

private fun buildUnsignedPayloadQuery(
    signing: SigningConfig,
    params: CreateWalletParams,
): UnsignedPayloadQuery =
    UnsignedPayloadQuery(
        organizationId = signing.enclaveId,
        name = params.name,
        type = params.type.wireValue,
        timestampMs = System.currentTimeMillis().toString(),
    )

private fun buildCanonicalUnsignedPayloadMessage(query: UnsignedPayloadQuery): String =
    buildJsonObject {
        put("organizationId", query.organizationId)
        put("name", query.name)
        put("type", query.type)
        put("timestampMs", query.timestampMs)
    }.toString()

private fun buildUnsignedPayloadRequest(
    baseUrl: String,
    query: UnsignedPayloadQuery,
    stamped: StampResult,
): HttpRequest {
    val queryString =
        listOf(
            "organizationId" to query.organizationId,
            "name" to query.name,
            "type" to query.type,
            "timestampMs" to query.timestampMs,
        ).joinToString("&") { (key, value) -> "$key=${encodeQueryValue(value)}" }
    return HttpRequest.newBuilder()
        .uri(URI.create("$baseUrl$UNSIGNED_PAYLOAD_PATH?$queryString"))
        .timeout(UNSIGNED_PAYLOAD_TIMEOUT)
        .header(stamped.stampHeaderName, stamped.stampHeaderValue)
        .header("User-Agent", "tesser-sdk-kotlin/$SDK_VERSION")
        .header("Accept", "application/json")
        .GET()
        .build()
}

private fun encodeQueryValue(value: String): String = URLEncoder.encode(value, Charsets.UTF_8).replace("+", "%20")

private suspend fun sendUnsignedPayloadRequest(
    httpClient: HttpClient,
    request: HttpRequest,
): HttpResponse<String> =
    try {
        httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString()).await()
    } catch (e: HttpTimeoutException) {
        throw TesserError.TimeoutError("Unsigned payload request timed out: ${e.message}", e)
    } catch (e: IOException) {
        throw TesserError.ConnectionError("Unsigned payload request failed: ${describeTransportFailure(e)}", e)
    }

private fun describeTransportFailure(error: Throwable): String {
    val failureChain = generateSequence(error) { it.cause }.toList()
    val firstMessage = failureChain.firstNotNullOfOrNull { failure -> failure.message?.takeIf { it.isNotBlank() } }
    if (firstMessage != null) {
        return firstMessage
    }
    return failureChain.last().javaClass.simpleName
}

private fun readGatewayRequestId(response: HttpResponse<String>): String? =
    response
        .headers()
        .firstValue("zp-rid")
        .or { response.headers().firstValue("x-request-id") }
        .orElse(null)

private fun parseUnsignedPayloadBody(response: HttpResponse<String>): String {
    if (response.statusCode() !in 200..299) {
        throw buildUnsignedPayloadApiError(response)
    }
    return try {
        json.decodeFromString<UnsignedPayloadResponse>(response.body()).data.body
    } catch (e: IllegalArgumentException) {
        throw TesserError.APIError(
            message = "Unsigned payload response is not valid: ${e.message}",
            status = response.statusCode(),
            headers = response.headers().map(),
            requestId = readGatewayRequestId(response),
            cause = e,
        )
    }
}

private fun buildUnsignedPayloadApiError(response: HttpResponse<String>): TesserError.APIError =
    TesserError.APIError(
        message = "Unsigned payload request returned HTTP ${response.statusCode()}: ${response.body()}",
        status = response.statusCode(),
        headers = response.headers().map(),
        requestId = readGatewayRequestId(response),
        errors = parseErrorDetails(response.body()),
    )

private fun parseErrorDetails(responseBody: String): List<TesserErrorDetail> {
    val envelope = runCatching { json.decodeFromString<ErrorEnvelope>(responseBody) }.getOrNull() ?: return emptyList()
    return envelope.errors.map { TesserErrorDetail(it.errorCode, it.errorMessage, it.uiMessage) }
}

private fun assertUnsignedPayloadMatchesRequest(
    body: String,
    signing: SigningConfig,
    params: CreateWalletParams,
) {
    val payload = parseUnsignedPayloadJson(body)
    if (readString(payload, "type") != CREATE_WALLET_ACCOUNTS_ACTIVITY_TYPE) {
        rejectUnsignedPayload("type is not $CREATE_WALLET_ACCOUNTS_ACTIVITY_TYPE")
    }
    if (readString(payload, "organizationId") != signing.enclaveId) {
        rejectUnsignedPayload("organizationId does not match the signing enclaveId")
    }
    val account = readSingleAccount(payload)
    if (readString(account, "name") != params.name) {
        rejectUnsignedPayload("account name does not match the requested wallet name")
    }
    if (readString(account, "addressFormat") != ETHEREUM_ADDRESS_FORMAT) {
        rejectUnsignedPayload("account addressFormat is not $ETHEREUM_ADDRESS_FORMAT")
    }
}

private fun parseUnsignedPayloadJson(body: String): JsonObject {
    val element = runCatching { json.parseToJsonElement(body) }.getOrNull()
    return element as? JsonObject ?: rejectUnsignedPayload("body is not a JSON object")
}

private fun readSingleAccount(payload: JsonObject): JsonObject {
    val parameters = payload["parameters"] as? JsonObject ?: rejectUnsignedPayload("parameters is missing")
    val accounts = parameters["accounts"] as? JsonArray ?: rejectUnsignedPayload("parameters.accounts is missing")
    if (accounts.size != 1) {
        rejectUnsignedPayload("expected exactly one account, got ${accounts.size}")
    }
    return accounts[0] as? JsonObject ?: rejectUnsignedPayload("account is not a JSON object")
}

private fun readString(
    source: JsonObject,
    key: String,
): String? {
    val value: JsonElement? = source[key]
    if (value !is JsonPrimitive || !value.isString) {
        return null
    }
    return value.content
}

private fun rejectUnsignedPayload(reason: String): Nothing = throw TesserError.SigningError("Unsigned payload rejected: $reason")
