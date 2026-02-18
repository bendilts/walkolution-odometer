/**
 * Speed tracking and calculation implementation
 */

#include "speed.h"
#include "logging.h"
#include <string.h>

// Constants
#define SPEED_WINDOW_SECONDS 5             // 5-second running average for speed
#define SLOW_WALKING_DETECTION_TIME_MS 5000 // Time speed must stay in slow walking range before OLED turns off
#define SLOW_WALK_THRESHOLD_MPH 1.5f       // Turn off OLED when speed is >0 but <1.5 mph (always mph, regardless of metric setting)

// Conversion constants
// Each rotation = 34.56 cm = 0.3456 meters = 0.0002147 miles = 0.0003456 km
#define CM_PER_ROTATION 34.56f
#define METERS_PER_MILE 1609.344f
#define METERS_PER_KM 1000.0f
#define MILES_PER_ROTATION (CM_PER_ROTATION / 100.0f / METERS_PER_MILE)
#define KM_PER_ROTATION (CM_PER_ROTATION / 100.0f / METERS_PER_KM)

// Speed window structure (circular buffer for 5-second rolling average)
typedef struct
{
    uint32_t rotations[SPEED_WINDOW_SECONDS];
    uint32_t timestamps_ms[SPEED_WINDOW_SECONDS];
    int index;
    bool filled;
} speed_window_t;

// Module state
static speed_window_t speed_window = {0};

// Slow walking range tracking for OLED power management
// Track how long speed has been continuously in slow walking range (0 < speed < threshold)
static uint32_t slow_walking_range_start_ms = 0; // When speed first entered slow walking range
static bool speed_was_in_slow_walking_range = false; // Previous state

void speed_init(void)
{
    memset(&speed_window, 0, sizeof(speed_window));
    slow_walking_range_start_ms = 0;
    speed_was_in_slow_walking_range = false;
}

void speed_reset(void)
{
    memset(&speed_window, 0, sizeof(speed_window));
    slow_walking_range_start_ms = 0;
    speed_was_in_slow_walking_range = false;
    log_printf("[SPEED] Speed window reset (starting new session)\n");
}

void speed_update(uint32_t session_rotations, uint32_t current_time_ms)
{
    // Update speed window
    speed_window.rotations[speed_window.index] = session_rotations;
    speed_window.timestamps_ms[speed_window.index] = current_time_ms;

    speed_window.index = (speed_window.index + 1) % SPEED_WINDOW_SECONDS;
    if (speed_window.index == 0)
    {
        speed_window.filled = true;
    }

    // Update slow walking range tracking
    // Get current speed in mph for threshold comparison
    float current_speed_mph = speed_get_running_avg(false); // false = mph

    // Check if speed is in slow walking range (0 < speed < 1.5 mph)
    bool speed_in_slow_walking_range = (current_speed_mph > 0.0f) && (current_speed_mph < SLOW_WALK_THRESHOLD_MPH);

    if (speed_in_slow_walking_range)
    {
        // Currently in slow walking range
        if (!speed_was_in_slow_walking_range)
        {
            // Just entered slow walking range - start timer
            slow_walking_range_start_ms = current_time_ms;
            speed_was_in_slow_walking_range = true;
            log_printf("[SPEED] Entered slow walking range (speed %.2f mph), starting 5-second timer\n",
                       current_speed_mph);
        }
        // else: already in slow walking range, timer already running
    }
    else
    {
        // Not in slow walking range (either stopped or walking fast)
        if (speed_was_in_slow_walking_range)
        {
            // Just exited slow walking range - reset timer
            log_printf("[SPEED] Exited slow walking range (speed %.2f mph)\n", current_speed_mph);
            speed_was_in_slow_walking_range = false;
            slow_walking_range_start_ms = 0;
        }
    }
}

float speed_get_running_avg(bool metric)
{
    if (!speed_window.filled && speed_window.index < 2)
    {
        return 0.0f; // Need at least 2 data points
    }

    int oldest_index = speed_window.filled ? speed_window.index : 0;
    int newest_index = speed_window.filled ? ((speed_window.index - 1 + SPEED_WINDOW_SECONDS) % SPEED_WINDOW_SECONDS) : (speed_window.index - 1);

    uint32_t rotation_diff = speed_window.rotations[newest_index] - speed_window.rotations[oldest_index];
    uint32_t time_diff_ms = speed_window.timestamps_ms[newest_index] - speed_window.timestamps_ms[oldest_index];

    if (time_diff_ms == 0)
    {
        return 0.0f;
    }

    // Convert to rotations per hour
    float rotations_per_ms = (float)rotation_diff / (float)time_diff_ms;
    float rotations_per_hour = rotations_per_ms * 3600000.0f;

    // Convert to mph or km/h based on metric setting
    return rotations_per_hour * (metric ? KM_PER_ROTATION : MILES_PER_ROTATION);
}

float speed_get_session_avg(uint32_t session_rotations, uint32_t session_time_seconds, bool metric)
{
    if (session_time_seconds == 0)
    {
        return 0.0f;
    }

    // Convert to rotations per hour
    float rotations_per_second = (float)session_rotations / (float)session_time_seconds;
    float rotations_per_hour = rotations_per_second * 3600.0f;

    // Convert to mph or km/h based on metric setting
    return rotations_per_hour * (metric ? KM_PER_ROTATION : MILES_PER_ROTATION);
}

bool speed_allows_oled_display(uint32_t current_time_ms)
{
    // Read-only function: just check current state, don't modify anything
    // (State updates happen in speed_update())

    // If not currently in slow walking range, OLED is allowed
    if (!speed_was_in_slow_walking_range)
    {
        return true;
    }

    // Currently in slow walking range - check how long we've been there
    uint32_t time_in_slow_walking_range_ms = current_time_ms - slow_walking_range_start_ms;

    if (time_in_slow_walking_range_ms >= SLOW_WALKING_DETECTION_TIME_MS)
    {
        // Been in slow walking range for 5+ seconds - OLED should be off
        return false;
    }

    // In slow walking range but less than 5 seconds - OLED still allowed
    return true;
}
