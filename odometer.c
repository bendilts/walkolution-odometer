/**
 * Odometer sensor processing implementation
 */

#include "odometer.h"
#include "pico/stdlib.h"
#include "hardware/gpio.h"
#include "hardware/flash.h"
#include "hardware/sync.h"
#include "hardware/adc.h"
#include <string.h>
#include <stdio.h>

#define SLEEP_TIMEOUT_MS 10000
#define ACTIVE_TIMEOUT_MS 3000       // Stop counting active time after 3 seconds of no rotations
#define FLASH_SAVE_INTERVAL_MS 60000 // Don't save more than once per minute
#define ROTATION_SAVE_INTERVAL 2500  // Save every 2500 rotations (~0.5 miles)
#define TIME_SYNC_TIMEOUT_MS 60000   // Wait up to 60 seconds for time sync before allowing saves

// Flash storage configuration - wear leveling with 64 sectors
#define FLASH_SECTOR_COUNT 64
#define FLASH_START_OFFSET (PICO_FLASH_SIZE_BYTES - (FLASH_SECTOR_SIZE * FLASH_SECTOR_COUNT))
#define FLASH_MAGIC_NUMBER 0x4F444F53 // "ODOS" in hex (Odometer Session)

// Flash storage structure - session-based
typedef struct
{
    uint32_t magic;                       // 0x4F444F53 ("ODOS")
    uint32_t struct_version;              // Version 1
    uint32_t session_id;                  // Monotonically increasing session counter
    uint32_t session_rotation_count;      // Rotations in this session
    uint32_t session_active_time_seconds; // Active time in this session (seconds)
    uint32_t session_start_time_unix;     // Unix timestamp when session started (0 = unknown)
    uint32_t session_end_time_unix;       // Unix timestamp when session ended (0 = unknown)
    uint32_t lifetime_rotation_count;     // All-time total rotations
    uint32_t lifetime_time_seconds;       // All-time total active seconds
    uint8_t reported;                     // 0 = not reported to fitness app, 1 = reported
    uint32_t checksum;                    // XOR checksum of all fields above
} flash_data_t;

// Organized state structures
typedef struct
{
    uint32_t lifetime_rotations;           // All-time total rotations
    uint32_t lifetime_active_seconds;      // All-time total active seconds
    uint32_t session_rotations;            // Current session rotations (may include merged data)
    uint32_t session_active_seconds;       // Current session active time (finalized, may include merged)
    uint32_t boot_rotations;               // Rotations since THIS boot only (for merge reversal)
    uint32_t boot_active_seconds;          // Active seconds since THIS boot only (for merge reversal)
    uint32_t last_rotation_time_ms;        // Time of last rotation
    uint32_t active_start_time_ms;         // When current active period started
    bool is_active;                        // Currently counting active time
} odometer_counts_t;

typedef struct
{
    uint32_t current_session_id;           // Current session ID (0 until decided)
    uint32_t session_start_time_unix;      // Unix timestamp when Pico booted (calculated)
    uint32_t time_reference_unix;          // Reference Unix timestamp from external source
    uint32_t time_reference_boot_ms;       // Boot time when we got the timestamp
    bool time_acquired;                    // Whether we've gotten time from any source
    bool session_id_decided;               // Have we decided our session ID yet?
} session_state_t;

typedef struct
{
    uint32_t session_id;
    uint32_t rotation_count;
    uint32_t active_time_seconds;
    uint32_t start_time_unix;
    uint32_t end_time_unix;
    bool reported;
    bool valid;                            // Was data loaded successfully?
} previous_session_t;

typedef struct
{
    uint8_t sensor_pin;
    bool last_sensor_state;
} sensor_state_t;

typedef struct
{
    led_callback_t led_callback;
} callbacks_t;

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
static previous_session_t prev_session = {0};
static sensor_state_t sensor = {0};
static callbacks_t callbacks = {0};
static save_state_t save_state = {0};

