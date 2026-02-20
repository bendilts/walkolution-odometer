package com.mypeople.walkolutionodometer

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.userPreferencesDataStore: DataStore<Preferences> by preferencesDataStore(name = "user_preferences")

/**
 * Manages local user preferences that don't require syncing with the Pico device.
 * These settings are stored only on the phone/watch.
 */
class UserPreferencesManager(private val context: Context) {

    companion object {
        private val DAILY_GOAL_MILES = floatPreferencesKey("daily_goal_miles")
        private const val DEFAULT_DAILY_GOAL_MILES = 6.0f
    }

    /**
     * Flow that emits the current daily goal distance in miles.
     */
    val dailyGoalMiles: Flow<Float> = context.userPreferencesDataStore.data.map { prefs ->
        prefs[DAILY_GOAL_MILES] ?: DEFAULT_DAILY_GOAL_MILES
    }

    /**
     * Save the daily goal distance in miles.
     */
    suspend fun setDailyGoalMiles(miles: Float) {
        context.userPreferencesDataStore.edit { prefs ->
            prefs[DAILY_GOAL_MILES] = miles
        }
    }

    /**
     * Get the current daily goal distance in miles (suspending).
     */
    suspend fun getDailyGoalMiles(): Float {
        return context.userPreferencesDataStore.data.map { prefs ->
            prefs[DAILY_GOAL_MILES] ?: DEFAULT_DAILY_GOAL_MILES
        }.first()
    }
}
