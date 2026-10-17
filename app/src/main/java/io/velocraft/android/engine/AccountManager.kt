package io.velocraft.android.engine

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import io.velocraft.android.util.VeloLogger
import org.json.JSONArray
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.UUID
import java.util.concurrent.Executors

/**
 * Manages both Microsoft Official Accounts (OAuth2 Device Code -> Xbox Live -> XSTS -> Minecraft Services)
 * and Offline Accounts, persisting active credentials for Minecraft 1.20.4 launch arguments.
 */
object AccountManager {

    enum class AccountType(val id: String, val label: String) {
        OFFLINE("OFFLINE", "Akun Offline (Lokal)"),
        MICROSOFT("MICROSOFT", "Akun Microsoft Resmi (MSA)")
    }

    data class PlayerAccount(
        val username: String,
        val uuid: String,
        val accessToken: String,
        val refreshToken: String,
        val type: AccountType,
        val lastLoginEpochMs: Long
    ) {
        val userType: String
            get() = if (type == AccountType.MICROSOFT) "msa" else "legacy"
    }

    private const val PREFS_NAME = "velocraft_account_prefs"
    private const val KEY_ACTIVE_TYPE = "active_type"
    private const val KEY_OFFLINE_USERNAME = "offline_username"
    private const val KEY_MSA_USERNAME = "msa_username"
    private const val KEY_MSA_UUID = "msa_uuid"
    private const val KEY_MSA_ACCESS_TOKEN = "msa_access_token"
    private const val KEY_MSA_REFRESH_TOKEN = "msa_refresh_token"
    private const val KEY_SAVED_ACCOUNTS_JSON = "saved_accounts_json"

    // Public Azure/Xbox Client ID supporting Device Code flow for XboxLive.signin
    private const val MSA_CLIENT_ID = "00000000402b5328"
    private const val DEVICE_CODE_URL = "https://login.live.com/oauth20_connect.srf"
    private const val TOKEN_URL = "https://login.live.com/oauth20_token.srf"

    private val authExecutor = Executors.newSingleThreadExecutor()

    @Volatile
    var msaAuthStatusText: String = "Siap Login Microsoft (OAuth2 / Xbox Live)"
        private set

    @Volatile
    var msaUserCode: String = ""
        private set

    @Volatile
    var msaVerificationUri: String = "https://www.microsoft.com/link"
        private set

    @Volatile
    var isMsaPolling: Boolean = false
        private set

    fun getActiveAccount(context: Context): PlayerAccount {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val legacyPrefs = context.getSharedPreferences("velocraft_prefs", Context.MODE_PRIVATE)
        val fallbackOfflineName = legacyPrefs.getString("player_username", "VeloPlayer") ?: "VeloPlayer"

        val typeStr = prefs.getString(KEY_ACTIVE_TYPE, AccountType.OFFLINE.id) ?: AccountType.OFFLINE.id
        val type = if (typeStr == AccountType.MICROSOFT.id) AccountType.MICROSOFT else AccountType.OFFLINE

        if (type == AccountType.MICROSOFT) {
            val msaName = prefs.getString(KEY_MSA_USERNAME, "") ?: ""
            val msaUuid = prefs.getString(KEY_MSA_UUID, "") ?: ""
            val msaToken = prefs.getString(KEY_MSA_ACCESS_TOKEN, "") ?: ""
            val msaRefresh = prefs.getString(KEY_MSA_REFRESH_TOKEN, "") ?: ""
            if (msaName.isNotBlank() && msaUuid.isNotBlank() && msaToken.isNotBlank()) {
                return PlayerAccount(
                    username = msaName,
                    uuid = msaUuid,
                    accessToken = msaToken,
                    refreshToken = msaRefresh,
                    type = AccountType.MICROSOFT,
                    lastLoginEpochMs = System.currentTimeMillis()
                )
            }
        }

        val offlineName = (prefs.getString(KEY_OFFLINE_USERNAME, fallbackOfflineName) ?: fallbackOfflineName)
            .trim()
            .ifEmpty { "VeloPlayer" }
        val offlineUuid = computeOfflineUuid(offlineName)
        return PlayerAccount(
            username = offlineName,
            uuid = offlineUuid,
            accessToken = "0",
            refreshToken = "",
            type = AccountType.OFFLINE,
            lastLoginEpochMs = System.currentTimeMillis()
        )
    }

    fun setOfflineAccount(context: Context, rawUsername: String): PlayerAccount {
        val clean = rawUsername
            .replace(Regex("[^a-zA-Z0-9_]"), "")
            .take(16)
            .ifEmpty { "VeloPlayer" }

        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_ACTIVE_TYPE, AccountType.OFFLINE.id)
            .putString(KEY_OFFLINE_USERNAME, clean)
            .apply()

