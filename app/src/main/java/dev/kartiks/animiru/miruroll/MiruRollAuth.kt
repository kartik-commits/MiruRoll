package dev.kartiks.animiru.miruroll

import android.content.SharedPreferences
import android.util.Log
import android.webkit.CookieManager
import kotlinx.serialization.json.Json
import okhttp3.FormBody
import okhttp3.Headers
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.net.HttpURLConnection
import java.util.UUID

class MiruRollAuth(
    private val preferences: SharedPreferences,
    private val json: Json,
) : Interceptor {

    private val authClient = OkHttpClient.Builder().build()

    override fun intercept(chain: Interceptor.Chain): Response {
        val originalRequest = chain.request()
        val url = originalRequest.url
        val host = url.host

        if (!host.contains("crunchyroll.com") && !host.contains("crunchyrollsvc.com")) {
            return chain.proceed(originalRequest)
        }

        val isPlayback = url.encodedPath.contains("/playback/")
        val userAgent = if (isPlayback) WEB_USER_AGENT else USER_AGENT
        val token = getAccessToken(forceRefresh = false)

        val requestBuilder = originalRequest.newBuilder()
            .header("User-Agent", userAgent)
            .header("Authorization", "Bearer $token")

        val cookieManager = runCatching { CookieManager.getInstance() }.getOrNull()
        val cookies = cookieManager?.getCookie(url.toString())
        if (!cookies.isNullOrBlank()) {
            requestBuilder.header("Cookie", cookies)
        }

        val authenticatedRequest = requestBuilder.build()
        val response = chain.proceed(authenticatedRequest)

        if (response.code == HttpURLConnection.HTTP_UNAUTHORIZED) {
            response.close()
            synchronized(this) {
                val newToken = getAccessToken(forceRefresh = true)
                val retryBuilder = originalRequest.newBuilder()
                    .header("User-Agent", userAgent)
                    .header("Authorization", "Bearer $newToken")
                if (!cookies.isNullOrBlank()) {
                    retryBuilder.header("Cookie", cookies)
                }
                return chain.proceed(retryBuilder.build())
            }
        }

        return response
    }

    fun getRefreshTokenFromWebView(): String? {
        return runCatching {
            val cookieManager = CookieManager.getInstance() ?: return null
            val domains = listOf(
                "https://www.crunchyroll.com",
                "https://sso.crunchyroll.com",
                "https://crunchyroll.com",
                "https://beta-api.crunchyroll.com",
            )
            for (domain in domains) {
                val cookies = cookieManager.getCookie(domain) ?: continue
                val parts = cookies.split(";").map { it.trim() }
                for (part in parts) {
                    if (part.startsWith("etp_rt=")) {
                        val token = part.substringAfter("etp_rt=").trim()
                        if (token.isNotBlank()) return token
                    }
                }
            }
            null
        }.getOrNull()
    }

    @Synchronized
    fun getAccessToken(forceRefresh: Boolean = false): String {
        val cachedToken = preferences.getString(KEY_ACCESS_TOKEN, null)
        val expiryTime = preferences.getLong(KEY_TOKEN_EXPIRY, 0L)

        if (!forceRefresh && !cachedToken.isNullOrBlank() && System.currentTimeMillis() < expiryTime) {
            return cachedToken
        }

        val deviceId = preferences.getString(KEY_DEVICE_ID, null) ?: UUID.randomUUID().toString().also {
            preferences.edit().putString(KEY_DEVICE_ID, it).apply()
        }

        val webViewRefreshToken = getRefreshTokenFromWebView()
        val customRefreshToken = preferences.getString(PREF_CUSTOM_REFRESH_TOKEN, null)?.trim()
        val storedRefreshToken = preferences.getString(KEY_REFRESH_TOKEN, null)?.trim()
        val userEmail = preferences.getString(PREF_EMAIL, null)?.trim()
        val userPass = preferences.getString(PREF_PASSWORD, null)

        val finalResponse = when {
            !webViewRefreshToken.isNullOrBlank() -> {
                Log.d(TAG, "Authenticating using WebView etp_rt cookie")
                runCatching { requestWebCookieToken(webViewRefreshToken, deviceId) }.getOrElse { e ->
                    Log.w(TAG, "WebView token exchange failed, falling back to guest", e)
                    requestGuestToken(deviceId)
                }
            }
            !customRefreshToken.isNullOrBlank() -> {
                Log.d(TAG, "Authenticating using custom refresh token")
                runCatching { requestRefreshToken(customRefreshToken, deviceId) }.getOrElse { e ->
                    Log.w(TAG, "Custom refresh token failed, falling back to guest", e)
                    requestGuestToken(deviceId)
                }
            }
            !storedRefreshToken.isNullOrBlank() -> {
                Log.d(TAG, "Authenticating using stored refresh token")
                runCatching { requestRefreshToken(storedRefreshToken, deviceId) }.getOrElse { e ->
                    Log.w(TAG, "Stored refresh token failed, falling back to guest", e)
                    requestGuestToken(deviceId)
                }
            }
            !userEmail.isNullOrBlank() && !userPass.isNullOrBlank() -> {
                Log.d(TAG, "Authenticating using user credentials")
                runCatching { requestPasswordSignIn(userEmail, userPass, deviceId) }.getOrElse { e ->
                    Log.w(TAG, "Password auth failed, falling back to guest", e)
                    requestGuestToken(deviceId)
                }
            }
            else -> {
                Log.d(TAG, "Authenticating as guest")
                requestGuestToken(deviceId)
            }
        }

        val expiresAt = System.currentTimeMillis() + ((finalResponse.expires_in - 60) * 1000)

        val editor = preferences.edit()
            .putString(KEY_ACCESS_TOKEN, finalResponse.access_token)
            .putLong(KEY_TOKEN_EXPIRY, expiresAt)

        if (!finalResponse.refresh_token.isNullOrBlank()) {
            editor.putString(KEY_REFRESH_TOKEN, finalResponse.refresh_token)
        }
        if (!finalResponse.account_id.isNullOrBlank()) {
            editor.putString(KEY_ACCOUNT_ID, finalResponse.account_id)
        }
        editor.apply()

        return finalResponse.access_token
    }

    private fun requestWebCookieToken(etpRt: String, deviceId: String): TokenResponse {
        val formBody = FormBody.Builder()
            .add("grant_type", "etp_rt_cookie")
            .add("device_id", deviceId)
            .add("device_type", "chrome")
            .build()

        val request = Request.Builder()
            .url("https://beta-api.crunchyroll.com/auth/v1/token")
            .headers(
                Headers.headersOf(
                    "User-Agent", USER_AGENT,
                    "Authorization", WEB_BASIC_AUTH_HEADER,
                    "Content-Type", "application/x-www-form-urlencoded",
                    "Cookie", "etp_rt=$etpRt; device_id=$deviceId",
                ),
            )
            .post(formBody)
            .build()

        val response = authClient.newCall(request).execute()
        val body = response.body.string()

        if (!response.isSuccessful) {
            throw IllegalStateException("Crunchyroll Web Auth Error (HTTP ${response.code}): $body")
        }

        return json.decodeFromString<TokenResponse>(body)
    }

    private fun requestRefreshToken(refreshToken: String, deviceId: String): TokenResponse {
        val formBody = FormBody.Builder()
            .add("grant_type", "refresh_token")
            .add("refresh_token", refreshToken)
            .add("scope", "offline_access")
            .add("device_id", deviceId)
            .add("device_name", "Pixel")
            .add("device_type", "Android")
            .build()

        return executeAndroidAuthRequest(formBody)
    }

    private fun requestPasswordSignIn(email: String, pass: String, deviceId: String): TokenResponse {
        val formBody = FormBody.Builder()
            .add("grant_type", "password")
            .add("username", email)
            .add("password", pass)
            .add("scope", "offline_access")
            .add("device_id", deviceId)
            .add("device_name", "Pixel")
            .add("device_type", "Android")
            .build()

        return executeAndroidAuthRequest(formBody)
    }

    private fun requestGuestToken(deviceId: String): TokenResponse {
        val formBody = FormBody.Builder()
            .add("grant_type", "client_id")
            .add("device_id", deviceId)
            .add("device_name", "Pixel")
            .add("device_type", "Android")
            .build()

        return executeAndroidAuthRequest(formBody)
    }

    private fun executeAndroidAuthRequest(formBody: FormBody): TokenResponse {
        val request = Request.Builder()
            .url("https://beta-api.crunchyroll.com/auth/v1/token")
            .headers(
                Headers.headersOf(
                    "User-Agent", USER_AGENT,
                    "Authorization", ANDROID_BASIC_AUTH_HEADER,
                    "Content-Type", "application/x-www-form-urlencoded",
                ),
            )
            .post(formBody)
            .build()

        val response = authClient.newCall(request).execute()
        val body = response.body.string()

        if (!response.isSuccessful) {
            throw IllegalStateException("Crunchyroll Android Auth Error (HTTP ${response.code}): $body")
        }

        return json.decodeFromString<TokenResponse>(body)
    }

    fun isUserLoggedIn(): Boolean {
        val webViewRt = getRefreshTokenFromWebView()
        val email = preferences.getString(PREF_EMAIL, null)
        val customRt = preferences.getString(PREF_CUSTOM_REFRESH_TOKEN, null)
        val rt = preferences.getString(KEY_REFRESH_TOKEN, null)
        return (!webViewRt.isNullOrBlank()) || (!email.isNullOrBlank()) || (!customRt.isNullOrBlank()) || (!rt.isNullOrBlank())
    }

    fun clearSession() {
        preferences.edit()
            .remove(KEY_ACCESS_TOKEN)
            .remove(KEY_TOKEN_EXPIRY)
            .remove(KEY_REFRESH_TOKEN)
            .remove(KEY_ACCOUNT_ID)
            .apply()
    }

    companion object {
        private const val TAG = "MiruRollAuth"

        const val USER_AGENT = "Crunchyroll/3.58.2 Android/13 okhttp/4.12.0"
        const val WEB_USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

        // Web Client Basic Auth (noaihdevm_6iyg0a8l0q:)
        const val WEB_BASIC_AUTH_HEADER = "Basic bm9haWhkZXZtXzZpeWcwYThsMHE6"

        // Android App Client Basic Auth (vtdizwxdmekmsmbguice:O4IJ-YSpi_vODpih4TtOaZ3CNuIVUmQC)
        const val ANDROID_BASIC_AUTH_HEADER = "Basic dnRkaXp3eGRtZWttc21iZ3VpY2U6TzRJSi1ZU3BpX3ZPRHBpaDRUdE9hWjNDTnVJVlVtUUM="

        private const val KEY_ACCESS_TOKEN = "cr_access_token"
        private const val KEY_TOKEN_EXPIRY = "cr_token_expiry"
        private const val KEY_REFRESH_TOKEN = "cr_refresh_token"
        private const val KEY_ACCOUNT_ID = "cr_account_id"
        private const val KEY_DEVICE_ID = "cr_device_id"

        const val PREF_EMAIL = "cr_pref_email"
        const val PREF_PASSWORD = "cr_pref_password"
        const val PREF_CUSTOM_REFRESH_TOKEN = "cr_pref_custom_refresh_token"
    }
}
