import kotlinx.coroutines.runBlocking
import xyz.tesser.sdk.CreateWalletParams
import xyz.tesser.sdk.LocalSigner
import xyz.tesser.sdk.SigningConfig
import xyz.tesser.sdk.WalletType
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse

/**
 * End-to-end harness for `LocalSigner.signCreateWallet`.
 *
 * Reads required values from environment variables (System.getenv -- no third-party
 * env loader; spec section 10 Open Item 6). Performs an OAuth client_credentials token
 * exchange directly (the SDK does not absorb OAuth in v0.0.1), then signs and
 * submits a wallet-create request against Tesser staging.
 *
 * Run:
 *   API_BASE_URL=https://staging.tesser.xyz \
 *   AUTH_TOKEN_URL=https://auth.tesser.xyz/oauth/token \
 *   API_CLIENT_ID=<id> API_CLIENT_SECRET=<secret> \
 *   SIGNING_PUBLIC_KEY=<hex> SIGNING_PRIVATE_KEY=<hex> SIGNING_ENCLAVE_ID=<org> \
 *   CREATE_WALLET_TYPE=stablecoin_ethereum \
 *   ./gradlew :examples:create-wallet:run
 *
 * AUTH_TOKEN_URL is a separate field per spec section 7.8 — Tesser hosts the OAuth
 * endpoint on a different host than the API base. Ask Tesser support for the exact
 * URL for your environment (sandbox / staging / prod).
 */
fun main(): Unit = runBlocking {
    val baseUrl = requireEnv("API_BASE_URL")
    val authTokenUrl = requireEnv("AUTH_TOKEN_URL")
    // Per spec section 7.8: audience defaults to the API base URL if not explicitly set.
    val audience = optionalEnv("API_AUDIENCE") ?: baseUrl
    val clientId = requireEnv("API_CLIENT_ID")
    val clientSecret = requireEnv("API_CLIENT_SECRET")
    val pubKey = requireEnv("SIGNING_PUBLIC_KEY")
    val privKey = requireEnv("SIGNING_PRIVATE_KEY")
    val enclaveId = requireEnv("SIGNING_ENCLAVE_ID")
    val walletTypeRaw = requireEnv("CREATE_WALLET_TYPE")

    val walletType = WalletType.fromWireValue(walletTypeRaw)

    println("Fetching access token from $authTokenUrl (audience=$audience) ...")
    val token = fetchToken(authTokenUrl, clientId, clientSecret, audience)

    val signer = LocalSigner(SigningConfig(pubKey, privKey, enclaveId))
    println("Signing CreateWallet activity for type=$walletType ...")
    val signed = signer.signCreateWallet(
        CreateWalletParams(name = "SDK verification wallet (kotlin)", type = walletType),
    )

    println("Submitting to $baseUrl/v1/accounts/wallets ...")
    val responseBody = postJson(
        url = "$baseUrl/v1/accounts/wallets",
        bearer = token,
        body = """{"signature":"${signed.signature}","name":"SDK verification wallet (kotlin)","type":"$walletTypeRaw","is_managed":true}""",
    )
    println("Wallet created. Response: $responseBody")
}

private fun requireEnv(name: String): String =
    System.getenv(name)?.takeIf { it.isNotBlank() }
        ?: error("Missing required environment variable: $name")

private fun optionalEnv(name: String): String? =
    System.getenv(name)?.takeIf { it.isNotBlank() }

private fun fetchToken(
    authTokenUrl: String,
    clientId: String,
    clientSecret: String,
    audience: String,
): String {
    val client = HttpClient.newHttpClient()
    val form = buildString {
        append("grant_type=client_credentials")
        append("&client_id=").append(urlEncode(clientId))
        append("&client_secret=").append(urlEncode(clientSecret))
        append("&audience=").append(urlEncode(audience))
    }
    val request = HttpRequest.newBuilder()
        .uri(URI.create(authTokenUrl))
        .header("Content-Type", "application/x-www-form-urlencoded")
        .POST(HttpRequest.BodyPublishers.ofString(form))
        .build()
    val resp = client.send(request, HttpResponse.BodyHandlers.ofString())
    check(resp.statusCode() in 200..299) {
        "OAuth token exchange failed: ${resp.statusCode()} ${resp.body()}"
    }
    // Naive JSON parse — production code should use kotlinx.serialization.
    // The token endpoint returns {"access_token":"...","token_type":"Bearer",...}.
    val tokenRegex = Regex("\"access_token\"\\s*:\\s*\"([^\"]+)\"")
    return tokenRegex.find(resp.body())?.groupValues?.get(1)
        ?: error("OAuth response did not contain access_token: ${resp.body()}")
}

private fun postJson(url: String, bearer: String, body: String): String {
    val client = HttpClient.newHttpClient()
    val request = HttpRequest.newBuilder()
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

private fun urlEncode(s: String): String =
    java.net.URLEncoder.encode(s, Charsets.UTF_8)
