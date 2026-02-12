/**
 * Odometer sensor processing implementation
 */

#include "odometer.h"
#include "flash.h"
#include "logging.h"
#include "irq.h"
#include "pico/stdlib.h"
#include "hardware/flash.h"
#include "hardware/adc.h"
#include <string.h>
#include <stdio.h>

#define SLEEP_TIMEOUT_MS 10000
#define ACTIVE_TIMEOUT_MS 3000       // Stop counting active time after 3 seconds of no rotations
#define FLASH_SAVE_INTERVAL_MS 60000 // Don't save more than once per minute
#define ROTATION_SAVE_INTERVAL 2500  // Save every 2500 rotations (~0.5 miles)
#define TIME_SYNC_TIMEOUT_MS 60000   // Wait up to 60 seconds for time sync before allowing saves

// Organized state structures
typedef struct
{
    uint32_t lifetime_rotations;      // All-time total rotations
    uint32_t lifetime_active_seconds; // All-time total active seconds
    uint32_t session_rotations;       // Current session rotations
    uint32_t session_active_seconds;  // Current session active time
    uint32_t last_rotation_time_ms;   // Time of last rotation
    uint32_t active_start_time_ms;    // When current active period started
    bool is_active;                   // Currently counting active time
} odometer_counts_t;

typedef struct
{
    uint32_t current_session_id;      // Current session ID
    uint32_t session_start_time_unix; // Unix timestamp when Pico booted (calculated)
    uint32_t time_reference_unix;     // Reference Unix timestamp from external source
    uint32_t time_reference_boot_ms;  // Boot time when we got the timestamp
    bool time_acquired;               // Whether we've gotten time from any source
} session_state_t;

typedef struct
{
    bool voltage_save_enabled;
    uint16_t voltage_threshold_mv;
    uint32_t last_saved_count;
    uint32_t last_save_time_ms;
} save_state_t;

// Global state instances
static odometer_counts_t counts = {0};
static session_state_t session = {0};
static save_state_t save_state = {0};

// Track the highest session ID and write_index seen (for creating new sessions/writes)
static uint32_t last_session_id = 0;
static uint32_t last_write_index = 0;

// Read VSYS voltage in millivolts
uint16_t odometer_read_voltage(void)
{
    static uint16_t last_valid_voltage = 0; // Cache last known good reading

    // Pico W: GPIO29 is shared with WiFi chip SPI CLK
    // Must disable WiFi chip (GP25 high) to read VSYS
    // CRITICAL: Must restore pins afterward or WiFi/BLE will break!

    // Disable WiFi chip by setting GP25 high
    gpio_init(25);
    gpio_set_dir(25, GPIO_OUT);
    gpio_put(25, 1);

    // Configure GP29 as ADC input
    gpio_init(29);
    gpio_set_dir(29, GPIO_IN);
    gpio_disable_pulls(29);

    // VSYS is measured via ADC channel 3 (GPIO29) through a voltage divider (3:1)
    adc_select_input(3);

    // Wait for ADC to settle (needs at least 550us according to Pico W forums)
    sleep_us(600);

    // Read ADC (12-bit: 0-4095)
    uint16_t adc_raw = adc_read();

    // Convert to voltage
    // ADC reads 0-3.3V, VSYS has 3:1 divider, so VSYS = ADC_voltage * 3
    // Voltage = (adc_raw / 4095) * 3.3V * 3
    // In millivolts: (adc_raw * 3.3 * 3 * 1000) / 4095
    uint32_t vsys_mv = (adc_raw * 9900UL) / 4095;

    // CRITICAL: Restore pins to allow WiFi/BLE to work
    // Set GP25 low to re-enable WiFi chip
    gpio_put(25, 0);
    gpio_set_pulls(25, false, true); // Pull down

    // Restore GP29 to ALT function 7 (WiFi chip SPI CLK)
    gpio_set_function(29, GPIO_FUNC_SIO);
    gpio_set_pulls(29, false, true); // Pull down
    // Note: The CYW43 driver will reconfigure this as needed

    // Filter out invalid readings (< 1500mV likely means ADC glitch)
    if (vsys_mv < 1500)
    {
        // Return last valid reading if we have one, otherwise return the bad reading
        if (last_valid_voltage > 0)
        {
            log_printf("WARNING: Invalid voltage reading %lu mV, using cached %u mV\n",
                       vsys_mv, last_valid_voltage);
            return last_valid_voltage;
        }
    }
    else
    {
        // Valid reading - cache it for future use
        last_valid_voltage = (uint16_t)vsys_mv;
    }

    return (uint16_t)vsys_mv;
}

