package io.velocraft.android.auth

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets
import java.util.UUID

/**
 * Manages player authentication for VeloCraft Direct:
 * 1. Instant Offline Mode (Deterministic UUID v3 matching `OfflinePlayer:<username>`).
 * 2. Official Microsoft OAuth2 -> Xbox Live (XBL) -> XSTS -> Minecraft Services JWT token exchange.
 */
class PlayerSessionManager(context: Context) {

    data class PlayerSession(
        val username: String,
        val uuid: String,
        val accessToken: String,
        val accountType: AccountType
    )

    enum class AccountType(val cliArg: String) {
        OFFLINE("legacy"),
        MICROSOFT("msa")
    }

    private val prefs = context.getSharedPreferences("velocraft_session", Context.MODE_PRIVATE)

    fun getActiveSession(): PlayerSession {
        val username = prefs.getString("username", "VeloPlayer") ?: "VeloPlayer"
        val typeStr = prefs.getString("account_type", AccountType.OFFLINE.name) ?: AccountType.OFFLINE.name
        val type = runCatching { AccountType.valueOf(typeStr) }.getOrDefault(AccountType.OFFLINE)
        val savedUuid = prefs.getString("uuid", null)
        val uuid = if (!savedUuid.isNullOrBlank()) {
            savedUuid
        } else {
            generateOfflineUuid(username)
        }
        val token = prefs.getString("access_token", "0") ?: "0"

        return PlayerSession(
            username = username,
            uuid = uuid,
            accessToken = token,
            accountType = type
        )
    }

    fun saveOfflineSession(username: String): PlayerSession {
        val cleanName = username.trim()
            .replace(Regex("[^a-zA-Z0-9_]"), "")
            .take(16)
            .ifEmpty { "VeloPlayer" }
        val uuid = generateOfflineUuid(cleanName)

        prefs.edit()
            .putString("username", cleanName)
            .putString("uuid", uuid)
            .putString("access_token", "0")
            .putString("account_type", AccountType.OFFLINE.name)
            .apply()

        return PlayerSession(cleanName, uuid, "0", AccountType.OFFLINE)
    }

    fun saveMicrosoftSession(username: String, uuid: String, mcAccessToken: String): PlayerSession {
        prefs.edit()
            .putString("username", username)
            .putString("uuid", uuid)
            .putString("access_token", mcAccessToken)
            .putString("account_type", AccountType.MICROSOFT.name)
            .apply()

        return PlayerSession(username, uuid, mcAccessToken, AccountType.MICROSOFT)
    }

    /**
     * Exchanges a Microsoft OAuth2 Access Token for a full Minecraft Java Edition Profile & JWT:
     * Step 1: MS OAuth Token -> Xbox Live (XBL) Token + UserHash (uhs)
     * Step 2: XBL Token -> XSTS Token (`rp://api.minecraftservices.com/`)
     * Step 3: XSTS Token + uhs -> Minecraft Access Token (`login_with_xbox`)
     * Step 4: Fetch Player Username & UUID (`minecraft/profile`)
     */
    suspend fun authenticateWithMicrosoftToken(msAccessToken: String): Result<PlayerSession> =
        withContext(Dispatchers.IO) {
            runCatching {
                // Step 1: Xbox Live Authenticate
                val xblReq = JSONObject().apply {
                    put("Properties", JSONObject().apply {
                        put("AuthMethod", "RPS")
                        put("SiteName", "user.auth.xboxlive.com")
                        put("RpsTicket", "d=$msAccessToken")
                    })
                    put("RelyingParty", "http://auth.xboxlive.com")
                    put("TokenType", "JWT")
                }
                val xblResp = postJson(
                    "https://user.auth.xboxlive.com/user/authenticate",
                    xblReq.toString()
                )
                val xblToken = xblResp.getString("Token")
                val uhs = xblResp.getJSONObject("DisplayClaims")
                    .getJSONArray("xui")
                    .getJSONObject(0)
                    .getString("uhs")

                // Step 2: XSTS Authorize for Minecraft Services
                val xstsReq = JSONObject().apply {
                    put("Properties", JSONObject().apply {
                        put("SandboxId", "RETAIL")
                        put("UserTokens", JSONArray().put(xblToken))
                    })
                    put("RelyingParty", "rp://api.minecraftservices.com/")
                    put("TokenType", "JWT")
                }
                val xstsResp = postJson(
                    "https://xsts.auth.xboxlive.com/xsts/authorize",
                    xstsReq.toString()
                )
                val xstsToken = xstsResp.getString("Token")

                // Step 3: Minecraft Services Login with Xbox
                val mcLoginReq = JSONObject().apply {
                    put("identityToken", "XBL3.0 x=$uhs;$xstsToken")
                }
                val mcLoginResp = postJson(
                    "https://api.minecraftservices.com/authentication/login_with_xbox",
                    mcLoginReq.toString()
                )
                val mcAccessToken = mcLoginResp.getString("access_token")

                // Step 4: Fetch Minecraft Java Profile (UUID & Username)
                val profileConn = (URL("https://api.minecraftservices.com/minecraft/profile")
                    .openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    setRequestProperty("Authorization", "Bearer $mcAccessToken")
                    connectTimeout = 15_000
                    readTimeout = 15_000
                }
                val profileJson = JSONObject(profileConn.inputStream.bufferedReader().use { it.readText() })
                val mcUsername = profileJson.getString("name")
                val rawId = profileJson.getString("id")
                val formattedUuid = formatDashlessUuid(rawId)

                saveMicrosoftSession(mcUsername, formattedUuid, mcAccessToken)
            }
        }

    private fun postJson(urlStr: String, body: String): JSONObject {
        val conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            connectTimeout = 15_000
            readTimeout = 15_000
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Accept", "application/json")
        }
        conn.outputStream.use { it.write(body.toByteArray(StandardCharsets.UTF_8)) }
        val respText = conn.inputStream.bufferedReader().use { it.readText() }
        return JSONObject(respText)
    }

    private fun generateOfflineUuid(username: String): String {
        val bytes = "OfflinePlayer:$username".toByteArray(StandardCharsets.UTF_8)
        return UUID.nameUUIDFromBytes(bytes).toString()
    }

    private fun formatDashlessUuid(raw: String): String {
        if (raw.contains("-") || raw.length != 32) return raw
        return "${raw.substring(0, 8)}-${raw.substring(8, 12)}-${raw.substring(12, 16)}-${raw.substring(16, 20)}-${raw.substring(20, 32)}"
    }
}
