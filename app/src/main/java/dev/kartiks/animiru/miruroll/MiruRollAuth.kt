package dev.kartiks.animiru.miruroll

import android.content.SharedPreferences
import android.util.Log
import android.webkit.CookieManager
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.FormBody
import okhttp3.Headers
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.net.HttpURLConnection
import java.nio.charset.StandardCharsets
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
            cookieManager.flush()
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
        val isCachedGuest = preferences.getBoolean(KEY_IS_GUEST, false)

        val deviceId = preferences.getString(KEY_DEVICE_ID, null) ?: UUID.randomUUID().toString().also {
            preferences.edit().putString(KEY_DEVICE_ID, it).apply()
        }

        val webViewRefreshToken = getRefreshTokenFromWebView()
        val customRefreshToken = preferences.getString(PREF_CUSTOM_REFRESH_TOKEN, null)?.trim()
        val storedRefreshToken = preferences.getString(KEY_REFRESH_TOKEN, null)?.trim()
        val userEmail = preferences.getString(PREF_EMAIL, null)?.trim()
        val userPass = preferences.getString(PREF_PASSWORD, null)

        val lastWebViewRt = preferences.getString(KEY_LAST_WEBVIEW_RT, null)
        val hasNewWebViewSession = !webViewRefreshToken.isNullOrBlank() && webViewRefreshToken != lastWebViewRt

        val hasUserCredentials = !webViewRefreshToken.isNullOrBlank() ||
            !customRefreshToken.isNullOrBlank() ||
            (!userEmail.isNullOrBlank() && !userPass.isNullOrBlank()) ||
            (!storedRefreshToken.isNullOrBlank() && !isCachedGuest)

        if ((isCachedGuest && hasUserCredentials) || hasNewWebViewSession) {
            Log.d(TAG, "Cached token is guest or new session detected, forcing token exchange")
        } else if (!forceRefresh && !cachedToken.isNullOrBlank() && System.currentTimeMillis() < expiryTime) {
            return cachedToken
        }

        val activeProfileId = preferences.getString(KEY_PROFILE_ID, null)
        val activePin = preferences.getString(PREF_PROFILE_PIN, null)?.trim()
        val storedRt = preferences.getString(KEY_REFRESH_TOKEN, null)?.trim()
            ?: preferences.getString(PREF_CUSTOM_REFRESH_TOKEN, null)?.trim()
            ?: webViewRefreshToken

        // Maintain the active profile across routine background token refreshes
        if (!hasNewWebViewSession && !activeProfileId.isNullOrBlank() && !storedRt.isNullOrBlank() && !isCachedGuest) {
            Log.d(TAG, "Refreshing access token for active profile: $activeProfileId")
            val switchResult = switchProfile(activeProfileId, activePin)
            if (switchResult.isSuccess) {
                return preferences.getString(KEY_ACCESS_TOKEN, null)!!
            } else {
                Log.w(TAG, "Failed to refresh token for active profile, falling back to base auth", switchResult.exceptionOrNull())
            }
        }

        return obtainBaseAccessToken()
    }

    private fun obtainBaseAccessToken(): String {
        val webViewRefreshToken = getRefreshTokenFromWebView()
        val customRefreshToken = preferences.getString(PREF_CUSTOM_REFRESH_TOKEN, null)?.trim()
        val storedRefreshToken = preferences.getString(KEY_REFRESH_TOKEN, null)?.trim()
        val isCachedGuest = preferences.getBoolean(KEY_IS_GUEST, false)
        val userEmail = preferences.getString(PREF_EMAIL, null)?.trim()
        val userPass = preferences.getString(PREF_PASSWORD, null)
        val deviceId = preferences.getString(KEY_DEVICE_ID, null) ?: UUID.randomUUID().toString().also {
            preferences.edit().putString(KEY_DEVICE_ID, it).apply()
        }

        var isGuest = false
        val finalResponse = when {
            !webViewRefreshToken.isNullOrBlank() -> {
                Log.d(TAG, "Authenticating using WebView etp_rt cookie")
                runCatching { requestWebCookieToken(webViewRefreshToken, deviceId) }.getOrElse { e ->
                    Log.w(TAG, "WebView token exchange failed, falling back to guest", e)
                    isGuest = true
                    requestGuestToken(deviceId)
                }
            }
            !customRefreshToken.isNullOrBlank() -> {
                Log.d(TAG, "Authenticating using custom refresh token")
                runCatching { requestRefreshToken(customRefreshToken, deviceId) }.getOrElse { e ->
                    Log.w(TAG, "Custom refresh token failed, falling back to guest", e)
                    isGuest = true
                    requestGuestToken(deviceId)
                }
            }
            !storedRefreshToken.isNullOrBlank() && !isCachedGuest -> {
                Log.d(TAG, "Authenticating using stored refresh token")
                runCatching { requestRefreshToken(storedRefreshToken, deviceId) }.getOrElse { e ->
                    Log.w(TAG, "Stored refresh token failed, falling back to guest", e)
                    isGuest = true
                    requestGuestToken(deviceId)
                }
            }
            !userEmail.isNullOrBlank() && !userPass.isNullOrBlank() -> {
                Log.d(TAG, "Authenticating using user credentials")
                runCatching { requestPasswordSignIn(userEmail, userPass, deviceId) }.getOrElse { e ->
                    Log.w(TAG, "Password auth failed, falling back to guest", e)
                    isGuest = true
                    requestGuestToken(deviceId)
                }
            }
            else -> {
                Log.d(TAG, "Authenticating as guest")
                isGuest = true
                requestGuestToken(deviceId)
            }
        }

        val expiresAt = System.currentTimeMillis() + ((finalResponse.expires_in - 60) * 1000)

        val editor = preferences.edit()
            .putString(KEY_ACCESS_TOKEN, finalResponse.access_token)
            .putLong(KEY_TOKEN_EXPIRY, expiresAt)
            .putBoolean(KEY_IS_GUEST, isGuest)

        if (!webViewRefreshToken.isNullOrBlank()) {
            editor.putString(KEY_LAST_WEBVIEW_RT, webViewRefreshToken)
        }
        if (!finalResponse.refresh_token.isNullOrBlank()) {
            editor.putString(KEY_REFRESH_TOKEN, finalResponse.refresh_token)
        }
        val tokenAccountId = finalResponse.account_id
            ?: extractJwtClaim(finalResponse.access_token, "account_id")
            ?: extractJwtClaim(finalResponse.access_token, "etp_user_id")
            ?: extractJwtClaim(finalResponse.access_token, "profile_id")
            ?: extractJwtClaim(finalResponse.access_token, "sub")
        if (!tokenAccountId.isNullOrBlank()) {
            editor.putString(KEY_ACCOUNT_ID, tokenAccountId)
        }
        val tokenProfileId = finalResponse.profile_id
            ?: extractJwtClaim(finalResponse.access_token, "profile_id")
        if (!tokenProfileId.isNullOrBlank() && preferences.getString(KEY_PROFILE_ID, null).isNullOrBlank()) {
            editor.putString(KEY_PROFILE_ID, tokenProfileId)
        }
        editor.apply()

        return finalResponse.access_token
    }

    private fun getValidAccessToken(): String {
        val currentToken = preferences.getString(KEY_ACCESS_TOKEN, null)
        val expiryTime = preferences.getLong(KEY_TOKEN_EXPIRY, 0L)
        if (!currentToken.isNullOrBlank() && System.currentTimeMillis() < expiryTime) {
            return currentToken
        }
        return obtainBaseAccessToken()
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

    fun fetchProfiles(): List<CrProfile> {
        val token = getAccessToken()
        val request = Request.Builder()
            .url("https://beta-api.crunchyroll.com/accounts/v1/me/multiprofile")
            .headers(
                Headers.headersOf(
                    "User-Agent", USER_AGENT,
                    "Authorization", "Bearer $token",
                ),
            )
            .get()
            .build()

        val response = authClient.newCall(request).execute()
        val body = response.body.string()
        if (!response.isSuccessful) {
            throw IllegalStateException("Failed to fetch profiles (HTTP ${response.code}): $body")
        }

        val parsed = json.decodeFromString<AllProfilesResponse>(body)
        val profiles = parsed.profiles

        preferences.edit().putString(KEY_PROFILES_JSON, body).apply()

        val currentProfileId = preferences.getString(KEY_PROFILE_ID, null)
        val active = profiles.find { it.isSelected }
            ?: profiles.find { it.profileId == currentProfileId }
            ?: profiles.firstOrNull()

        if (active != null) {
            preferences.edit()
                .putString(KEY_PROFILE_ID, active.profileId)
                .putString(KEY_PROFILE_NAME, active.profileName)
                .apply()
        }

        return profiles
    }

    fun getCachedProfiles(): List<CrProfile> {
        val jsonStr = preferences.getString(KEY_PROFILES_JSON, null) ?: return emptyList()
        return runCatching {
            json.decodeFromString<AllProfilesResponse>(jsonStr).profiles
        }.getOrElse { emptyList() }
    }

    fun getActiveProfileName(): String {
        return preferences.getString(KEY_PROFILE_NAME, null)
            ?: getCachedProfiles().find { it.isSelected }?.profileName
            ?: "Default Profile"
    }

    fun getActiveProfileId(): String? {
        return preferences.getString(KEY_PROFILE_ID, null)
            ?: getCachedProfiles().find { it.isSelected }?.profileId
    }

    fun getActiveProfilePreferredAudioLanguage(): String? {
        val activeId = getActiveProfileId() ?: return null
        return getCachedProfiles().find { it.profileId == activeId }?.preferredAudioLanguage
    }

    fun switchProfile(profileId: String, pin: String? = null): Result<CrProfile> {
        return runCatching {
            var refreshToken = preferences.getString(KEY_REFRESH_TOKEN, null)
                ?: preferences.getString(PREF_CUSTOM_REFRESH_TOKEN, null)

            if (refreshToken.isNullOrBlank()) {
                getAccessToken()
                refreshToken = preferences.getString(KEY_REFRESH_TOKEN, null)
                    ?: preferences.getString(PREF_CUSTOM_REFRESH_TOKEN, null)
            }

            if (refreshToken.isNullOrBlank()) {
                throw IllegalStateException("No refresh token available to switch profile. Please log in again.")
            }

            val deviceId = preferences.getString(KEY_DEVICE_ID, null) ?: UUID.randomUUID().toString().also {
                preferences.edit().putString(KEY_DEVICE_ID, it).apply()
            }

            val effectivePin = pin?.trim()?.ifBlank { null }
                ?: preferences.getString(PREF_PROFILE_PIN, null)?.trim()?.ifBlank { null }

            var cachedProfiles = getCachedProfiles()
            if (cachedProfiles.isEmpty()) {
                cachedProfiles = runCatching { fetchProfiles() }.getOrDefault(emptyList())
            }
            val targetProfile = cachedProfiles.find { it.profileId == profileId }
            val isPinProtected = targetProfile?.isPinProtected == true

            if (isPinProtected && effectivePin.isNullOrBlank()) {
                throw IllegalStateException("Profile '${targetProfile?.profileName ?: profileId}' is PIN-protected. Please set your PIN in 'Profile PIN (Optional)' setting first.")
            }

            var passToken: String? = null
            var accountId = preferences.getString(KEY_ACCOUNT_ID, null)
            if (isPinProtected && !effectivePin.isNullOrBlank()) {
                val currentToken = getValidAccessToken()
                if (accountId.isNullOrBlank()) {
                    accountId = extractJwtClaim(currentToken, "account_id")
                        ?: extractJwtClaim(currentToken, "sub")
                }
                if (!accountId.isNullOrBlank()) {
                    passToken = requestProfilePassToken(currentToken, accountId, profileId, effectivePin)
                }
            }

            val formBody = FormBody.Builder()
                .add("grant_type", "refresh_token_profile_id")
                .add("refresh_token", refreshToken)
                .add("profile_id", profileId)
                .add("scope", "offline_access")
                .add("device_id", deviceId)
                .add("device_name", "Pixel")
                .add("device_type", "Android")
                .build()

            val isWebViewSession = !getRefreshTokenFromWebView().isNullOrBlank()
            val primaryAuth = if (isWebViewSession) WEB_BASIC_AUTH_HEADER else ANDROID_BASIC_AUTH_HEADER
            val fallbackAuth = if (isWebViewSession) ANDROID_BASIC_AUTH_HEADER else WEB_BASIC_AUTH_HEADER

            fun executeSwitch(authHeader: String): Response {
                val req = Request.Builder()
                    .url("https://beta-api.crunchyroll.com/auth/v1/token")
                    .headers(
                        Headers.headersOf(
                            "User-Agent", if (authHeader == WEB_BASIC_AUTH_HEADER) WEB_USER_AGENT else USER_AGENT,
                            "Authorization", authHeader,
                            "Content-Type", "application/x-www-form-urlencoded",
                        ),
                    )
                    .post(formBody)

                if (!passToken.isNullOrBlank()) {
                    req.addHeader("CrPassToken", passToken)
                }
                return authClient.newCall(req.build()).execute()
            }

            var response = executeSwitch(primaryAuth)
            var body = response.body.string()

            if (!response.isSuccessful && body.contains("client_id")) {
                response = executeSwitch(fallbackAuth)
                body = response.body.string()
            }

            if (!response.isSuccessful) {
                if (body.contains("invalid_credentials") || body.contains("invalid_grant")) {
                    throw IllegalStateException("Switch profile failed: Profile may require a PIN or session expired. (HTTP ${response.code})")
                }
                throw IllegalStateException("Switch profile failed (HTTP ${response.code}): $body")
            }

            val tokenResponse = json.decodeFromString<TokenResponse>(body)
            val expiresAt = System.currentTimeMillis() + ((tokenResponse.expires_in - 60) * 1000)

            val targetName = targetProfile?.profileName ?: "Profile"

            val editor = preferences.edit()
                .putString(KEY_ACCESS_TOKEN, tokenResponse.access_token)
                .putLong(KEY_TOKEN_EXPIRY, expiresAt)
                .putBoolean(KEY_IS_GUEST, false)
                .putString(KEY_PROFILE_ID, profileId)
                .putString(KEY_PROFILE_NAME, targetName)
                .putString(PREF_ACTIVE_PROFILE, profileId)

            if (!tokenResponse.refresh_token.isNullOrBlank()) {
                editor.putString(KEY_REFRESH_TOKEN, tokenResponse.refresh_token)
            }
            val newAccountId = tokenResponse.account_id
                ?: extractJwtClaim(tokenResponse.access_token, "account_id")
                ?: extractJwtClaim(tokenResponse.access_token, "etp_user_id")
                ?: extractJwtClaim(tokenResponse.access_token, "profile_id")
                ?: extractJwtClaim(tokenResponse.access_token, "sub")
            if (!newAccountId.isNullOrBlank()) {
                editor.putString(KEY_ACCOUNT_ID, newAccountId)
            }

            if (cachedProfiles.isNotEmpty()) {
                val updatedProfiles = cachedProfiles.map {
                    it.copy(isSelected = it.profileId == profileId)
                }
                val updatedJson = json.encodeToString(AllProfilesResponse(profiles = updatedProfiles))
                editor.putString(KEY_PROFILES_JSON, updatedJson)
            }

            editor.apply()

            targetProfile?.copy(isSelected = true)
                ?: CrProfile(profileId = profileId, profileName = targetName, isSelected = true)
        }
    }

    private fun extractJwtClaim(jwt: String, claimKey: String): String? {
        return runCatching {
            val parts = jwt.split(".")
            if (parts.size >= 2) {
                val payloadBytes = android.util.Base64.decode(
                    parts[1],
                    android.util.Base64.URL_SAFE or android.util.Base64.NO_PADDING or android.util.Base64.NO_WRAP,
                )
                val jsonStr = String(payloadBytes, StandardCharsets.UTF_8)
                val obj = json.parseToJsonElement(jsonStr).jsonObject
                obj[claimKey]?.jsonPrimitive?.contentOrNull
            } else null
        }.getOrNull()
    }

    private fun requestProfilePassToken(accessToken: String, accountId: String, profileId: String, pin: String): String {
        val jsonBody = """{"credential_type":"profile_pin","profile_id":"$profileId","value":"$pin"}"""
        val mediaType = "application/json; charset=utf-8".toMediaTypeOrNull()
        val request = Request.Builder()
            .url("https://beta-api.crunchyroll.com/auth/v1/$accountId/passtoken")
            .headers(
                Headers.headersOf(
                    "User-Agent", USER_AGENT,
                    "Authorization", "Bearer $accessToken",
                    "Content-Type", "application/json",
                ),
            )
            .post(jsonBody.toRequestBody(mediaType))
            .build()

        val response = authClient.newCall(request).execute()
        val body = response.body.string()
        if (!response.isSuccessful) {
            if (body.contains("failed_pass_token_credentials") || body.contains("invalid_credential")) {
                throw IllegalStateException("Incorrect Profile PIN entered")
            }
            throw IllegalStateException("PIN verification failed (HTTP ${response.code}): $body")
        }
        val token = json.decodeFromString<PassTokenResponse>(body).effectiveToken
        if (token.isBlank()) {
            throw IllegalStateException("PIN verification returned empty pass token")
        }
        return token
    }

    fun isUserLoggedIn(): Boolean {
        if (preferences.getBoolean(KEY_IS_GUEST, false)) return false
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
            .remove(KEY_IS_GUEST)
            .remove(KEY_LAST_WEBVIEW_RT)
            .remove(KEY_PROFILE_ID)
            .remove(KEY_PROFILE_NAME)
            .remove(KEY_PROFILES_JSON)
            .apply()
    }

    fun getAccountId(): String? {
        val stored = preferences.getString(KEY_ACCOUNT_ID, null)
        if (!stored.isNullOrBlank()) return stored
        val token = runCatching { getAccessToken() }.getOrNull() ?: return null
        return extractJwtClaim(token, "account_id")
            ?: extractJwtClaim(token, "etp_user_id")
            ?: extractJwtClaim(token, "profile_id")
            ?: extractJwtClaim(token, "sub")
    }

    fun deleteWatchHistory(episodeId: String): Result<Unit> = runCatching {
        val accountId = getAccountId() ?: throw IllegalStateException("No account ID available")
        val token = getAccessToken()
        val url = "https://beta-api.crunchyroll.com/content/v2/$accountId/watch-history/$episodeId"
        val req = Request.Builder()
            .url(url)
            .headers(
                Headers.headersOf(
                    "User-Agent", USER_AGENT,
                    "Authorization", "Bearer $token",
                ),
            )
            .delete()
            .build()
        val resp = authClient.newCall(req).execute()
        if (!resp.isSuccessful && resp.code != 204 && resp.code != 404) {
            val err = resp.body.string()
            throw IllegalStateException("Failed to delete watch history (HTTP ${resp.code}): $err")
        }
    }

    fun savePlayhead(episodeId: String, playheadSeconds: Long): Result<Unit> = runCatching {
        val accountId = getAccountId() ?: throw IllegalStateException("No account ID available")
        val token = getAccessToken()
        val url = "https://beta-api.crunchyroll.com/content/v2/$accountId/playheads"
        val payload = json.encodeToString(SavePlayheadBody(contentId = episodeId, playhead = playheadSeconds))
        val mediaType = "application/json; charset=utf-8".toMediaTypeOrNull()
        val body = payload.toRequestBody(mediaType)
        val req = Request.Builder()
            .url(url)
            .headers(
                Headers.headersOf(
                    "User-Agent", USER_AGENT,
                    "Authorization", "Bearer $token",
                    "Content-Type", "application/json",
                ),
            )
            .post(body)
            .build()
        val resp = authClient.newCall(req).execute()
        if (!resp.isSuccessful && resp.code != 204) {
            val err = resp.body.string()
            throw IllegalStateException("Failed to save playhead (HTTP ${resp.code}): $err")
        }
    }

    fun markAsWatched(episodeId: String): Result<Unit> = runCatching {
        val accountId = getAccountId() ?: throw IllegalStateException("No account ID available")
        val token = getAccessToken()
        val url = "https://beta-api.crunchyroll.com/content/v2/discover/$accountId/mark_as_watched/$episodeId"
        val mediaType = "application/json; charset=utf-8".toMediaTypeOrNull()
        val body = "".toRequestBody(mediaType)
        val req = Request.Builder()
            .url(url)
            .headers(
                Headers.headersOf(
                    "User-Agent", USER_AGENT,
                    "Authorization", "Bearer $token",
                    "Content-Type", "application/json",
                ),
            )
            .post(body)
            .build()
        val resp = authClient.newCall(req).execute()
        if (!resp.isSuccessful && resp.code != 204) {
            val err = resp.body.string()
            throw IllegalStateException("Failed to mark as watched (HTTP ${resp.code}): $err")
        }
    }

    fun getPlayheads(contentIds: List<String>): Map<String, CrPlayhead> {
        if (contentIds.isEmpty()) return emptyMap()
        return runCatching {
            val accountId = getAccountId() ?: return emptyMap()
            val token = getAccessToken()
            contentIds.chunked(50).flatMap { chunk ->
                val idsParam = chunk.joinToString(",")
                val url = "https://beta-api.crunchyroll.com/content/v2/$accountId/playheads?content_ids=$idsParam"
                val req = Request.Builder()
                    .url(url)
                    .headers(
                        Headers.headersOf(
                            "User-Agent", USER_AGENT,
                            "Authorization", "Bearer $token",
                        ),
                    )
                    .get()
                    .build()
                val resp = authClient.newCall(req).execute()
                if (resp.isSuccessful) {
                    val respBody = resp.body.string()
                    val decoded = json.decodeFromString<PlayheadResponse>(respBody)
                    decoded.data
                } else {
                    emptyList()
                }
            }.associateBy { it.contentId }
        }.getOrDefault(emptyMap())
    }

    fun getPlayhead(episodeId: String): CrPlayhead? {
        return getPlayheads(listOf(episodeId))[episodeId]
    }

    fun fetchWatchHistory(pageSize: Int = 25): Result<List<CrWatchHistoryItem>> = runCatching {
        val accountId = getAccountId() ?: throw IllegalStateException("No account ID available")
        val token = getAccessToken()
        val url = "https://beta-api.crunchyroll.com/content/v2/$accountId/watch-history?page_size=$pageSize"
        val req = Request.Builder()
            .url(url)
            .headers(
                Headers.headersOf(
                    "User-Agent", USER_AGENT,
                    "Authorization", "Bearer $token",
                ),
            )
            .get()
            .build()
        val resp = authClient.newCall(req).execute()
        val body = resp.body.string()
        if (!resp.isSuccessful) {
            throw IllegalStateException("Failed to fetch watch history (HTTP ${resp.code}): $body")
        }
        json.decodeFromString<WatchHistoryResponse>(body).data
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
        private const val KEY_IS_GUEST = "cr_is_guest"
        private const val KEY_LAST_WEBVIEW_RT = "cr_last_webview_rt"
        const val KEY_PROFILE_ID = "cr_profile_id"
        const val KEY_PROFILE_NAME = "cr_profile_name"
        const val KEY_PROFILES_JSON = "cr_profiles_json"

        const val PREF_EMAIL = "cr_pref_email"
        const val PREF_PASSWORD = "cr_pref_password"
        const val PREF_CUSTOM_REFRESH_TOKEN = "cr_pref_custom_refresh_token"
        const val PREF_ACTIVE_PROFILE = "cr_pref_active_profile"
        const val PREF_PROFILE_PIN = "cr_pref_profile_pin"
    }
}