static bool load_count_from_flash(void)
{
    // Use the flash module's scan function to get deduplicated sessions in one pass
    session_data_t sessions[FLASH_SECTOR_COUNT];
    uint32_t session_count = flash_scan_all_sessions(sessions, FLASH_SECTOR_COUNT);

    if (session_count == 0)
    {
        // No valid data found - start fresh
        last_session_id = 0;
        last_write_index = 0;
        log_printf("[FLASH] No valid previous session found - starting fresh\n");
        return false;
    }

    // Find the session with the highest session_id (most recent)
    uint32_t latest_index = 0;
    uint32_t max_session_id = sessions[0].session_id;
    uint32_t max_write_index = sessions[0].write_index;

    for (uint32_t i = 0; i < session_count; i++)
    {
        // Track global max write_index
        if (sessions[i].write_index > max_write_index)
        {
            max_write_index = sessions[i].write_index;
        }

        // Track the session with highest session_id
        if (sessions[i].session_id > max_session_id)
        {
            max_session_id = sessions[i].session_id;
            latest_index = i;
        }
    }

    session_data_t latest_data = sessions[latest_index];

    // Log the 10 most recent valid sessions (sorted by session_id, descending)
    log_printf("[FLASH] Found %lu valid session(s) in flash\n", session_count);
    log_printf("[FLASH] Logging up to 10 most recent sessions:\n");

    // Simple bubble sort to sort by session_id (descending)
    for (uint32_t i = 0; i < session_count - 1; i++)
    {
        for (uint32_t j = 0; j < session_count - i - 1; j++)
        {
            if (sessions[j].session_id < sessions[j + 1].session_id)
            {
                session_data_t temp = sessions[j];
                sessions[j] = sessions[j + 1];
                sessions[j + 1] = temp;
            }
        }
    }

    // Log up to 10 most recent
    uint32_t log_count = (session_count > 10) ? 10 : session_count;
    for (uint32_t i = 0; i < log_count; i++)
    {
        const session_data_t *d = &sessions[i];
        uint32_t sector = d->write_index % FLASH_SECTOR_COUNT;
        log_printf("[FLASH]   [%lu] Sector %lu: ID=%lu, WrIdx=%lu, Rotations=%lu/%lu, Time=%lu/%lu sec, Start=%lu, End=%lu, Reported=%s\n",
                   i + 1,
                   sector,
                   d->session_id,
                   d->write_index,
                   d->session_rotation_count,
                   d->lifetime_rotation_count,
                   d->session_active_time_seconds,
                   d->lifetime_time_seconds,
                   d->session_start_time_unix,
                   d->session_end_time_unix,
                   (d->reported != 0) ? "YES" : "NO");
    }

    // Load lifetime totals from the most recent session
    counts.lifetime_rotations = latest_data.lifetime_rotation_count;
    counts.lifetime_active_seconds = latest_data.lifetime_time_seconds;

    // Store the highest session ID and write_index we've seen
    last_session_id = latest_data.session_id;
    last_write_index = max_write_index;

    // Start fresh for this session
    counts.session_rotations = 0;
    counts.session_active_seconds = 0;

    log_printf("[FLASH] Loaded lifetime totals from flash:\n");
    log_printf("  - Last session ID: %lu\n", last_session_id);
    log_printf("  - Last write index: %lu\n", last_write_index);
    log_printf("  - Lifetime totals: %lu rotations, %lu sec\n", counts.lifetime_rotations, counts.lifetime_active_seconds);

    return true;
}

// Get current Unix timestamp based on time reference
uint32_t odometer_get_current_unix_time(void)
{
    if (!session.time_acquired || session.time_reference_unix == 0)
    {
        return 0; // No time available
    }

    uint32_t current_boot_ms = to_ms_since_boot(get_absolute_time());
    uint32_t elapsed_seconds = (current_boot_ms - session.time_reference_boot_ms) / 1000;
    return session.time_reference_unix + elapsed_seconds;
}