        context.getSharedPreferences("velocraft_prefs", Context.MODE_PRIVATE)
            .edit()
            .putString("player_username", clean)
            .apply()

        val acc = getActiveAccount(context)
        saveAccountToHistory(context, acc)
        VeloLogger.section("ACCOUNT", "Switched to Offline Account: ${acc.username} (UUID=${acc.uuid})")
        return acc
    }

    fun setAccountMode(context: Context, mode: AccountType) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(KEY_ACTIVE_TYPE, mode.id).apply()
    }

    fun getSelectedMode(context: Context): AccountType {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val raw = prefs.getString(KEY_ACTIVE_TYPE, AccountType.OFFLINE.id)
        return if (raw == AccountType.MICROSOFT.id) AccountType.MICROSOFT else AccountType.OFFLINE
    }

    fun hasSavedMicrosoftAccount(context: Context): Boolean {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val name = prefs.getString(KEY_MSA_USERNAME, "") ?: ""
        val token = prefs.getString(KEY_MSA_ACCESS_TOKEN, "") ?: ""
        return name.isNotBlank() && token.isNotBlank()
    }

    fun getSavedMicrosoftUsername(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getString(KEY_MSA_USERNAME, "") ?: ""
    }

    fun logoutMicrosoftAccount(context: Context) {
        isMsaPolling = false
        msaUserCode = ""
        msaAuthStatusText = "Akun Microsoft berhasil dikeluarkan."
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .remove(KEY_MSA_USERNAME)
            .remove(KEY_MSA_UUID)
            .remove(KEY_MSA_ACCESS_TOKEN)
            .remove(KEY_MSA_REFRESH_TOKEN)
            .putString(KEY_ACTIVE_TYPE, AccountType.OFFLINE.id)
            .apply()
    }

    fun cancelMsaPolling() {
        isMsaPolling = false
        msaUserCode = ""
        msaAuthStatusText = "Proses login Microsoft dibatalkan."
    }

    fun openVerificationBrowser(context: Context) {
        if (msaVerificationUri.isNotBlank()) {
            openVerificationUrlInBrowser(context, msaVerificationUri, msaUserCode)
        }
    }

    fun getSavedAccountHistory(context: Context): List<PlayerAccount> {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val raw = prefs.getString(KEY_SAVED_ACCOUNTS_JSON, "[]") ?: "[]"
        val list = mutableListOf<PlayerAccount>()
        try {
            val arr = JSONArray(raw)
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                val type = if (obj.optString("type") == AccountType.MICROSOFT.id) {
                    AccountType.MICROSOFT
                } else {
                    AccountType.OFFLINE
                }
                list.add(
                    PlayerAccount(
                        username = obj.optString("username", "VeloPlayer"),
                        uuid = obj.optString("uuid", ""),
                        accessToken = obj.optString("accessToken", "0"),
                        refreshToken = obj.optString("refreshToken", ""),
                        type = type,
                        lastLoginEpochMs = obj.optLong("lastLoginEpochMs", 0L)
                    )
                )
            }
        } catch (_: Exception) {
        }
        if (list.isEmpty()) {
            list.add(getActiveAccount(context))
        }
        return list
    }

    private fun saveAccountToHistory(context: Context, account: PlayerAccount) {
        try {
            val current = getSavedAccountHistory(context).toMutableList()
            current.removeAll { it.username.equals(account.username, ignoreCase = true) && it.type == account.type }
            current.add(0, account)
            val trimmed = current.take(6)
            val arr = JSONArray()
            trimmed.forEach { acc ->
                arr.put(
                    JSONObject().apply {
                        put("username", acc.username)
                        put("uuid", acc.uuid)
                        put("accessToken", acc.accessToken)
                        put("refreshToken", acc.refreshToken)
                        put("type", acc.type.id)
                        put("lastLoginEpochMs", acc.lastLoginEpochMs)
                    }
                )
            }
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY_SAVED_ACCOUNTS_JSON, arr.toString())
                .apply()
        } catch (_: Exception) {
        }
    }

    fun startMicrosoftLoginFlow(
        context: Context,
        openBrowserImmediately: Boolean = true,
        onStatusUpdate: () -> Unit
    ) {
        if (isMsaPolling) {
            if (openBrowserImmediately && msaVerificationUri.isNotBlank()) {
                openVerificationUrlInBrowser(context, msaVerificationUri, msaUserCode)
            }
            return
        }

        isMsaPolling = true
        msaAuthStatusText = "Meminta Kode Perangkat OAuth2 dari Microsoft Live..."
        onStatusUpdate()

        authExecutor.execute {
            try {
                val body = "client_id=${URLEncoder.encode(MSA_CLIENT_ID, "UTF-8")}" +
                    "&scope=${URLEncoder.encode("service::user.auth.xboxlive.com::MBI_SSL", "UTF-8")}" +
                    "&response_type=device_code"

                val respStr = httpPostForm(DEVICE_CODE_URL, body)
                val json = JSONObject(respStr)
                val userCode = json.optString("user_code", "")
                val deviceCode = json.optString("device_code", "")
                val verifyUri = json.optString("verification_uri", "https://www.microsoft.com/link")
                val intervalSec = json.optInt("interval", 5).coerceAtLeast(4)
                val expiresIn = json.optInt("expires_in", 600)

                if (userCode.isBlank() || deviceCode.isBlank()) {
                    throw IllegalStateException("Respons kode perangkat kosong dari server OAuth.")
                }

                msaUserCode = userCode
                msaVerificationUri = verifyUri
                msaAuthStatusText = "Kode: $userCode (Disalin!) -> Buka $verifyUri & masukkan kode"
                copyCodeToClipboard(context, userCode)
                onStatusUpdate()

                if (openBrowserImmediately) {
                    openVerificationUrlInBrowser(context, verifyUri, userCode)
                }

                val deadlineMs = System.currentTimeMillis() + (expiresIn * 1000L)
                while (isMsaPolling && System.currentTimeMillis() < deadlineMs) {
                    Thread.sleep(intervalSec * 1000L)
                    if (!isMsaPolling) break

                    val pollBody = "grant_type=urn:ietf:params:oauth:grant-type:device_code" +
                        "&client_id=${URLEncoder.encode(MSA_CLIENT_ID, "UTF-8")}" +
                        "&device_code=${URLEncoder.encode(deviceCode, "UTF-8")}"

                    val pollResp = httpPostFormAllowError(TOKEN_URL, pollBody)
                    val pollJson = JSONObject(pollResp)
                    val msaToken = pollJson.optString("access_token", "")
                    val refreshToken = pollJson.optString("refresh_token", "")

                    if (msaToken.isNotBlank()) {
                        msaAuthStatusText = "Token Microsoft diterima! Mengautentikasi Xbox Live & Minecraft..."
                        onStatusUpdate()
                        completeXboxAndMinecraftChain(context, msaToken, refreshToken, onStatusUpdate)
                        return@execute
                    }

                    val err = pollJson.optString("error", "")
                    if (err.isNotBlank() && err != "authorization_pending" && err != "slow_down") {
                        msaAuthStatusText = "Login Microsoft dibatalkan/kedaluwarsa ($err)."
                        isMsaPolling = false
                        onStatusUpdate()
                        return@execute
                    }
                }
                isMsaPolling = false
                onStatusUpdate()
            } catch (t: Throwable) {
                isMsaPolling = false
                msaAuthStatusText = "Gagal menghubungkan OAuth Microsoft: ${t.message?.take(60)}"
                VeloLogger.warn("MSA Login warning: ${t.message}")
                onStatusUpdate()
            }
        }
    }

    fun cancelMicrosoftPolling(onStatusUpdate: () -> Unit) {
        isMsaPolling = false
        msaAuthStatusText = "Login Microsoft dibatalkan."
        onStatusUpdate()
    }

    private fun completeXboxAndMinecraftChain(
        context: Context,
        msaAccessToken: String,
        msaRefreshToken: String,
        onStatusUpdate: () -> Unit
    ) {
        // 1. Xbox Live Authenticate
        val xblReq = JSONObject().apply {
            put("Properties", JSONObject().apply {
                put("AuthMethod", "RPS")
                put("SiteName", "user.auth.xboxlive.com")
                put("RpsTicket", msaAccessToken)
            })
            put("RelyingParty", "http://auth.xboxlive.com")
            put("TokenType", "JWT")
        }
        val xblResp = JSONObject(httpPostJson("https://user.auth.xboxlive.com/user/authenticate", xblReq.toString()))
        val xblToken = xblResp.getString("Token")
        val uhs = xblResp.getJSONObject("DisplayClaims")
            .getJSONArray("xui")
            .getJSONObject(0)
            .getString("uhs")

        // 2. XSTS Authorize
        val xstsReq = JSONObject().apply {
            put("Properties", JSONObject().apply {
                put("SandboxId", "RETAIL")
                put("UserTokens", JSONArray().put(xblToken))
            })
            put("RelyingParty", "rp://api.minecraftservices.com/")
            put("TokenType", "JWT")
        }
        val xstsResp = JSONObject(httpPostJson("https://xsts.auth.xboxlive.com/xsts/authorize", xstsReq.toString()))
        val xstsToken = xstsResp.getString("Token")

        // 3. Minecraft Services Login with Xbox
        val mcLoginReq = JSONObject().apply {
            put("identityToken", "XBL3.0 x=$uhs;$xstsToken")
        }
        val mcLoginResp = JSONObject(
            httpPostJson("https://api.minecraftservices.com/authentication/login_with_xbox", mcLoginReq.toString())
        )
        val mcAccessToken = mcLoginResp.getString("access_token")

        // 4. Fetch Official Minecraft Profile (Username + UUID)
        val profileResp = JSONObject(
            httpGetBearer("https://api.minecraftservices.com/minecraft/profile", mcAccessToken)
        )
        val mcName = profileResp.optString("name", "")
        val rawId = profileResp.optString("id", "")
        if (mcName.isBlank() || rawId.isBlank()) {
            throw IllegalStateException("Akun Microsoft ini belum memiliki lisensi profil Minecraft Java Edition.")
        }
        val formattedUuid = formatRawUuid(rawId)

        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_ACTIVE_TYPE, AccountType.MICROSOFT.id)
            .putString(KEY_MSA_USERNAME, mcName)
            .putString(KEY_MSA_UUID, formattedUuid)
            .putString(KEY_MSA_ACCESS_TOKEN, mcAccessToken)
            .putString(KEY_MSA_REFRESH_TOKEN, msaRefreshToken)
            .apply()

        context.getSharedPreferences("velocraft_prefs", Context.MODE_PRIVATE)
            .edit()
            .putString("player_username", mcName)
            .apply()

        val acc = getActiveAccount(context)
        saveAccountToHistory(context, acc)

        isMsaPolling = false
        msaUserCode = ""
        msaAuthStatusText = "Terhubung Resmi: $mcName (UUID: ${formattedUuid.take(13)}...)"
        VeloLogger.section("ACCOUNT", "Microsoft Official Account authenticated: $mcName ($formattedUuid)")
        onStatusUpdate()
    }

    fun openVerificationUrlInBrowser(context: Context, url: String, code: String) {
        copyCodeToClipboard(context, code)
        try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        } catch (_: Exception) {
        }
    }

    private fun copyCodeToClipboard(context: Context, code: String) {
        if (code.isBlank()) return
        try {
            val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            cm?.setPrimaryClip(ClipData.newPlainText("Kode Login Microsoft", code))
        } catch (_: Exception) {
        }
    }

    fun computeOfflineUuid(username: String): String {
        return UUID.nameUUIDFromBytes("OfflinePlayer:$username".toByteArray(StandardCharsets.UTF_8)).toString()
    }

    private fun formatRawUuid(raw: String): String {
        val clean = raw.replace("-", "").trim()
        if (clean.length != 32) return raw
        return "${clean.substring(0, 8)}-${clean.substring(8, 12)}-${clean.substring(12, 16)}-${clean.substring(16, 20)}-${clean.substring(20, 32)}"
    }

    private fun httpPostForm(urlStr: String, body: String): String {
        val conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 12000
            readTimeout = 12000
            doOutput = true
            setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            setRequestProperty("Accept", "application/json")
        }
        OutputStreamWriter(conn.outputStream, StandardCharsets.UTF_8).use { it.write(body) }
        return conn.inputStream.bufferedReader(StandardCharsets.UTF_8).use { it.readText() }
    }

    private fun httpPostFormAllowError(urlStr: String, body: String): String {
        val conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 12000
            readTimeout = 12000
            doOutput = true
            setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            setRequestProperty("Accept", "application/json")
        }
        OutputStreamWriter(conn.outputStream, StandardCharsets.UTF_8).use { it.write(body) }
        val stream = if (conn.responseCode in 200..299) conn.inputStream else conn.errorStream
        return stream?.bufferedReader(StandardCharsets.UTF_8)?.use { it.readText() } ?: "{}"
    }

    private fun httpPostJson(urlStr: String, jsonBody: String): String {
        val conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 12000
            readTimeout = 12000
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Accept", "application/json")
        }
        OutputStreamWriter(conn.outputStream, StandardCharsets.UTF_8).use { it.write(jsonBody) }
        return conn.inputStream.bufferedReader(StandardCharsets.UTF_8).use { it.readText() }
    }

    private fun httpGetBearer(urlStr: String, bearerToken: String): String {
        val conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 12000
            readTimeout = 12000
            setRequestProperty("Authorization", "Bearer $bearerToken")
            setRequestProperty("Accept", "application/json")
        }
        return conn.inputStream.bufferedReader(StandardCharsets.UTF_8).use { it.readText() }
    }
}
