package com.mypeople.walkolutionodometer

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import com.squareup.moshi.Moshi
import com.squareup.moshi.JsonClass
import com.squareup.moshi.Types
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory

private val Context.sessionCacheDataStore: DataStore<Preferences> by preferencesDataStore(name = "session_cache")

// Represents a session that was uploaded to Strava but not yet confirmed to Pico
@JsonClass(generateAdapter = true)
data class UploadedSession(
    val sessionId: Int,
    val stravaActivityId: Long,
    val uploadTimestamp: Long,
    val distanceMeters: Float,
    val elapsedTimeSeconds: Int
)

// Cached session data for offline access
@JsonClass(generateAdapter = true)
data class CachedSessionRecord(
    val sessionId: Int,
    val rotationCount: Int,
    val activeTimeSeconds: Int,
    val startTimeUnix: Long,
    val endTimeUnix: Long
)

// Current session snapshot for offline upload
@JsonClass(generateAdapter = true)
data class CurrentSessionSnapshot(
    val sessionId: Int,
    val rotationCount: Int,
    val activeTimeSeconds: Int,
    val captureTimestamp: Long  // When this snapshot was taken (phone time)
)

// Represents a session that was discarded but not yet confirmed to Pico
@JsonClass(generateAdapter = true)
data class DiscardedSession(
    val sessionId: Int,
    val discardTimestamp: Long
)

class SessionCacheManager(private val context: Context) {

    companion object {
        private val UPLOADED_SESSIONS = stringPreferencesKey("uploaded_sessions")
        private val CACHED_SESSIONS = stringPreferencesKey("cached_sessions")
        private val CURRENT_SESSION_SNAPSHOT = stringPreferencesKey("current_session_snapshot")
        private val DISCARDED_SESSIONS = stringPreferencesKey("discarded_sessions")
    }

    private val moshi = Moshi.Builder()
        .add(KotlinJsonAdapterFactory())
        .build()

    private val uploadedSessionsAdapter = moshi.adapter<List<UploadedSession>>(
        Types.newParameterizedType(List::class.java, UploadedSession::class.java)
    )

    private val cachedSessionsAdapter = moshi.adapter<List<CachedSessionRecord>>(
        Types.newParameterizedType(List::class.java, CachedSessionRecord::class.java)
    )

    private val currentSessionAdapter = moshi.adapter(CurrentSessionSnapshot::class.java)

    private val discardedSessionsAdapter = moshi.adapter<List<DiscardedSession>>(
        Types.newParameterizedType(List::class.java, DiscardedSession::class.java)
    )

    // --- Uploaded Sessions (sent to Strava, awaiting Pico confirmation) ---

    suspend fun addUploadedSession(session: UploadedSession) {
        val current = getUploadedSessions().toMutableList()
        // Remove any existing entry for this session ID (in case of re-upload)
        current.removeAll { it.sessionId == session.sessionId }
        current.add(session)
        saveUploadedSessions(current)
    }

    suspend fun getUploadedSessions(): List<UploadedSession> {
        val json = context.sessionCacheDataStore.data.map { it[UPLOADED_SESSIONS] }.first()
        return if (json != null) {
            try {
                uploadedSessionsAdapter.fromJson(json) ?: emptyList()
            } catch (e: Exception) {
                emptyList()
            }
        } else {
            emptyList()
        }
    }

    suspend fun removeUploadedSession(sessionId: Int) {
        val current = getUploadedSessions().toMutableList()
        current.removeAll { it.sessionId == sessionId }
        saveUploadedSessions(current)
    }

    suspend fun isSessionUploaded(sessionId: Int): Boolean {
        return getUploadedSessions().any { it.sessionId == sessionId }
    }

    private suspend fun saveUploadedSessions(sessions: List<UploadedSession>) {
        context.sessionCacheDataStore.edit { prefs ->
            prefs[UPLOADED_SESSIONS] = uploadedSessionsAdapter.toJson(sessions)
        }
    }

    // --- Cached Sessions (from last BLE read, for offline access) ---