void odometer_save_count(void)
{
    uint32_t current_time_unix = odometer_get_current_unix_time();

    // Calculate current session end time
    uint32_t session_end_time = current_time_unix;

    // If we have an end time but no start time, estimate start time from active seconds
    if (session_end_time != 0 && session.session_start_time_unix == 0)
    {
        uint32_t active_seconds = odometer_get_session_active_time_seconds();
        session.session_start_time_unix = session_end_time - active_seconds;
        log_printf("[SESSION] Estimated start time from end time - active seconds: %lu - %lu = %lu\n",
                   session_end_time, active_seconds, session.session_start_time_unix);
    }

    // Increment write_index for this save (globally incrementing, never resets)
    last_write_index++;

    // Prepare session data structure (no internal flash fields needed)
    session_data_t data;
    memset(&data, 0, sizeof(session_data_t));

    data.session_id = session.current_session_id;
    data.write_index = last_write_index; // Globally incrementing write counter
    data.session_rotation_count = counts.session_rotations;
    data.session_active_time_seconds = odometer_get_session_active_time_seconds();
    data.session_start_time_unix = session.session_start_time_unix;
    data.session_end_time_unix = session_end_time;
    data.lifetime_rotation_count = counts.lifetime_rotations;
    data.lifetime_time_seconds = odometer_get_active_time_seconds();
    data.reported = 0; // New/updated sessions are not reported

    // Write to flash (sector determined by write_index)
    if (!flash_write(&data, "Writing session to flash"))
    {
        log_printf("[FLASH WRITE] ERROR: Flash write verification failed!\n");
        log_printf("[FLASH WRITE] Data integrity cannot be guaranteed. System may need attention.\n");
        // Continue anyway - we've already written the data, and there's not much we can do at this point
        // except log the error for debugging. The checksum will prevent this data from being loaded.
    }

    // Update save state
    save_state.last_saved_count = counts.lifetime_rotations;
    save_state.last_save_time_ms = to_ms_since_boot(get_absolute_time());

    log_printf("[FLASH WRITE] ✓ Flash write completed successfully\n");
}

void odometer_init(void)
{
    // Note: Sensor IRQ initialization happens in main application via irq_init()

    // Initialize ADC for voltage monitoring
    adc_init();
    adc_gpio_init(29); // GPIO29 is ADC3, connected to VSYS via divider

    // Try to load count from flash, otherwise start at 0
    if (!load_count_from_flash())
    {
        counts.lifetime_rotations = 0;
        counts.lifetime_active_seconds = 0;
    }

    // Initialize save state to current lifetime count
    save_state.last_saved_count = counts.lifetime_rotations;

    // Start fresh session (always create new session on startup)
    counts.session_rotations = 0;
    counts.session_active_seconds = 0;
    session.current_session_id = last_session_id + 1;

    log_printf("[SESSION] Starting new session ID: %lu\n", session.current_session_id);
}

bool odometer_process(void)
{
    bool rotation_detected = false;
    uint32_t current_time_ms = to_ms_since_boot(get_absolute_time());

    // Read and clear pending rotations from IRQ module
    uint32_t rotations_to_process = irq_read_and_clear_rotations();

    // Process each pending rotation
    for (uint32_t i = 0; i < rotations_to_process; i++)
    {
        odometer_add_rotation();
        rotation_detected = true;
    }

    // Update active time tracking
    // If currently active, check for timeout (3 seconds since last rotation)
    if (counts.is_active)
    {
        if ((current_time_ms - counts.last_rotation_time_ms) >= ACTIVE_TIMEOUT_MS)
        {
            // Timeout reached - finalize this active period
            uint32_t elapsed_seconds = (counts.last_rotation_time_ms - counts.active_start_time_ms) / 1000;
            counts.lifetime_active_seconds += elapsed_seconds;
            counts.session_active_seconds += elapsed_seconds;
            counts.is_active = false;
        }
    }

    return rotation_detected;
}

uint32_t odometer_get_count(void)
{
    return counts.lifetime_rotations;
}

uint32_t odometer_get_session_count(void)
{
    return counts.session_rotations;
}

uint32_t odometer_get_active_time_seconds(void)
{
    uint32_t total = counts.lifetime_active_seconds;

    // If currently active, add the time elapsed in the current active period
    if (counts.is_active)
    {
        uint32_t current_time_ms = to_ms_since_boot(get_absolute_time());
        uint32_t current_period_seconds = (current_time_ms - counts.active_start_time_ms) / 1000;
        total += current_period_seconds;
    }

    return total;
}

