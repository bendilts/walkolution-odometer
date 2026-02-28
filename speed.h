/**
 * Speed tracking and calculation module
 *
 * Maintains a rolling 5-second window of rotation data to calculate:
 * - Running average speed (5-second window)
 * - Session average speed
 * - Slow walking detection for OLED power management
 */

#ifndef SPEED_H
#define SPEED_H

#include <stdint.h>
#include <stdbool.h>

// Initialize the speed tracking module
void speed_init(void);

// Update speed window with current session rotation count
// Call every second to maintain accurate rolling average
// session_rotations: current session rotation count
// current_time_ms: current system time in milliseconds
void speed_update(uint32_t session_rotations, uint32_t current_time_ms);

// Reset speed tracking (called when starting a new session)
void speed_reset(void);

// Get current 5-second running average speed in mph or km/h
// Returns 0.0 if insufficient data (need at least 2 data points)
// metric: true = km/h, false = mph
float speed_get_running_avg(bool metric);

// Get session average speed in mph or km/h
// session_rotations: total rotations in current session
// session_time_seconds: total active time in current session
// metric: true = km/h, false = mph
float speed_get_session_avg(uint32_t session_rotations, uint32_t session_time_seconds, bool metric);

// Check if OLED should be allowed based on speed state
// Returns false only if speed has been continuously in slow walking range (0 < speed < 1.5 mph) for 5+ seconds
// This prevents the OLED from turning off when a user stops walking (speed drops through slow range to 0)
// Note: The 1.5 mph threshold is fixed regardless of metric setting
// Note: This is a read-only function - state updates happen in speed_update()
// current_time_ms: current system time in milliseconds
bool speed_allows_oled_display(uint32_t current_time_ms);

// Check if BLE should be activated based on walking speed
// Returns false on startup, then true forever after walking faster than
// the slow walking threshold (1.5 mph) for 15 consecutive seconds
// Note: This is a read-only function - state updates happen in speed_update()
bool speed_allows_ble(void);

#endif // SPEED_H
