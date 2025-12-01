package com.mypeople.walkolutionodometer

import android.content.Context
import android.util.Log
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.*

class StravaRepository(context: Context) {

    companion object {
        private const val TAG = "StravaRepository"
        private const val BASE_URL = "https://www.strava.com/"

        // OAuth URLs
        const val AUTH_URL = "https://www.strava.com/oauth/mobile/authorize"
        const val REDIRECT_URI = "walkolution://walkolution"
        const val SCOPE = "activity:write,read"

        // Conversion constants
        private const val CM_PER_ROTATION = 34.56f
        private const val METERS_PER_MILE = 1609.34f
    }

    private val tokenManager = StravaTokenManager(context)

    private val moshi = Moshi.Builder()
        .add(KotlinJsonAdapterFactory())
        .build()

    private val okHttpClient = OkHttpClient.Builder()
        .addInterceptor(HttpLoggingInterceptor().apply {
            level = HttpLoggingInterceptor.Level.BODY
        })
        .build()

    private val api: StravaApi = Retrofit.Builder()
        .baseUrl(BASE_URL)
        .client(okHttpClient)
        .addConverterFactory(MoshiConverterFactory.create(moshi))
        .build()
        .create(StravaApi::class.java)

    // Build OAuth authorization URL
    fun getAuthorizationUrl(): String {
        val encodedRedirectUri = URLEncoder.encode(REDIRECT_URI, "UTF-8")
        return "$AUTH_URL?" +
            "client_id=${BuildConfig.STRAVA_CLIENT_ID}&" +
            "redirect_uri=$encodedRedirectUri&" +
            "response_type=code&" +
            "approval_prompt=auto&" +
            "scope=$SCOPE"
    }

    // Exchange authorization code for tokens
    suspend fun exchangeCodeForTokens(code: String): Result<Unit> {
        return try {
            val response = api.exchangeToken(
                clientId = BuildConfig.STRAVA_CLIENT_ID,
                clientSecret = BuildConfig.STRAVA_CLIENT_SECRET,
                code = code
            )

            if (response.isSuccessful && response.body() != null) {
                tokenManager.saveTokens(response.body()!!)
                Log.d(TAG, "Token exchange successful")
                Result.success(Unit)
            } else {
                Log.e(TAG, "Token exchange failed: ${response.errorBody()?.string()}")
                Result.failure(Exception("Token exchange failed: ${response.code()}"))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Token exchange error", e)
            Result.failure(e)
        }
    }

    // Refresh access token if expired
    private suspend fun ensureValidToken(): String? {
        if (tokenManager.isTokenExpired()) {
            val refreshToken = tokenManager.getRefreshToken() ?: return null

            try {
                val response = api.refreshToken(
                    clientId = BuildConfig.STRAVA_CLIENT_ID,
                    clientSecret = BuildConfig.STRAVA_CLIENT_SECRET,
                    refreshToken = refreshToken
                )

                if (response.isSuccessful && response.body() != null) {
                    tokenManager.saveTokens(response.body()!!)
                    Log.d(TAG, "Token refresh successful")
                } else {
                    Log.e(TAG, "Token refresh failed: ${response.errorBody()?.string()}")
                    return null
                }
            } catch (e: Exception) {
                Log.e(TAG, "Token refresh error", e)
                return null
            }
        }

        return tokenManager.getAccessToken()
    }

    // Upload a session to Strava
    suspend fun uploadSession(session: SessionRecord): Result<StravaActivity> {
        val accessToken = ensureValidToken()
            ?: return Result.failure(Exception("Not authenticated with Strava"))

        // Convert session data to Strava format
        val distanceMeters = session.rotationCount * CM_PER_ROTATION / 100f
        // Use local time without 'Z' suffix for start_date_local
        // Strava expects this to be the actual local time, not UTC
        val dateFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).apply {
            timeZone = TimeZone.getDefault()  // Use device's local timezone
        }
        val startDate = dateFormat.format(Date(session.startTimeUnix * 1000))

        // Generate activity name
        val distanceMiles = distanceMeters / METERS_PER_MILE
        val name = "Treadmill Walk - %.2f mi".format(distanceMiles)

        return try {
            val response = api.createActivity(
                authorization = "Bearer $accessToken",
                name = name,
                sportType = "Walk",
                startDateLocal = startDate,
                elapsedTime = session.activeTimeSeconds,
                distance = distanceMeters,
                description = "Recorded with Walkolution Odometer",
                trainer = 1  // Mark as indoor/treadmill activity
            )

            if (response.isSuccessful && response.body() != null) {
                Log.d(TAG, "Activity uploaded: ${response.body()!!.id}")
                Result.success(response.body()!!)
            } else {
                Log.e(TAG, "Upload failed: ${response.errorBody()?.string()}")
                Result.failure(Exception("Upload failed: ${response.code()}"))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Upload error", e)
            Result.failure(e)
        }
    }

    suspend fun isAuthenticated() = tokenManager.isAuthenticated()
    suspend fun getAthleteName() = tokenManager.getAthleteName()
    suspend fun logout() = tokenManager.clearTokens()
}