uint32_t odometer_get_session_active_time_seconds(void)
{
    uint32_t total = counts.session_active_seconds;

    // If currently active, add the time elapsed in the current active period
    if (counts.is_active)
    {
        uint32_t current_time_ms = to_ms_since_boot(get_absolute_time());
        uint32_t current_period_seconds = (current_time_ms - counts.active_start_time_ms) / 1000;
        total += current_period_seconds;
    }

    return total;
}

void odometer_enable_voltage_save(uint16_t threshold_mv)
{
    save_state.voltage_save_enabled = true;
    save_state.voltage_threshold_mv = threshold_mv;
}

void odometer_disable_voltage_save(void)
{
    save_state.voltage_save_enabled = false;
}

void odometer_add_rotation(void)
{
    uint32_t current_time_ms = to_ms_since_boot(get_absolute_time());

    // Increment rotation counters
    counts.lifetime_rotations++;
    counts.session_rotations++;

    // Update active time tracking
    counts.last_rotation_time_ms = current_time_ms;
    if (!counts.is_active)
    {
        // Starting a new active period
        counts.active_start_time_ms = current_time_ms;
        counts.is_active = true;
    }

    // Check if we should save to flash based on rotation count
    if ((counts.lifetime_rotations - save_state.last_saved_count) >= ROTATION_SAVE_INTERVAL)
    {
        odometer_save_count();
    }

    // Check voltage if voltage-based saving is enabled
    // Save immediately if voltage drops below threshold (power loss imminent)
    if (save_state.voltage_save_enabled)
    {
        uint16_t vsys_mv = odometer_read_voltage();
        if (vsys_mv <= save_state.voltage_threshold_mv)
        {
            // Voltage is low - save immediately before potential power loss
            // (only if count has changed and at least 1 minute since last save)
            if (counts.lifetime_rotations != save_state.last_saved_count &&
                (current_time_ms - save_state.last_save_time_ms) >= FLASH_SAVE_INTERVAL_MS)
            {
                odometer_save_count();
            }
        }
    }
}

// Get list of unreported sessions from flash
uint32_t odometer_get_unreported_sessions(session_record_t *sessions, uint32_t max_sessions)
{
    // Exclude the current active session
    uint32_t exclude_session_id = session.current_session_id;
    log_printf("[SESSION] Excluding session %lu from unreported list (current session)\n", exclude_session_id);

    // Use flash module's scan function to get deduplicated sessions in one pass
    session_data_t all_sessions[FLASH_SECTOR_COUNT];
    uint32_t all_count = flash_scan_all_sessions(all_sessions, FLASH_SECTOR_COUNT);

    // Filter for unreported sessions (excluding current session)
    uint32_t count = 0;
    for (uint32_t i = 0; i < all_count && count < max_sessions; i++)
    {
        const session_data_t *data = &all_sessions[i];

        // Only include unreported sessions that aren't the current one
        if (data->reported == 0 && data->session_id != exclude_session_id)
        {
            sessions[count].session_id = data->session_id;
            sessions[count].rotation_count = data->session_rotation_count;
            sessions[count].active_time_seconds = data->session_active_time_seconds;
            sessions[count].start_time_unix = data->session_start_time_unix;
            sessions[count].end_time_unix = data->session_end_time_unix;
            count++;
        }
    }

    return count;
}

// Mark a specific session as reported
bool odometer_mark_session_reported(uint32_t session_id)
{
    // If marking the current session, save it first, then start a new one
    if (session_id == session.current_session_id)
    {
        log_printf("[SESSION] Marking current session %lu as reported\n", session_id);
        odometer_save_count(); // Persist current state before marking
    }

    // Find and update the session in flash
    session_data_t data;
    if (!flash_find_session(session_id, &data))
    {
        log_printf("[FLASH] ERROR: Session %lu not found in flash when trying to mark as reported\n", session_id);
        return false; // Session not found
    }

    data.reported = 1;
    if (!flash_write(&data, session_id == session.current_session_id ? "Marking session as REPORTED" : "Marking OLD session as REPORTED"))
    {
        log_printf("[FLASH WRITE] ERROR: Flash write verification failed!\n");
        log_printf("[FLASH WRITE] Session %lu may not be properly marked as reported.\n", session_id);
    }

    log_printf("[FLASH WRITE] ✓ %s session %lu marked as reported in flash\n",
               session_id == session.current_session_id ? "Current" : "Old", session_id);

    // If this was the current session, start a new one
    if (session_id == session.current_session_id)
    {
        session.current_session_id = session_id + 1;
        counts.session_rotations = 0;
        counts.session_active_seconds = 0;
        odometer_reset_speed_window();

        if (session.time_acquired)
        {
            session.session_start_time_unix = odometer_get_current_unix_time();
        }

        log_printf("  - Starting fresh session with zero counts\n");
        log_printf("  - New session ID: %lu (rotations: %lu)\n",
                   session.current_session_id, counts.session_rotations);
    }

    return true;
}