    suspend fun cacheSessions(sessions: List<SessionRecord>) {
        val cached = sessions.map { session ->
            CachedSessionRecord(
                sessionId = session.sessionId,
                rotationCount = session.rotationCount,
                activeTimeSeconds = session.activeTimeSeconds,
                startTimeUnix = session.startTimeUnix,
                endTimeUnix = session.endTimeUnix
            )
        }
        context.sessionCacheDataStore.edit { prefs ->
            prefs[CACHED_SESSIONS] = cachedSessionsAdapter.toJson(cached)
        }
    }

    suspend fun getCachedSessions(): List<SessionRecord> {
        val json = context.sessionCacheDataStore.data.map { it[CACHED_SESSIONS] }.first()
        return if (json != null) {
            try {
                val cached = cachedSessionsAdapter.fromJson(json) ?: emptyList()
                cached.map { c ->
                    SessionRecord(
                        sessionId = c.sessionId,
                        rotationCount = c.rotationCount,
                        activeTimeSeconds = c.activeTimeSeconds,
                        startTimeUnix = c.startTimeUnix,
                        endTimeUnix = c.endTimeUnix
                    )
                }
            } catch (e: Exception) {
                emptyList()
            }
        } else {
            emptyList()
        }
    }

    // --- Current Session Snapshot (for offline upload of current session) ---

    suspend fun saveCurrentSessionSnapshot(sessionId: Int, rotationCount: Int, activeTimeSeconds: Int) {
        val snapshot = CurrentSessionSnapshot(
            sessionId = sessionId,
            rotationCount = rotationCount,
            activeTimeSeconds = activeTimeSeconds,
            captureTimestamp = System.currentTimeMillis() / 1000
        )
        context.sessionCacheDataStore.edit { prefs ->
            prefs[CURRENT_SESSION_SNAPSHOT] = currentSessionAdapter.toJson(snapshot)
        }
    }

    suspend fun getCurrentSessionSnapshot(): CurrentSessionSnapshot? {
        val json = context.sessionCacheDataStore.data.map { it[CURRENT_SESSION_SNAPSHOT] }.first()
        return if (json != null) {
            try {
                currentSessionAdapter.fromJson(json)
            } catch (e: Exception) {
                null
            }
        } else {
            null
        }
    }

    suspend fun clearCurrentSessionSnapshot() {
        context.sessionCacheDataStore.edit { prefs ->
            prefs.remove(CURRENT_SESSION_SNAPSHOT)
        }
    }

    // --- Discarded Sessions (discarded by user, awaiting Pico confirmation) ---

    suspend fun addDiscardedSession(sessionId: Int) {
        val current = getDiscardedSessions().toMutableList()
        // Remove any existing entry for this session ID
        current.removeAll { it.sessionId == sessionId }
        current.add(DiscardedSession(
            sessionId = sessionId,
            discardTimestamp = System.currentTimeMillis() / 1000
        ))
        saveDiscardedSessions(current)
    }

    suspend fun getDiscardedSessions(): List<DiscardedSession> {
        val json = context.sessionCacheDataStore.data.map { it[DISCARDED_SESSIONS] }.first()
        return if (json != null) {
            try {
                discardedSessionsAdapter.fromJson(json) ?: emptyList()
            } catch (e: Exception) {
                emptyList()
            }
        } else {
            emptyList()
        }
    }

    suspend fun removeDiscardedSession(sessionId: Int) {
        val current = getDiscardedSessions().toMutableList()
        current.removeAll { it.sessionId == sessionId }
        saveDiscardedSessions(current)
    }

    suspend fun isSessionDiscarded(sessionId: Int): Boolean {
        return getDiscardedSessions().any { it.sessionId == sessionId }
    }

    private suspend fun saveDiscardedSessions(sessions: List<DiscardedSession>) {
        context.sessionCacheDataStore.edit { prefs ->
            prefs[DISCARDED_SESSIONS] = discardedSessionsAdapter.toJson(sessions)
        }
    }

    // --- Utility ---

    suspend fun clearAll() {
        context.sessionCacheDataStore.edit { it.clear() }
    }
}
