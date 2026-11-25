package dev.kartiks.animiru.miruroll

import android.content.SharedPreferences
import android.util.Log
import android.webkit.CookieManager
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
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
import java.util.Base64
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

class MiruRollAuth(
    private val preferences: SharedPreferences,
    private val json: Json,
) : Interceptor {

    private val authClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .writeTimeout(20, TimeUnit.SECONDS)
        .build()
    private val authEpoch = AtomicLong(0L)

    fun getAuthEpoch(): Long = authEpoch.get()

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

    private fun getOrCreateDeviceId(): String =
        preferences.getString(KEY_DEVICE_ID, null)?.ifBlank { null }
            ?: UUID.randomUUID().toString().also {
                preferences.edit().putString(KEY_DEVICE_ID, it).apply()
            }

    @Synchronized
    fun getAccessToken(forceRefresh: Boolean = false): String {
        val cachedToken = preferences.getString(KEY_ACCESS_TOKEN, null)
        val expiryTime = preferences.getLong(KEY_TOKEN_EXPIRY, 0L)
        val isCachedGuest = preferences.getBoolean(KEY_IS_GUEST, false)

        getOrCreateDeviceId()

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
        val storedRt = storedRefreshToken?.ifBlank { null }
            ?: customRefreshToken?.ifBlank { null }
            ?: webViewRefreshToken?.ifBlank { null }

        // Maintain the active profile across routine background token refreshes
        if (!hasNewWebViewSession && !activeProfileId.isNullOrBlank() && !storedRt.isNullOrBlank() && !isCachedGuest) {
            Log.d(TAG, "Refreshing access token for active profile: $activeProfileId")
            val switchResult = switchProfileInternal(activeProfileId, activePin, storedRt)
            if (switchResult.isSuccess) {
                preferences.getString(KEY_ACCESS_TOKEN, null)
                    ?.takeIf { it.isNotBlank() }
                    ?.let { return it }
            } else {
                Log.w(TAG, "Failed to refresh token for active profile, falling back to base auth", switchResult.exceptionOrNull())
            }
        }

        return obtainBaseAccessToken()
    }

    @Synchronized
    private fun obtainBaseAccessToken(): String {
        val webViewRefreshToken = getRefreshTokenFromWebView()
        val customRefreshToken = preferences.getString(PREF_CUSTOM_REFRESH_TOKEN, null)?.trim()
        val storedRefreshToken = preferences.getString(KEY_REFRESH_TOKEN, null)?.trim()
        val isCachedGuest = preferences.getBoolean(KEY_IS_GUEST, false)
        val userEmail = preferences.getString(PREF_EMAIL, null)?.trim()
        val userPass = preferences.getString(PREF_PASSWORD, null)
        val deviceId = getOrCreateDeviceId()

        val lastWebViewRt = preferences.getString(KEY_LAST_WEBVIEW_RT, null)
        val hasNewWebViewSession = !webViewRefreshToken.isNullOrBlank() && webViewRefreshToken != lastWebViewRt

        val attempts = buildList<Pair<String, () -> TokenResponse>> {
            if (hasNewWebViewSession && !webViewRefreshToken.isNullOrBlank()) {
                add("WebView etp_rt cookie" to { requestWebCookieToken(webViewRefreshToken, deviceId) })
            }
            if (!customRefreshToken.isNullOrBlank()) {
                add("custom refresh token" to { requestRefreshToken(customRefreshToken, deviceId) })
            }
            if (!storedRefreshToken.isNullOrBlank() && (!isCachedGuest || !hasNewWebViewSession)) {
                add("stored refresh token" to { requestRefreshToken(storedRefreshToken, deviceId) })
            }
            if (!hasNewWebViewSession && !webViewRefreshToken.isNullOrBlank()) {
                add("WebView etp_rt cookie" to { requestWebCookieToken(webViewRefreshToken, deviceId) })
            }
            if (!userEmail.isNullOrBlank() && !userPass.isNullOrBlank()) {
                add("user credentials" to { requestPasswordSignIn(userEmail, userPass, deviceId) })
            }
        }

        var finalResponse: TokenResponse? = null
        var isGuest = false
        for ((label, attempt) in attempts) {
            Log.d(TAG, "Authenticating using $label")
            val result = runCatching { attempt() }
            if (result.isSuccess) {
                finalResponse = result.getOrThrow()
                break
            }
            Log.w(TAG, "$label authentication failed, trying next available method", result.exceptionOrNull())
        }

        if (finalResponse == null) {
            Log.d(TAG, "Authenticating as guest")
            isGuest = true
            finalResponse = requestGuestToken(deviceId)
        }

        val ttlMs = if (isGuest && attempts.isNotEmpty()) {
            minOf((finalResponse.expires_in - 60).coerceAtLeast(15L) * 1000L, 30_000L)
        } else {
            (finalResponse.expires_in - 60).coerceAtLeast(15L) * 1000L
        }
        val expiresAt = System.currentTimeMillis() + ttlMs

        val editor = preferences.edit()
            .putString(KEY_ACCESS_TOKEN, finalResponse.access_token)
            .putLong(KEY_TOKEN_EXPIRY, expiresAt)
            .putBoolean(KEY_IS_GUEST, isGuest)

        if (!webViewRefreshToken.isNullOrBlank() && !isGuest) {
            editor.putString(KEY_LAST_WEBVIEW_RT, webViewRefreshToken)
        }
        if (!finalResponse.refresh_token.isNullOrBlank()) {
            editor.putString(KEY_REFRESH_TOKEN, finalResponse.refresh_token)
        }
        val tokenAccountId = finalResponse.account_id
            ?: parseJwtClaim(finalResponse.access_token, "account_id")
            ?: parseJwtClaim(finalResponse.access_token, "etp_user_id")
            ?: parseJwtClaim(finalResponse.access_token, "profile_id")
            ?: parseJwtClaim(finalResponse.access_token, "sub")
        if (!tokenAccountId.isNullOrBlank()) {
            editor.putString(KEY_ACCOUNT_ID, tokenAccountId)
        }
        val tokenProfileId = finalResponse.profile_id
            ?: parseJwtClaim(finalResponse.access_token, "profile_id")
        if (!tokenProfileId.isNullOrBlank() && preferences.getString(KEY_PROFILE_ID, null).isNullOrBlank()) {
            editor.putString(KEY_PROFILE_ID, tokenProfileId)
        }
        editor.apply()
        authEpoch.incrementAndGet()

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

    private fun executeTokenRequest(request: Request, errorPrefix: String): TokenResponse {
        authClient.newCall(request).execute().use { response ->
            val body = response.body.string()
            if (!response.isSuccessful) {
                throw IllegalStateException("$errorPrefix (HTTP ${response.code}): $body")
            }
            return json.decodeFromString<TokenResponse>(body)
        }
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

        return executeTokenRequest(request, "Crunchyroll Web Auth Error")
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

        return runCatching {
            executeTokenRequest(
                buildTokenFormRequest(formBody, ANDROID_BASIC_AUTH_HEADER),
                "Crunchyroll Android Auth Error",
            )
        }.getOrElse { firstErr ->
            if (firstErr.message?.contains("client_id") == true || firstErr.message?.contains("invalid_client") == true) {
                executeTokenRequest(
                    buildTokenFormRequest(formBody, WEB_BASIC_AUTH_HEADER),
                    "Crunchyroll Web Refresh Auth Error",
                )
            } else {
                throw firstErr
            }
        }
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

    private fun buildTokenFormRequest(formBody: FormBody, authHeader: String): Request =
        Request.Builder()
            .url("https://beta-api.crunchyroll.com/auth/v1/token")
            .headers(
                Headers.headersOf(
                    "User-Agent", USER_AGENT,
                    "Authorization", authHeader,
                    "Content-Type", "application/x-www-form-urlencoded",
                ),
            )
            .post(formBody)
            .build()

    private fun executeAndroidAuthRequest(formBody: FormBody): TokenResponse =
        executeTokenRequest(
            buildTokenFormRequest(formBody, ANDROID_BASIC_AUTH_HEADER),
            "Crunchyroll Android Auth Error",
        )

    fun fetchProfiles(): List<CrProfile> = fetchProfilesWithToken(getAccessToken())

    private fun fetchProfilesWithToken(token: String): List<CrProfile> {
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

        val body = authClient.newCall(request).execute().use { response ->
            val raw = response.body.string()
            if (!response.isSuccessful) {
                throw IllegalStateException("Failed to fetch profiles (HTTP ${response.code}): $raw")
            }
            raw
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

    @Synchronized
    fun switchProfile(profileId: String, pin: String? = null): Result<CrProfile> =
        switchProfileInternal(profileId = profileId, pin = pin, explicitRefreshToken = null)

    private fun switchProfileInternal(
        profileId: String,
        pin: String?,
        explicitRefreshToken: String?,
    ): Result<CrProfile> {
        return runCatching {
            var refreshToken = explicitRefreshToken
                ?: preferences.getString(KEY_REFRESH_TOKEN, null)
                ?: preferences.getString(PREF_CUSTOM_REFRESH_TOKEN, null)

            if (refreshToken.isNullOrBlank()) {
                obtainBaseAccessToken()
                refreshToken = preferences.getString(KEY_REFRESH_TOKEN, null)
                    ?: preferences.getString(PREF_CUSTOM_REFRESH_TOKEN, null)
            }

            if (refreshToken.isNullOrBlank()) {
                throw IllegalStateException("No refresh token available to switch profile. Please log in again.")
            }

            val deviceId = getOrCreateDeviceId()

            val effectivePin = pin?.trim()?.ifBlank { null }
                ?: preferences.getString(PREF_PROFILE_PIN, null)?.trim()?.ifBlank { null }

            var cachedProfiles = getCachedProfiles()
            if (cachedProfiles.isEmpty()) {
                cachedProfiles = runCatching {
                    fetchProfilesWithToken(getValidAccessToken())
                }.getOrDefault(emptyList())
            }
            val targetProfile = cachedProfiles.find { it.profileId == profileId }
            val isPinProtected = targetProfile?.isPinProtected == true

            if (isPinProtected && effectivePin.isNullOrBlank()) {
                throw IllegalStateException("Profile '${targetProfile.profileName}' is PIN-protected. Please set your PIN in 'Profile PIN (Optional)' setting first.")
            }

            var passToken: String? = null
            var accountId = preferences.getString(KEY_ACCOUNT_ID, null)
            if (isPinProtected && !effectivePin.isNullOrBlank()) {
                val currentToken = getValidAccessToken()
                if (accountId.isNullOrBlank()) {
                    accountId = parseJwtClaim(currentToken, "account_id")
                        ?: parseJwtClaim(currentToken, "sub")
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

            fun executeSwitch(authHeader: String): Pair<Int, String> {
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
                return authClient.newCall(req.build()).execute().use { resp ->
                    resp.code to resp.body.string()
                }
            }

            var (statusCode, body) = executeSwitch(primaryAuth)

            if (statusCode !in 200..299 && body.contains("client_id")) {
                val retry = executeSwitch(fallbackAuth)
                statusCode = retry.first
                body = retry.second
            }

            if (statusCode !in 200..299) {
                if (body.contains("invalid_credentials") || body.contains("invalid_grant")) {
                    throw IllegalStateException("Switch profile failed: Profile may require a PIN or session expired. (HTTP $statusCode)")
                }
                throw IllegalStateException("Switch profile failed (HTTP $statusCode): $body")
            }

            val tokenResponse = json.decodeFromString<TokenResponse>(body)
            val expiresAt = System.currentTimeMillis() + ((tokenResponse.expires_in - 60).coerceAtLeast(15L) * 1000L)

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
                ?: parseJwtClaim(tokenResponse.access_token, "account_id")
                ?: parseJwtClaim(tokenResponse.access_token, "etp_user_id")
                ?: parseJwtClaim(tokenResponse.access_token, "profile_id")
                ?: parseJwtClaim(tokenResponse.access_token, "sub")
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
            authEpoch.incrementAndGet()

            targetProfile?.copy(isSelected = true)
                ?: CrProfile(profileId = profileId, profileName = targetName, isSelected = true)
        }
    }

    private fun parseJwtClaim(jwt: String, claimKey: String): String? =
        extractJwtClaim(json, jwt, claimKey)

    private fun requestProfilePassToken(accessToken: String, accountId: String, profileId: String, pin: String): String {
        val jsonBody = buildJsonObject {
            put("credential_type", "profile_pin")
            put("profile_id", profileId)
            put("value", pin)
        }.toString()
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

        val body = authClient.newCall(request).execute().use { response ->
            val raw = response.body.string()
            if (!response.isSuccessful) {
                if (raw.contains("failed_pass_token_credentials") || raw.contains("invalid_credential")) {
                    throw IllegalStateException("Incorrect Profile PIN entered")
                }
                throw IllegalStateException("PIN verification failed (HTTP ${response.code}): $raw")
            }
            raw
        }
        val token = json.decodeFromString<PassTokenResponse>(body).effectiveToken
        if (token.isBlank()) {
            throw IllegalStateException("PIN verification returned empty pass token")
        }
        return token
    }

    fun isUserLoggedIn(): Boolean {
        if (preferences.getBoolean(KEY_IS_GUEST, false)) {
            val webViewRt = getRefreshTokenFromWebView()
            val email = preferences.getString(PREF_EMAIL, null)
            val customRt = preferences.getString(PREF_CUSTOM_REFRESH_TOKEN, null)
            return !webViewRt.isNullOrBlank() || !email.isNullOrBlank() || !customRt.isNullOrBlank()
        }
        val webViewRt = getRefreshTokenFromWebView()
        val email = preferences.getString(PREF_EMAIL, null)
        val customRt = preferences.getString(PREF_CUSTOM_REFRESH_TOKEN, null)
        val rt = preferences.getString(KEY_REFRESH_TOKEN, null)
        return (!webViewRt.isNullOrBlank()) || (!email.isNullOrBlank()) || (!customRt.isNullOrBlank()) || (!rt.isNullOrBlank())
    }

    @Synchronized
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
        authEpoch.incrementAndGet()
    }

    @Synchronized
    fun getAccountId(): String? {
        val stored = preferences.getString(KEY_ACCOUNT_ID, null)
        if (!stored.isNullOrBlank()) return stored
        val token = runCatching { getAccessToken() }.getOrNull() ?: return null
        return parseJwtClaim(token, "account_id")
            ?: parseJwtClaim(token, "etp_user_id")
            ?: parseJwtClaim(token, "profile_id")
            ?: parseJwtClaim(token, "sub")
    }

    private fun executeAccountRequest(
        request: Request,
        actionName: String,
        acceptedCodes: Set<Int> = setOf(204),
    ) {
        authClient.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful && resp.code !in acceptedCodes) {
                val err = resp.body.string()
                throw IllegalStateException("Failed to $actionName (HTTP ${resp.code}): $err")
            }
        }
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
        executeAccountRequest(req, "delete watch history", setOf(204, 404))
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
        executeAccountRequest(req, "save playhead", setOf(204))
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
        executeAccountRequest(req, "mark as watched", setOf(204))
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
                authClient.newCall(req).execute().use { resp ->
                    if (resp.isSuccessful) {
                        val respBody = resp.body.string()
                        val decoded = json.decodeFromString<PlayheadResponse>(respBody)
                        decoded.data
                    } else {
                        emptyList()
                    }
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
        val body = authClient.newCall(req).execute().use { resp ->
            val raw = resp.body.string()
            if (!resp.isSuccessful) {
                throw IllegalStateException("Failed to fetch watch history (HTTP ${resp.code}): $raw")
            }
            raw
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

        internal fun extractJwtClaim(json: Json, jwt: String, claimKey: String): String? =
            runCatching {
                val parts = jwt.split(".")
                if (parts.size < 2) return null
                val payloadBytes = Base64.getUrlDecoder().decode(parts[1])
                val jsonStr = String(payloadBytes, StandardCharsets.UTF_8)
                json.parseToJsonElement(jsonStr).jsonObject[claimKey]?.jsonPrimitive?.contentOrNull
            }.getOrNull()
    }
}