// Set time reference from external source (BLE or NTP)
void odometer_set_time_reference(uint32_t unix_timestamp)
{
    if (unix_timestamp > 0)
    {
        uint32_t current_boot_ms = to_ms_since_boot(get_absolute_time());

        session.time_reference_unix = unix_timestamp;
        session.time_reference_boot_ms = current_boot_ms;
        session.time_acquired = true;

        // Calculate when the Pico started by subtracting uptime from current time
        uint32_t uptime_seconds = current_boot_ms / 1000;
        session.session_start_time_unix = unix_timestamp - uptime_seconds;

        log_printf("[TIME] Time reference set!\n");
        log_printf("  - Current Unix time: %lu\n", unix_timestamp);
        log_printf("  - Uptime: %lu ms (%.1f sec)\n", current_boot_ms, current_boot_ms / 1000.0f);
        log_printf("  - Calculated session start: %lu\n", session.session_start_time_unix);

        // Print human-readable date (approximate - just for debugging)
        uint32_t days_since_epoch = unix_timestamp / 86400;
        uint32_t years = days_since_epoch / 365 + 1970;
        log_printf("  - Approximate date: year ~%lu\n", years);
    }
    else
    {
        log_printf("[TIME] Warning: Received invalid timestamp (0)\n");
    }
}

// Check if time has been acquired from external source
bool odometer_has_time(void)
{
    return session.time_acquired;
}

// Get current session ID (0 if not yet decided)
uint32_t odometer_get_current_session_id(void)
{
    return session.current_session_id;
}

// Set lifetime totals (for transferring progress to a new device)
void odometer_set_lifetime_totals(float hours, float distance_miles)
{
    // Convert distance from miles to rotations
    // Each rotation = 34.56 cm = 0.3456 meters = 0.0002147 miles
    const float MILES_PER_ROTATION = 0.0002147f;
    uint32_t rotations = (uint32_t)(distance_miles / MILES_PER_ROTATION);

    // Convert hours to seconds
    uint32_t seconds = (uint32_t)(hours * 3600.0f);

    log_printf("[ODOMETER] Setting lifetime totals:\n");
    log_printf("  - Hours: %.2f -> %lu seconds\n", hours, seconds);
    log_printf("  - Distance: %.2f miles -> %lu rotations\n", distance_miles, rotations);
    log_printf("  - Previous lifetime: %lu rotations, %lu seconds\n",
               counts.lifetime_rotations, counts.lifetime_active_seconds);

    // Update the lifetime totals
    counts.lifetime_rotations = rotations;
    counts.lifetime_active_seconds = seconds;

    log_printf("  - New lifetime: %lu rotations, %lu seconds\n",
               counts.lifetime_rotations, counts.lifetime_active_seconds);

    // Save to flash immediately
    log_printf("  - Saving to flash...\n");
    odometer_save_count();
    log_printf("  - Lifetime totals saved successfully\n");
}

// WiFi/NTP functions - to be implemented
// Note: This is a stub. Full implementation requires:
// 1. Initialize WiFi with cyw43_arch_init()
// 2. Connect to WiFi using cyw43_arch_wifi_connect_blocking()
// 3. Initialize SNTP with sntp_init()
// 4. Wait for SNTP sync
// 5. Get time with sntp_get_time()
// 6. Disconnect WiFi and disable
//
// For now, this functionality is not implemented and timestamps will be 0.
// The system will work fine without timestamps - sessions will just be merged
// more aggressively until NTP is implemented.
//
// Example implementation can be found in pico-examples/pico_w/wifi/ntp_client/
