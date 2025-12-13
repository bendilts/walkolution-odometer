/**
 * Odometer sensor processing
 */

#ifndef ODOMETER_H
#define ODOMETER_H

#include <stdint.h>
#include <stdbool.h>

// Callback function type for LED control
typedef void (*led_callback_t)(bool on);

// Initialize the odometer sensor
void odometer_init(uint8_t sensor_pin);

// Set LED callback for visual feedback
void odometer_set_led_callback(led_callback_t callback);

// Process sensor readings (simple edge detection, no debouncing)
// Returns true if a new rotation was detected
// Call at polling rate appropriate for your max speed (e.g., 30ms for 8mph)
bool odometer_process(void);

// Get the total number of rotations (all-time)
uint32_t odometer_get_count(void);

// Get the current session rotation count (may include merged sessions within 15 min)
uint32_t odometer_get_session_count(void);

// Get the total active time in seconds (all-time)
uint32_t odometer_get_active_time_seconds(void);

// Get the current session active time in seconds (may include merged sessions within 15 min)
uint32_t odometer_get_session_active_time_seconds(void);

// Save current count and active time to flash
void odometer_save_count(void);

// Enable voltage-based flash save (only save when voltage drops below threshold)
// threshold_mv: voltage in millivolts (e.g., 3300 for 3.3V)
void odometer_enable_voltage_save(uint16_t threshold_mv);

// Disable voltage-based flash save (always save before sleep)
void odometer_disable_voltage_save(void);

// Read current VSYS voltage in millivolts
uint16_t odometer_read_voltage(void);

// Debug: manually add a rotation (for testing without sensor)
void odometer_add_rotation(void);

// Session management structures and functions

// Session record structure for BLE transmission
typedef struct __attribute__((packed))
{
    uint32_t session_id;
    uint32_t rotation_count;
    uint32_t active_time_seconds;
    uint32_t start_time_unix; // 0 = unknown
    uint32_t end_time_unix;   // 0 = unknown
} session_record_t;

// Get list of unreported sessions
// Returns number of sessions found, fills sessions array (up to max_sessions)
uint32_t odometer_get_unreported_sessions(session_record_t *sessions, uint32_t max_sessions);

// Mark a specific session as reported
// Returns true if session was found and marked, false otherwise
bool odometer_mark_session_reported(uint32_t session_id);

// Set time reference from external source (BLE or NTP)
void odometer_set_time_reference(uint32_t unix_timestamp);

// Check if time has been acquired from external source
bool odometer_has_time(void);

// Get current Unix timestamp (0 if time not acquired)
uint32_t odometer_get_current_unix_time(void);

// Get current session ID (0 if not yet decided)
uint32_t odometer_get_current_session_id(void);

// Check if session ID has been decided
bool odometer_is_session_id_decided(void);

// Get boot-time rotation count (rotations since this boot only, excludes merged data)
uint32_t odometer_get_boot_rotation_count(void);

// Get boot-time active seconds (active time since this boot only, excludes merged data)
uint32_t odometer_get_boot_active_time_seconds(void);

// Reset the speed calculation window (called when boot rotations are reset)
void odometer_reset_speed_window(void);

// Set lifetime totals (for transferring progress to a new device)
// hours: total hours of active time
// distance_miles: total distance in miles
void odometer_set_lifetime_totals(float hours, float distance_miles);

#endif // ODOMETER_H
