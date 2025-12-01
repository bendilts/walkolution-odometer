package com.mypeople.walkolutionodometer

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.time.Instant

private val Context.stravaDataStore: DataStore<Preferences> by preferencesDataStore(name = "strava_tokens")

class StravaTokenManager(private val context: Context) {

    companion object {
        private val ACCESS_TOKEN = stringPreferencesKey("access_token")
        private val REFRESH_TOKEN = stringPreferencesKey("refresh_token")
        private val EXPIRES_AT = longPreferencesKey("expires_at")
        private val ATHLETE_ID = longPreferencesKey("athlete_id")
        private val ATHLETE_NAME = stringPreferencesKey("athlete_name")
    }

    suspend fun saveTokens(response: StravaTokenResponse) {
        context.stravaDataStore.edit { prefs ->
            prefs[ACCESS_TOKEN] = response.accessToken
            prefs[REFRESH_TOKEN] = response.refreshToken
            prefs[EXPIRES_AT] = response.expiresAt
            response.athlete?.let { athlete ->
                prefs[ATHLETE_ID] = athlete.id
                prefs[ATHLETE_NAME] = "${athlete.firstName ?: ""} ${athlete.lastName ?: ""}".trim()
            }
        }
    }

    suspend fun getAccessToken(): String? {
        return context.stravaDataStore.data.map { it[ACCESS_TOKEN] }.first()
    }

    suspend fun getRefreshToken(): String? {
        return context.stravaDataStore.data.map { it[REFRESH_TOKEN] }.first()
    }

    suspend fun isTokenExpired(): Boolean {
        val expiresAt = context.stravaDataStore.data.map { it[EXPIRES_AT] }.first() ?: return true
        // Refresh 5 minutes before expiry
        return Instant.now().epochSecond > (expiresAt - 300)
    }

    suspend fun isAuthenticated(): Boolean {
        return getAccessToken() != null
    }

    suspend fun getAthleteName(): String? {
        return context.stravaDataStore.data.map { it[ATHLETE_NAME] }.first()
    }

    suspend fun clearTokens() {
        context.stravaDataStore.edit { it.clear() }
    }
}