static uint32_t calculate_checksum(const flash_data_t *data)
{
    // XOR of all fields except checksum
    return data->magic ^
           data->struct_version ^
           data->session_id ^
           data->session_rotation_count ^
           data->session_active_time_seconds ^
           data->session_start_time_unix ^
           data->session_end_time_unix ^
           data->lifetime_rotation_count ^
           data->lifetime_time_seconds ^
           data->reported;
}

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
            printf("WARNING: Invalid voltage reading %lu mV, using cached %u mV\n",
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
    uint32_t max_session_id = 0;
    bool found_valid = false;
    const flash_data_t *latest_data = NULL;

    // Scan all 64 sectors to find the one with the highest valid session_id
    for (uint32_t sector = 0; sector < FLASH_SECTOR_COUNT; sector++)
    {
        uint32_t sector_offset = FLASH_START_OFFSET + (sector * FLASH_SECTOR_SIZE);
        const flash_data_t *flash_data = (const flash_data_t *)(XIP_BASE + sector_offset);

        // Verify magic number and checksum
        if (flash_data->magic == FLASH_MAGIC_NUMBER &&
            flash_data->checksum == calculate_checksum(flash_data))
        {
            // Check if this is the newest valid entry (highest session_id)
            if (!found_valid || flash_data->session_id > max_session_id)
            {
                max_session_id = flash_data->session_id;
                latest_data = flash_data;
                found_valid = true;
            }
        }
    }

    if (found_valid)
    {
        // Load lifetime totals from the most recent session
        counts.lifetime_rotations = latest_data->lifetime_rotation_count;
        counts.lifetime_active_seconds = latest_data->lifetime_time_seconds;

        // Store previous session data for later decision (at first save)
        prev_session.session_id = latest_data->session_id;
        prev_session.rotation_count = latest_data->session_rotation_count;
        prev_session.active_time_seconds = latest_data->session_active_time_seconds;
        prev_session.start_time_unix = latest_data->session_start_time_unix;
        prev_session.end_time_unix = latest_data->session_end_time_unix;
        prev_session.reported = (latest_data->reported != 0);
        prev_session.valid = true;

        // Start fresh for this session - will decide merge/new at first save
        counts.session_rotations = 0;
        counts.session_active_seconds = 0;
        session.session_id_decided = false;

        printf("[FLASH] Loaded previous session from flash:\n");
        printf("  - Session ID: %lu\n", prev_session.session_id);
        printf("  - Rotations: %lu, Active time: %lu sec\n", prev_session.rotation_count, prev_session.active_time_seconds);
        printf("  - Start time: %lu, End time: %lu\n", prev_session.start_time_unix, prev_session.end_time_unix);
        printf("  - Reported: %s\n", prev_session.reported ? "YES" : "NO");
        printf("  - Lifetime totals: %lu rotations, %lu sec\n", counts.lifetime_rotations, counts.lifetime_active_seconds);

        return true;
    }

    // No valid data found - start fresh
    prev_session.valid = false;
    session.session_id_decided = false;
    printf("[FLASH] No valid previous session found - starting fresh\n");
    return false;
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

// Helper function to merge with previous session
static void merge_with_previous_session(void)
{
    // Add previous session's counts to current counts
    counts.session_rotations += prev_session.rotation_count;
    counts.session_active_seconds += prev_session.active_time_seconds;

    // Determine the best start time for the merged session
    if (prev_session.start_time_unix != 0)
    {
        // Previous session had a known start time - use it (it's earlier)
        session.session_start_time_unix = prev_session.start_time_unix;
    }
    else if (session.session_start_time_unix != 0)
    {
        // Previous had unknown start, but we know current Pico boot time
        // Estimate: our boot time minus the previous session's walking time
        if (session.session_start_time_unix > prev_session.active_time_seconds)
        {
            session.session_start_time_unix = session.session_start_time_unix - prev_session.active_time_seconds;
            printf("[SESSION] Estimated merged start time: boot time %lu - prev active %lu = %lu\n",
                   session.session_start_time_unix + prev_session.active_time_seconds,
                   prev_session.active_time_seconds,
                   session.session_start_time_unix);
        }
    }
    // else: both unknown, session_start_time_unix stays 0
}

// Helper function to check if we're allowed to save to flash
// We require time to be synced OR 60 seconds to have passed since boot
static bool can_save_to_flash(void)
{
    if (session.time_acquired)
    {
        return true; // Time is synced, we can make accurate merge decisions
    }

    uint32_t current_boot_ms = to_ms_since_boot(get_absolute_time());
    if (current_boot_ms >= TIME_SYNC_TIMEOUT_MS)
    {
        return true; // Timeout reached, time sync probably isn't coming
    }

    return false; // Still waiting for time sync
}

void odometer_save_count(void)
{
    uint32_t current_time_unix = odometer_get_current_unix_time();
    uint32_t target_session_id;

    // First save: decide whether to merge with previous session or start new
    if (!session.session_id_decided)
    {
        printf("[SESSION] Making merge/new decision at first save...\n");
        printf("  - Current time acquired: %s\n", session.time_acquired ? "YES" : "NO");
        printf("  - Current session start: %lu\n", session.session_start_time_unix);
        printf("  - Previous session valid: %s\n", prev_session.valid ? "YES" : "NO");

        if (!prev_session.valid)
        {
            // No previous session - create new
            target_session_id = 1;
            printf("[SESSION] Decision: NEW session (no previous) -> ID %lu\n", target_session_id);
        }
        else if (prev_session.reported)
        {
            // Previous session was reported - must create new
            target_session_id = prev_session.session_id + 1;
            printf("[SESSION] Decision: NEW session (previous was reported) -> ID %lu\n", target_session_id);
        }
        else if (session.session_start_time_unix == 0)
        {
            // Current time unknown - merge into previous unreported session
            // (We can't calculate a gap, so assume it's a continuation)
            target_session_id = prev_session.session_id;
            printf("[SESSION] Decision: MERGE (current time unknown) -> ID %lu\n", target_session_id);
            merge_with_previous_session();
        }
        else if (prev_session.end_time_unix == 0)
        {
            // Previous session has unknown end time - merge since we can't calculate gap
            target_session_id = prev_session.session_id;
            printf("[SESSION] Decision: MERGE (previous end time unknown) -> ID %lu\n", target_session_id);
            merge_with_previous_session();
        }
        else
        {
            // We know both times - check 15-minute gap
            uint32_t gap_seconds = 900; // Default to 15 min if we can't calculate
            if (session.session_start_time_unix > prev_session.end_time_unix)
            {
                gap_seconds = session.session_start_time_unix - prev_session.end_time_unix;
            }
            printf("  - Gap between sessions: %lu seconds (%.1f minutes)\n", gap_seconds, gap_seconds / 60.0f);

            if (gap_seconds <= 900) // Within 15 minutes
            {
                // Merge into previous session
                target_session_id = prev_session.session_id;
                printf("[SESSION] Decision: MERGE (gap <= 15 min) -> ID %lu\n", target_session_id);
                merge_with_previous_session();
            }
            else
            {
                // Gap > 15 minutes - create new session
                target_session_id = prev_session.session_id + 1;
                printf("[SESSION] Decision: NEW session (gap > 15 min) -> ID %lu\n", target_session_id);
            }
        }

        // Lock in the session ID for this execution
        session.current_session_id = target_session_id;
        session.session_id_decided = true;
        printf("[SESSION] Session ID locked: %lu\n", target_session_id);
    }
    else
    {
        // Already decided - use the same session ID
        target_session_id = session.current_session_id;
    }

    // Calculate current session end time
    uint32_t session_end_time = current_time_unix;

    // If we have an end time but no start time, estimate start time from active seconds
    if (session_end_time != 0 && session.session_start_time_unix == 0)
    {
        uint32_t active_seconds = odometer_get_session_active_time_seconds();
        session.session_start_time_unix = session_end_time - active_seconds;
        printf("[SESSION] Estimated start time from end time - active seconds: %lu - %lu = %lu\n",
               session_end_time, active_seconds, session.session_start_time_unix);
    }

    // Calculate which sector to write to
    uint32_t target_sector = target_session_id % FLASH_SECTOR_COUNT;
    uint32_t sector_offset = FLASH_START_OFFSET + (target_sector * FLASH_SECTOR_SIZE);

    // Prepare data structure
    static uint8_t __attribute__((aligned(FLASH_PAGE_SIZE))) write_buffer[FLASH_PAGE_SIZE];
    flash_data_t *data = (flash_data_t *)write_buffer;
    memset(write_buffer, 0, FLASH_PAGE_SIZE);

    data->magic = FLASH_MAGIC_NUMBER;
    data->struct_version = 1;
    data->session_id = target_session_id;
    data->session_rotation_count = counts.session_rotations;
    data->session_active_time_seconds = odometer_get_session_active_time_seconds();
    data->session_start_time_unix = session.session_start_time_unix;
    data->session_end_time_unix = session_end_time;
    data->lifetime_rotation_count = counts.lifetime_rotations;
    data->lifetime_time_seconds = odometer_get_active_time_seconds();
    data->reported = 0; // New/updated sessions are not reported
    data->checksum = calculate_checksum(data);

    // Write to flash
    uint32_t ints = save_and_disable_interrupts();
    flash_range_erase(sector_offset, FLASH_SECTOR_SIZE);
    flash_range_program(sector_offset, write_buffer, FLASH_PAGE_SIZE);
    restore_interrupts(ints);

    // Update save state
    save_state.last_saved_count = counts.lifetime_rotations;
    save_state.last_save_time_ms = to_ms_since_boot(get_absolute_time());

    printf("[FLASH] Saved to flash:\n");
    printf("  - Session ID: %lu (sector %lu)\n", target_session_id, target_sector);
    printf("  - Session rotations: %lu, time: %lu sec\n", counts.session_rotations, odometer_get_session_active_time_seconds());
    printf("  - Lifetime rotations: %lu, time: %lu sec\n", counts.lifetime_rotations, odometer_get_active_time_seconds());
    printf("  - Start time: %lu, End time: %lu\n", session.session_start_time_unix, session_end_time);
}

void odometer_init(uint8_t pin)
{
    sensor.sensor_pin = pin;
    gpio_init(sensor.sensor_pin);
    gpio_set_dir(sensor.sensor_pin, GPIO_IN);
    gpio_pull_up(sensor.sensor_pin); // Enable internal pull-up resistor
    sensor.last_sensor_state = gpio_get(sensor.sensor_pin);

    // Initialize ADC for voltage monitoring
    adc_init();
    adc_gpio_init(29); // GPIO29 is ADC3, connected to VSYS via divider

    // Try to load count from flash, otherwise start at 0
    if (!load_count_from_flash())
    {
        counts.lifetime_rotations = 0;
        counts.lifetime_active_seconds = 0;
    }

    // Start fresh session - will decide merge/new at first save
    counts.session_rotations = 0;
    counts.session_active_seconds = 0;
}

void odometer_set_led_callback(led_callback_t callback)
{
    callbacks.led_callback = callback;
}

bool odometer_process(void)
{
    bool sensor_high = gpio_get(sensor.sensor_pin);
    bool rotation_detected = false;
    uint32_t current_time_ms = to_ms_since_boot(get_absolute_time());

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
            counts.boot_active_seconds += elapsed_seconds;
            counts.is_active = false;
        }
    }

    // Check voltage if voltage-based saving is enabled
    // Save immediately if voltage drops below threshold (power loss imminent)
    if (save_state.voltage_save_enabled)
    {
        uint16_t vsys_mv = odometer_read_voltage();
        if (vsys_mv <= save_state.voltage_threshold_mv)
        {
            // Voltage is low - save immediately before potential power loss
            // (only if count has changed, time is synced, and at least 1 minute since last save)
            if (can_save_to_flash() &&
                counts.lifetime_rotations != save_state.last_saved_count &&
                (current_time_ms - save_state.last_save_time_ms) >= FLASH_SAVE_INTERVAL_MS)
            {
                odometer_save_count();
            }
        }
    }

    // Simple edge detection - detect state changes from the latching Hall Effect sensor
    // Each edge represents a half-rotation (sensor toggles on each magnet pass)
    if (sensor_high != sensor.last_sensor_state)
    {
        sensor.last_sensor_state = sensor_high;

        // Every edge is a half-rotation, so count every 2 edges as 1 full rotation
        // We'll count on falling edges (sensor goes LOW)
        if (!sensor_high)
        {
            // Complete rotation detected!
            counts.lifetime_rotations++;
            counts.session_rotations++;
            counts.boot_rotations++;
            rotation_detected = true;

            // Update active time tracking
            counts.last_rotation_time_ms = current_time_ms;
            if (!counts.is_active)
            {
                // Starting a new active period
                counts.active_start_time_ms = current_time_ms;
                counts.is_active = true;
            }

            // Check if we should save to flash based on rotation count
            // Save every ROTATION_SAVE_INTERVAL rotations (2500 = ~0.5 miles)
            // Only save if time is synced (or timeout reached) to ensure accurate merge decisions
            if (can_save_to_flash() &&
                (counts.lifetime_rotations - save_state.last_saved_count) >= ROTATION_SAVE_INTERVAL)
            {
                odometer_save_count();
            }
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
    counts.boot_rotations++;

    // Update active time tracking
    counts.last_rotation_time_ms = current_time_ms;
    if (!counts.is_active)
    {
        // Starting a new active period
        counts.active_start_time_ms = current_time_ms;
        counts.is_active = true;
    }

    // Check if we should save to flash based on rotation count
    // Only save if time is synced (or timeout reached) to ensure accurate merge decisions
    if (can_save_to_flash() &&
        (counts.lifetime_rotations - save_state.last_saved_count) >= ROTATION_SAVE_INTERVAL)
    {
        odometer_save_count();
    }
}

// Get list of unreported sessions from flash
uint32_t odometer_get_unreported_sessions(session_record_t *sessions, uint32_t max_sessions)
{
    uint32_t count = 0;

    // Determine which session ID to exclude:
    // - If we haven't decided yet, exclude the previous session (we might merge into it)
    // - If we decided and merged, exclude the current session (flash data is stale)
    uint32_t exclude_session_id = 0;
    if (!session.session_id_decided && prev_session.valid && !prev_session.reported)
    {
        // Check if we might merge into the previous session
        uint32_t current_time = odometer_get_current_unix_time();

        if (!session.time_acquired)
        {
            // No time yet - we'll merge, so exclude it
            exclude_session_id = prev_session.session_id;
            printf("[SESSION] Excluding session %lu from unreported list (merge pending - no time yet)\n", exclude_session_id);
        }
        else if (prev_session.end_time_unix == 0)
        {
            // Previous end time unknown - we'll merge, so exclude it
            exclude_session_id = prev_session.session_id;
            printf("[SESSION] Excluding session %lu from unreported list (merge pending - previous end time unknown)\n", exclude_session_id);
        }
        else if (current_time > 0 && current_time >= prev_session.end_time_unix)
        {
            uint32_t gap_seconds = current_time - prev_session.end_time_unix;
            uint32_t gap_minutes = gap_seconds / 60;
            uint32_t gap_secs = gap_seconds % 60;

            if (gap_seconds <= 900)
            {
                // Within 15 minutes - we'll merge, so exclude it
                exclude_session_id = prev_session.session_id;
                printf("[SESSION] Excluding session %lu from unreported list (merge pending - %lu:%02lu since end, < 15 min)\n",
                       exclude_session_id, gap_minutes, gap_secs);
            }
            else
            {
                // Gap > 15 minutes - we'll create new session, so previous is safe to show
                printf("[SESSION] Previous session %lu will NOT be merged (%lu:%02lu since end, > 15 min)\n",
                       prev_session.session_id, gap_minutes, gap_secs);
            }
        }
    }
    else if (session.session_id_decided)
    {
        // Decided - exclude our current session (it's the active one)
        exclude_session_id = session.current_session_id;
        printf("[SESSION] Excluding session %lu from unreported list (current session)\n", exclude_session_id);
    }

    // Scan all 64 sectors for unreported sessions
    for (uint32_t sector = 0; sector < FLASH_SECTOR_COUNT && count < max_sessions; sector++)
    {
        uint32_t sector_offset = FLASH_START_OFFSET + (sector * FLASH_SECTOR_SIZE);
        const flash_data_t *flash_data = (const flash_data_t *)(XIP_BASE + sector_offset);

        // Verify magic number and checksum
        if (flash_data->magic == FLASH_MAGIC_NUMBER &&
            flash_data->checksum == calculate_checksum(flash_data) &&
            flash_data->reported == 0) // Only unreported sessions
        {
            // Skip the excluded session
            if (exclude_session_id != 0 && flash_data->session_id == exclude_session_id)
            {
                continue;
            }

            // Add this session to the list
            sessions[count].session_id = flash_data->session_id;
            sessions[count].rotation_count = flash_data->session_rotation_count;
            sessions[count].active_time_seconds = flash_data->session_active_time_seconds;
            sessions[count].start_time_unix = flash_data->session_start_time_unix;
            sessions[count].end_time_unix = flash_data->session_end_time_unix;
            count++;
        }
    }

    return count;
}

// Mark a specific session as reported
bool odometer_mark_session_reported(uint32_t session_id)
{
    // Check if this is the current active session
    // If so, we need to finalize it and start a fresh session
    if (session.session_id_decided && session_id == session.current_session_id)
    {
        // Check if we merged (boot counts differ from session counts)
        bool was_merged = (counts.session_rotations != counts.boot_rotations) ||
                         (counts.session_active_seconds != counts.boot_active_seconds);

        printf("[SESSION] Marking current session %lu as reported\n", session_id);
        printf("  - Was merged: %s\n", was_merged ? "YES" : "NO");
        printf("  - Session rotations: %lu, boot rotations: %lu\n",
               counts.session_rotations, counts.boot_rotations);

        // First, save the current session state to flash and mark as reported
        // We need to save first to ensure current data is persisted
        odometer_save_count();

        // Now mark it as reported in flash
        for (uint32_t sector = 0; sector < FLASH_SECTOR_COUNT; sector++)
        {
            uint32_t sector_offset = FLASH_START_OFFSET + (sector * FLASH_SECTOR_SIZE);
            const flash_data_t *flash_data = (const flash_data_t *)(XIP_BASE + sector_offset);

            if (flash_data->magic == FLASH_MAGIC_NUMBER &&
                flash_data->checksum == calculate_checksum(flash_data) &&
                flash_data->session_id == session_id)
            {
                static uint8_t __attribute__((aligned(FLASH_PAGE_SIZE))) write_buffer[FLASH_PAGE_SIZE];
                flash_data_t *data = (flash_data_t *)write_buffer;
                memcpy(write_buffer, (const void *)flash_data, sizeof(flash_data_t));
                data->reported = 1;
                data->checksum = calculate_checksum(data);

                uint32_t ints = save_and_disable_interrupts();
                flash_range_erase(sector_offset, FLASH_SECTOR_SIZE);
                flash_range_program(sector_offset, write_buffer, FLASH_PAGE_SIZE);
                restore_interrupts(ints);
                printf("  - Marked session %lu as reported in flash\n", session_id);
                break;
            }
        }

        // Now create a new session
        uint32_t new_session_id = session_id + 1;
        session.current_session_id = new_session_id;

        if (was_merged)
        {
            // If we merged, the new session should have only boot-time data
            counts.session_rotations = counts.boot_rotations;
            counts.session_active_seconds = counts.boot_active_seconds;

            // Reset speed window since we're starting fresh tracking
            // The speed window contains old data from before the merge reversal
            odometer_reset_speed_window();

            // Update session start time to be our boot time
            if (session.time_acquired)
            {
                uint32_t current_boot_ms = to_ms_since_boot(get_absolute_time());
                session.session_start_time_unix = session.time_reference_unix -
                    (current_boot_ms - session.time_reference_boot_ms) / 1000;
            }
            printf("  - Reversed merge: new session has boot-time data only\n");
        }
        else
        {
            // Not merged - start completely fresh
            counts.session_rotations = 0;
            counts.session_active_seconds = 0;
            counts.boot_rotations = 0;
            counts.boot_active_seconds = 0;

            // Reset speed window since boot_rotations is being reset
            odometer_reset_speed_window();

            // New session starts now
            if (session.time_acquired)
            {
                session.session_start_time_unix = odometer_get_current_unix_time();
            }
            printf("  - Starting fresh session with zero counts\n");
        }

        printf("  - New session ID: %lu (rotations: %lu)\n",
               new_session_id, counts.session_rotations);

        // Save the new session to flash immediately (even if empty)
        // This ensures the new session ID is persisted
        odometer_save_count();

        return true;
    }

    // Normal case: find the session in flash and mark it as reported
    for (uint32_t sector = 0; sector < FLASH_SECTOR_COUNT; sector++)
    {
        uint32_t sector_offset = FLASH_START_OFFSET + (sector * FLASH_SECTOR_SIZE);
        const flash_data_t *flash_data = (const flash_data_t *)(XIP_BASE + sector_offset);

        // Check if this is the session we're looking for
        if (flash_data->magic == FLASH_MAGIC_NUMBER &&
            flash_data->checksum == calculate_checksum(flash_data) &&
            flash_data->session_id == session_id)
        {
            // Found it! Update the reported flag
            static uint8_t __attribute__((aligned(FLASH_PAGE_SIZE))) write_buffer[FLASH_PAGE_SIZE];
            flash_data_t *data = (flash_data_t *)write_buffer;

            // Copy existing data
            memcpy(write_buffer, (const void *)flash_data, sizeof(flash_data_t));

            // Update reported flag
            data->reported = 1;

            // Recalculate checksum
            data->checksum = calculate_checksum(data);

            // Write back to flash
            uint32_t ints = save_and_disable_interrupts();
            flash_range_erase(sector_offset, FLASH_SECTOR_SIZE);
            flash_range_program(sector_offset, write_buffer, FLASH_PAGE_SIZE);
            restore_interrupts(ints);

            return true;
        }
    }

    return false; // Session not found
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

        printf("[TIME] Time reference set!\n");
        printf("  - Current Unix time: %lu\n", unix_timestamp);
        printf("  - Uptime: %lu ms (%.1f sec)\n", current_boot_ms, current_boot_ms / 1000.0f);
        printf("  - Calculated session start: %lu\n", session.session_start_time_unix);

        // Print human-readable date (approximate - just for debugging)
        uint32_t days_since_epoch = unix_timestamp / 86400;
        uint32_t years = days_since_epoch / 365 + 1970;
        printf("  - Approximate date: year ~%lu\n", years);

        // Now that we have time, check if we should merge with previous session
        // Do this immediately so display shows correct values right away
        if (!session.session_id_decided && prev_session.valid && !prev_session.reported)
        {
            bool should_merge = false;

            if (prev_session.end_time_unix == 0)
            {
                // Previous session has unknown end time - merge
                should_merge = true;
                printf("[SESSION] Will merge with previous session (previous end time unknown)\n");
            }
            else if (session.session_start_time_unix >= prev_session.end_time_unix)
            {
                uint32_t gap_seconds = session.session_start_time_unix - prev_session.end_time_unix;
                if (gap_seconds <= 900)
                {
                    should_merge = true;
                    printf("[SESSION] Will merge with previous session (gap %lu sec <= 15 min)\n", gap_seconds);
                }
                else
                {
                    printf("[SESSION] Will NOT merge with previous session (gap %lu sec > 15 min)\n", gap_seconds);
                }
            }

            if (should_merge)
            {
                // Merge now so display shows correct values immediately
                session.current_session_id = prev_session.session_id;
                session.session_id_decided = true;
                merge_with_previous_session();
                printf("[SESSION] Merged! Session ID: %lu, rotations: %lu, time: %lu sec\n",
                       session.current_session_id, counts.session_rotations, counts.session_active_seconds);
            }
        }
    }
    else
    {
        printf("[TIME] Warning: Received invalid timestamp (0)\n");
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

// Check if session ID has been decided
bool odometer_is_session_id_decided(void)
{
    return session.session_id_decided;
}

// Get boot-time rotation count (rotations since this boot only, excludes merged data)
uint32_t odometer_get_boot_rotation_count(void)
{
    return counts.boot_rotations;
}

// Get boot-time active seconds (active time since this boot only, excludes merged data)
uint32_t odometer_get_boot_active_time_seconds(void)
{
    uint32_t total = counts.boot_active_seconds;

    // If currently active, add the time elapsed in the current active period
    if (counts.is_active)
    {
        uint32_t current_time_ms = to_ms_since_boot(get_absolute_time());
        uint32_t current_period_seconds = (current_time_ms - counts.active_start_time_ms) / 1000;
        total += current_period_seconds;
    }

    return total;
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

    printf("[ODOMETER] Setting lifetime totals:\n");
    printf("  - Hours: %.2f -> %lu seconds\n", hours, seconds);
    printf("  - Distance: %.2f miles -> %lu rotations\n", distance_miles, rotations);
    printf("  - Previous lifetime: %lu rotations, %lu seconds\n",
           counts.lifetime_rotations, counts.lifetime_active_seconds);

    // Update the lifetime totals
    counts.lifetime_rotations = rotations;
    counts.lifetime_active_seconds = seconds;

    printf("  - New lifetime: %lu rotations, %lu seconds\n",
           counts.lifetime_rotations, counts.lifetime_active_seconds);

    // Save to flash immediately
    printf("  - Saving to flash...\n");
    odometer_save_count();
    printf("  - Lifetime totals saved successfully\n");
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
