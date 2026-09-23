package com.soyxan.sidesubs

import android.content.SharedPreferences
import android.net.Uri
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets
import java.util.Locale
import java.util.UUID

data class JellyfinPendingLogin(
    val baseUrl: String,
    val serverId: String,
    val serverName: String,
    val secret: String,
    val code: String,
    val createdAtMs: Long = System.currentTimeMillis(),
)

class JellyfinAuthManager(
    private val preferences: SharedPreferences,
    private val diagnostics: DiagnosticLog,
) {
    private val deviceId: String
        get() = preferences.getString(KEY_DEVICE_ID, "").orEmpty().ifBlank {
            "sidesubs-android-${UUID.randomUUID()}".also {
                preferences.edit().putString(KEY_DEVICE_ID, it).apply()
            }
        }

    fun hasSavedConnection(): Boolean =
        preferences.getString(PlexAuthManager.KEY_PROVIDER, "").orEmpty() == MediaProviderType.JELLYFIN.name &&
            preferences.getString(PlexAuthManager.KEY_SERVER_URL, "").orEmpty().isNotBlank() &&
            preferences.getString(PlexAuthManager.KEY_SERVER_TOKEN, "").orEmpty().isNotBlank()

    fun beginLogin(rawBaseUrl: String): JellyfinPendingLogin {
        val baseUrl = normalizeBaseUrl(rawBaseUrl)
        check(baseUrl.isNotBlank()) { "Enter a valid Jellyfin server URL" }

        val info = requestJson("GET", "$baseUrl/System/Info/Public", null, null)
        val enabled = requestJson("GET", "$baseUrl/QuickConnect/Enabled", null, null)
        check(enabled.optBoolean("Value", false)) { "Quick Connect is disabled on this Jellyfin server" }

        diagnostics.add("Jellyfin Quick Connect request started")
        val pending = requestJson("POST", "$baseUrl/QuickConnect/Initiate", JSONObject(), null)
        val code = pending.optString("Code")
        val secret = pending.optString("Secret")
        check(code.isNotBlank() && secret.isNotBlank()) {
            "Jellyfin did not return a valid Quick Connect code"
        }

        diagnostics.add("Jellyfin Quick Connect code created")
        return JellyfinPendingLogin(
            baseUrl = baseUrl,
            serverId = info.optString("Id"),
            serverName = info.optString("ServerName").ifBlank { "Jellyfin" },
            secret = secret,
            code = code,
        )
    }

    fun pollLogin(pending: JellyfinPendingLogin): ProviderConnection? {
        check(System.currentTimeMillis() - pending.createdAtMs < QUICK_CONNECT_TIMEOUT_MS) {
            "Jellyfin Quick Connect request expired"
        }

        val connectUrl = Uri.parse("${pending.baseUrl}/QuickConnect/Connect")
            .buildUpon()
            .appendQueryParameter("Secret", pending.secret)
            .build()
            .toString()
        val state = requestJson("GET", connectUrl, null, null)
        if (!state.optBoolean("Authenticated", false)) return null

        val auth = requestJson(
            "POST",
            "${pending.baseUrl}/Users/AuthenticateWithQuickConnect",
            JSONObject().put("Secret", pending.secret),
            null,
        )
        val token = auth.optString("AccessToken")
        val userId = auth.optJSONObject("User")?.optString("Id").orEmpty()
        check(token.isNotBlank() && userId.isNotBlank()) {
            "Jellyfin authorized Quick Connect but did not return a usable token"
        }

        diagnostics.add("Jellyfin Quick Connect authorized")
        return ProviderConnection(
            provider = MediaProviderType.JELLYFIN,
            serverId = pending.serverId,
            serverName = pending.serverName,
            baseUrl = pending.baseUrl,
            accessToken = token,
            clientIdentifier = deviceId,
            userId = userId,
        ).also(::saveConnection)
    }

    fun restoreConnection(): ProviderConnection? {
        if (!hasSavedConnection()) return null
        val connection = ProviderConnection(
            provider = MediaProviderType.JELLYFIN,
            serverId = preferences.getString(PlexAuthManager.KEY_SERVER_ID, "").orEmpty(),
            serverName = preferences.getString(PlexAuthManager.KEY_SERVER_NAME, "Jellyfin").orEmpty(),
            baseUrl = preferences.getString(PlexAuthManager.KEY_SERVER_URL, "").orEmpty(),
            accessToken = preferences.getString(PlexAuthManager.KEY_SERVER_TOKEN, "").orEmpty(),
            clientIdentifier = deviceId,
            userId = preferences.getString(KEY_USER_ID, "").orEmpty(),
        )
        requestBytes("GET", "${connection.baseUrl}/Sessions", null, connection.accessToken)
        return connection
    }

    fun saveConnection(connection: ProviderConnection) {
        preferences.edit()
            .putString(PlexAuthManager.KEY_PROVIDER, connection.provider.name)
            .putString(PlexAuthManager.KEY_SERVER_ID, connection.serverId)
            .putString(PlexAuthManager.KEY_SERVER_NAME, connection.serverName)
            .putString(PlexAuthManager.KEY_SERVER_URL, connection.baseUrl)
            .putString(PlexAuthManager.KEY_SERVER_TOKEN, connection.accessToken)
            .putString(KEY_USER_ID, connection.userId)
            .apply()
        diagnostics.add("Saved Jellyfin connection")
    }

    fun signOut() {
        diagnostics.add("Jellyfin authorization removed")
        preferences.edit()
            .remove(PlexAuthManager.KEY_PROVIDER)
            .remove(PlexAuthManager.KEY_SERVER_ID)
            .remove(PlexAuthManager.KEY_SERVER_NAME)
            .remove(PlexAuthManager.KEY_SERVER_URL)
            .remove(PlexAuthManager.KEY_SERVER_TOKEN)
            .remove(KEY_USER_ID)
            .apply()
    }

    private fun requestJson(method: String, url: String, body: JSONObject?, token: String?): JSONObject {
        val text = requestBytes(method, url, body, token).toString(StandardCharsets.UTF_8).trim()
        if (text.equals("true", ignoreCase = true)) return JSONObject().put("Value", true)
        if (text.equals("false", ignoreCase = true)) return JSONObject().put("Value", false)
        return JSONObject(text)
    }

    private fun requestBytes(
        method: String,
        url: String,
        body: JSONObject?,
        token: String?,
    ): ByteArray {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.requestMethod = method
        connection.connectTimeout = 8_000
        connection.readTimeout = 15_000
        connection.useCaches = false
        connection.setRequestProperty("Accept", "application/json")
        connection.setRequestProperty("Authorization", authorizationHeader(token))

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
            val detail = payload.toString(StandardCharsets.UTF_8).take(400)
            error("Jellyfin HTTP $status${if (detail.isBlank()) "" else ": $detail"}")
        }
        return payload
    }

    private fun authorizationHeader(token: String?): String = buildString {
        append("MediaBrowser ")
        append("Client=\"SideSubs\", ")
        append("Device=\"Android\", ")
        append("DeviceId=\"$deviceId\", ")
        append("Version=\"1\"")
        if (!token.isNullOrBlank()) append(", Token=\"$token\"")
    }

    private fun normalizeBaseUrl(value: String): String {
        var url = value.trim().trimEnd('/')
        if (url.isBlank()) return ""
        if (!url.matches(Regex("(?i)^https?://.*"))) url = "http://$url"
        val uri = Uri.parse(url)
        val scheme = uri.scheme?.lowercase(Locale.US) ?: return ""
        if (scheme != "http" && scheme != "https") return ""
        val host = uri.host ?: return ""
        val authority = if (uri.port >= 0) "$host:${uri.port}" else host
        return Uri.Builder().scheme(scheme).encodedAuthority(authority).build().toString()
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

    companion object {
        private const val KEY_DEVICE_ID = "jellyfin_device_id"
        private const val KEY_USER_ID = "jellyfin_user_id"
        private const val QUICK_CONNECT_TIMEOUT_MS = 5 * 60 * 1000L
    }
}
