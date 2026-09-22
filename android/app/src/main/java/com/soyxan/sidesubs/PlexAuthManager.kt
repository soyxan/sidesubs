package com.soyxan.sidesubs

import android.content.SharedPreferences
import android.net.Uri
import android.util.Base64
import com.google.crypto.tink.subtle.Ed25519Sign
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.UUID

data class PlexPendingLogin(
    val pinId: Long,
    val code: String,
    val authUrl: String,
    val createdAtMs: Long = System.currentTimeMillis(),
)

data class PlexServerResource(
    val id: String,
    val name: String,
    val accessToken: String,
    val connections: List<PlexServerConnection>,
)

data class PlexServerConnection(
    val uri: String,
    val local: Boolean,
    val relay: Boolean,
)

class PlexAuthManager(
    private val preferences: SharedPreferences,
) {
    val clientIdentifier: String
        get() = ensureIdentity().clientId

    fun hasSavedServer(): Boolean =
        preferences.getString(KEY_SERVER_ID, "").orEmpty().isNotBlank()

    fun beginLogin(): PlexPendingLogin {
        val identity = ensureIdentity()
        val jwk = JSONObject()
            .put("kty", "OKP")
            .put("crv", "Ed25519")
            .put("x", base64Url(identity.publicKey))
            .put("kid", identity.kid)
            .put("alg", "EdDSA")
            .put("use", "sig")

        val body = JSONObject()
            .put("jwk", jwk)
            .put("strong", true)

        val response = requestJson(
            method = "POST",
            url = "$PLEX_CLIENTS/api/v2/pins",
            body = body,
            token = null,
        )

        val pinId = response.optLong("id", -1L)
        val code = response.optString("code")
        check(pinId > 0 && code.isNotBlank()) { "Plex did not return a valid sign-in PIN" }

        val fragmentQuery = Uri.Builder()
            .appendQueryParameter("clientID", identity.clientId)
            .appendQueryParameter("code", code)
            .appendQueryParameter("context[device][product]", PRODUCT)
            .build()
            .encodedQuery
            .orEmpty()

        val authUrl = "$PLEX_AUTH_APP#?$fragmentQuery"
        return PlexPendingLogin(pinId, code, authUrl)
    }

    fun pollLogin(pending: PlexPendingLogin): String? {
        check(System.currentTimeMillis() - pending.createdAtMs < PIN_TIMEOUT_MS) {
            "Plex sign-in request expired"
        }

        val deviceJwt = createDeviceJwt()
        val url = Uri.parse("$PLEX_CLIENTS/api/v2/pins/${pending.pinId}")
            .buildUpon()
            .appendQueryParameter("deviceJWT", deviceJwt)
            .build()
            .toString()

        val response = requestJson("GET", url, null, null)
        val token = firstNonBlank(
            jsonString(response, "authToken"),
            jsonString(response, "auth_token"),
        )
        if (token.isBlank()) return null

        preferences.edit()
            .putString(KEY_ACCOUNT_TOKEN, token)
            .apply()
        return token
    }

    fun listServers(): List<PlexServerResource> {
        val token = ensureAccountToken()
        val url = Uri.parse("$PLEX_CLIENTS/api/v2/resources")
            .buildUpon()
            .appendQueryParameter("includeHttps", "1")
            .appendQueryParameter("includeRelay", "1")
            .appendQueryParameter("includeIPv6", "1")
            .build()
            .toString()

        val payload = requestBytes("GET", url, null, token)
        val array = JSONArray(payload.toString(StandardCharsets.UTF_8))

        return buildList {
            for (i in 0 until array.length()) {
                val item = array.optJSONObject(i) ?: continue
                val provides = item.optString("provides")
                if (!provides.split(",").any { it.trim().equals("server", ignoreCase = true) }) continue

                val accessToken = jsonString(item, "accessToken")
                if (accessToken.isBlank()) continue

                val id = firstNonBlank(
                    item.optString("clientIdentifier"),
                    item.optString("machineIdentifier"),
                )
                if (id.isBlank()) continue

                val connectionsJson = item.optJSONArray("connections") ?: JSONArray()
                val connections = buildList {
                    for (j in 0 until connectionsJson.length()) {
                        val connection = connectionsJson.optJSONObject(j) ?: continue
                        val uri = connection.optString("uri")
                        if (uri.isBlank()) continue
                        add(
                            PlexServerConnection(
                                uri = uri.trimEnd('/'),
                                local = connection.optBoolean("local", false),
                                relay = connection.optBoolean("relay", false),
                            )
                        )
                    }
                }
                if (connections.isEmpty()) continue

                add(
                    PlexServerResource(
                        id = id,
                        name = item.optString("name").ifBlank { "Plex Media Server" },
                        accessToken = accessToken,
                        connections = connections,
                    )
                )
            }
        }
    }

    fun selectServer(server: PlexServerResource): ProviderConnection {
        val connection = bestConnection(server)
        preferences.edit()
            .putString(KEY_PROVIDER, MediaProviderType.PLEX.name)
            .putString(KEY_SERVER_ID, server.id)
            .putString(KEY_SERVER_NAME, server.name)
            .putString(KEY_SERVER_URL, connection.uri)
            .putString(KEY_SERVER_TOKEN, server.accessToken)
            .apply()

        return ProviderConnection(
            provider = MediaProviderType.PLEX,
            serverId = server.id,
            serverName = server.name,
            baseUrl = connection.uri,
            accessToken = server.accessToken,
            clientIdentifier = clientIdentifier,
        )
    }

    fun restoreConnection(): ProviderConnection? {
        val savedId = preferences.getString(KEY_SERVER_ID, "").orEmpty()
        if (savedId.isBlank()) return null

        val server = listServers().firstOrNull { it.id == savedId } ?: return null
        return selectServer(server)
    }

    fun signOut() {
        preferences.edit()
            .remove(KEY_ACCOUNT_TOKEN)
            .remove(KEY_PROVIDER)
            .remove(KEY_SERVER_ID)
            .remove(KEY_SERVER_NAME)
            .remove(KEY_SERVER_URL)
            .remove(KEY_SERVER_TOKEN)
            .remove(KEY_PRIVATE_KEY)
            .remove(KEY_PUBLIC_KEY)
            .remove(KEY_KID)
            .apply()
    }

    private fun ensureAccountToken(): String {
        val token = preferences.getString(KEY_ACCOUNT_TOKEN, "").orEmpty()
        check(token.isNotBlank()) { "Not signed in to Plex" }

        val expiresAt = jwtExpiration(token)
        if (expiresAt == null || expiresAt > System.currentTimeMillis() / 1000L + REFRESH_MARGIN_SECONDS) {
            return token
        }

        val nonceResponse = requestJson(
            method = "GET",
            url = "$PLEX_CLIENTS/api/v2/auth/nonce",
            body = null,
            token = null,
        )
        val nonce = nonceResponse.optString("nonce")
        check(nonce.isNotBlank()) { "Plex did not return an authentication nonce" }

        val body = JSONObject().put("jwt", createDeviceJwt(nonce))
        val refreshed = requestJson(
            method = "POST",
            url = "$PLEX_CLIENTS/api/v2/auth/token",
            body = body,
            token = null,
        )
        val newToken = firstNonBlank(
            jsonString(refreshed, "auth_token"),
            jsonString(refreshed, "authToken"),
        )
        check(newToken.isNotBlank()) { "Plex did not return a refreshed token" }

        preferences.edit().putString(KEY_ACCOUNT_TOKEN, newToken).apply()
        return newToken
    }

    private fun createDeviceJwt(nonce: String? = null): String {
        val identity = ensureIdentity()
        val now = System.currentTimeMillis() / 1000L

        val header = JSONObject()
            .put("alg", "EdDSA")
            .put("kid", identity.kid)
            .put("typ", "JWT")

        val payload = JSONObject()
            .put("aud", "plex.tv")
            .put("iss", identity.clientId)
            .put("iat", now)
            .put("exp", now + DEVICE_JWT_SECONDS)
            .put("scope", "username,friendly_name")

        if (!nonce.isNullOrBlank()) payload.put("nonce", nonce)

        val encodedHeader = base64Url(header.toString().toByteArray(StandardCharsets.UTF_8))
        val encodedPayload = base64Url(payload.toString().toByteArray(StandardCharsets.UTF_8))
        val signingInput = "$encodedHeader.$encodedPayload"
        val signature = Ed25519Sign(identity.privateKey)
            .sign(signingInput.toByteArray(StandardCharsets.UTF_8))

        return "$signingInput.${base64Url(signature)}"
    }

    private fun ensureIdentity(): Identity {
        val existingClientId = preferences.getString(KEY_CLIENT_ID, "").orEmpty()
        val existingPrivate = decodeBase64Url(preferences.getString(KEY_PRIVATE_KEY, "").orEmpty())
        val existingPublic = decodeBase64Url(preferences.getString(KEY_PUBLIC_KEY, "").orEmpty())
        val existingKid = preferences.getString(KEY_KID, "").orEmpty()

        if (
            existingClientId.isNotBlank() &&
            existingPrivate.size == 32 &&
            existingPublic.size == 32 &&
            existingKid.isNotBlank()
        ) {
            return Identity(existingClientId, existingKid, existingPrivate, existingPublic)
        }

        val clientId = existingClientId.ifBlank { "sidesubs-android-${UUID.randomUUID()}" }
        val keyPair = Ed25519Sign.KeyPair.newKeyPair()
        val kid = base64Url(
            MessageDigest.getInstance("SHA-256").digest(keyPair.publicKey)
        ).take(22)

        preferences.edit()
            .putString(KEY_CLIENT_ID, clientId)
            .putString(KEY_PRIVATE_KEY, base64Url(keyPair.privateKey))
            .putString(KEY_PUBLIC_KEY, base64Url(keyPair.publicKey))
            .putString(KEY_KID, kid)
            .apply()

        return Identity(clientId, kid, keyPair.privateKey, keyPair.publicKey)
    }

    private fun bestConnection(server: PlexServerResource): PlexServerConnection {
        val savedUrl = if (preferences.getString(KEY_SERVER_ID, "") == server.id) {
            preferences.getString(KEY_SERVER_URL, "").orEmpty()
        } else {
            ""
        }
        val candidates = server.connections.sortedWith(
            compareBy<PlexServerConnection>(
                { if (it.uri == savedUrl) -1 else if (it.local && !it.relay) 0 else if (!it.relay) 1 else 2 },
                { if (it.uri.startsWith("https://", ignoreCase = true)) 0 else 1 },
            )
        )

        var rejectedToken = false
        for (candidate in candidates) {
            val status = runCatching { probeSessions(candidate.uri, server.accessToken) }.getOrNull()
            if (status == 200) return candidate
            if (status == 401 || status == 403) rejectedToken = true
        }
        if (rejectedToken) error("The Plex server rejected the discovered authorization.")
        error("None of the connections advertised by this Plex server can be reached.")
    }

    private fun probeSessions(baseUrl: String, token: String): Int {
        val connection = URL("${baseUrl.trimEnd('/')}/status/sessions")
            .openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 2_500
            connection.readTimeout = 3_500
            connection.useCaches = false
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("X-Plex-Token", token)
            connection.setRequestProperty("X-Plex-Client-Identifier", clientIdentifier)
            connection.setRequestProperty("X-Plex-Product", PRODUCT)
            val status = connection.responseCode
            if (status != 200) return status

            // Check the same authenticated endpoint that PlexClient will use.
            val payload = readFully(connection.inputStream)
            return if (JSONObject(payload.toString(StandardCharsets.UTF_8)).has("MediaContainer")) {
                200
            } else {
                502
            }
        } finally {
            connection.disconnect()
        }
    }

    private fun jwtExpiration(token: String): Long? = runCatching {
        val parts = token.split('.')
        if (parts.size < 2) return@runCatching null
        val payload = JSONObject(
            String(decodeBase64Url(parts[1]), StandardCharsets.UTF_8)
        )
        payload.optLong("exp").takeIf { it > 0 }
    }.getOrNull()

    private fun requestJson(
        method: String,
        url: String,
        body: JSONObject?,
        token: String?,
    ): JSONObject = JSONObject(
        requestBytes(method, url, body, token).toString(StandardCharsets.UTF_8)
    )

    private fun requestBytes(
        method: String,
        url: String,
        body: JSONObject?,
        token: String?,
    ): ByteArray {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.requestMethod = method
        connection.connectTimeout = 10_000
        connection.readTimeout = 15_000
        connection.useCaches = false
        connection.setRequestProperty("Accept", "application/json")
        connection.setRequestProperty("X-Plex-Product", PRODUCT)
        connection.setRequestProperty("X-Plex-Version", "1")
        connection.setRequestProperty("X-Plex-Platform", "Android")
        connection.setRequestProperty("X-Plex-Device", "SideSubs")
        connection.setRequestProperty("X-Plex-Client-Identifier", clientIdentifier)
        if (!token.isNullOrBlank()) connection.setRequestProperty("X-Plex-Token", token)

        if (body != null) {
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json")
            connection.outputStream.use {
                it.write(body.toString().toByteArray(StandardCharsets.UTF_8))
            }
        }

        val status = connection.responseCode
        val payload = readFully(if (status >= 400) connection.errorStream else connection.inputStream)
        connection.disconnect()

        if (status !in 200..299) {
            val detail = payload.toString(StandardCharsets.UTF_8).take(500)
            error("Plex authentication HTTP $status${if (detail.isBlank()) "" else ": $detail"}")
        }
        return payload
    }

    private fun readFully(stream: InputStream?): ByteArray {
        if (stream == null) return ByteArray(0)
        return stream.use { input ->
            ByteArrayOutputStream().use { output ->
                val buffer = ByteArray(8192)
                while (true) {
                    val read = input.read(buffer)
                    if (read == -1) break
                    output.write(buffer, 0, read)
                }
                output.toByteArray()
            }
        }
    }

    private fun base64Url(value: ByteArray): String =
        Base64.encodeToString(value, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)

    private fun decodeBase64Url(value: String): ByteArray = runCatching {
        if (value.isBlank()) ByteArray(0)
        else Base64.decode(value, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
    }.getOrDefault(ByteArray(0))

    private fun jsonString(json: JSONObject, key: String): String =
        if (json.isNull(key)) "" else json.optString(key)

    private fun firstNonBlank(vararg values: String): String =
        values.firstOrNull { it.isNotBlank() }.orEmpty()

    private data class Identity(
        val clientId: String,
        val kid: String,
        val privateKey: ByteArray,
        val publicKey: ByteArray,
    )

    companion object {
        private const val PRODUCT = "SideSubs"
        private const val PLEX_CLIENTS = "https://clients.plex.tv"
        private const val PLEX_AUTH_APP = "https://app.plex.tv/auth"
        private const val DEVICE_JWT_SECONDS = 300L
        private const val REFRESH_MARGIN_SECONDS = 43_200L
        private const val PIN_TIMEOUT_MS = 5 * 60 * 1000L

        const val KEY_PROVIDER = "media_provider"
        const val KEY_SERVER_ID = "media_server_id"
        const val KEY_SERVER_NAME = "media_server_name"
        const val KEY_SERVER_URL = "media_server_url"
        const val KEY_SERVER_TOKEN = "media_server_token"

        private const val KEY_CLIENT_ID = "plex_client_id"
        private const val KEY_PRIVATE_KEY = "plex_private_key"
        private const val KEY_PUBLIC_KEY = "plex_public_key"
        private const val KEY_KID = "plex_kid"
        private const val KEY_ACCOUNT_TOKEN = "plex_account_token"
    }
}
