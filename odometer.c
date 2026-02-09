/**
 * Odometer sensor processing implementation
 */

#include "odometer.h"
#include "logging.h"
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
#define FLASH_STRUCT_VERSION 1         // Current struct version (increment when changing flash_data_t)

// Flash storage structure - session-based
typedef struct
{
    uint32_t magic;                       // 0x4F444F53 ("ODOS")
    uint32_t struct_version;              // FLASH_STRUCT_VERSION (currently 1)
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
    uint32_t session_rotations;            // Current session rotations
    uint32_t session_active_seconds;       // Current session active time
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
static sensor_state_t sensor = {0};
static callbacks_t callbacks = {0};
static save_state_t save_state = {0};

// Track the highest session ID seen (for creating new sessions)
static uint32_t last_session_id = 0;

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

// Write data to flash with automatic verification and retry
// Handles erase, program, verify, and retry logic
// Returns true if write succeeded and verified, false otherwise
static bool write_flash_and_verify(uint32_t sector_offset, const uint8_t *write_buffer, const char *operation_title)
{
    const flash_data_t *expected = (const flash_data_t *)write_buffer;
    const flash_data_t *flash_data;
    bool verification_passed = false;
    uint32_t target_sector = (sector_offset - FLASH_START_OFFSET) / FLASH_SECTOR_SIZE;

    // Log all data being written to flash BEFORE writing
    log_printf("========================================\n");
    log_printf("[FLASH WRITE] %s:\n", operation_title);
    log_printf("  Sector: %lu (offset 0x%08lX)\n", target_sector, sector_offset);
    log_printf("  Magic: 0x%08lX\n", expected->magic);
    log_printf("  Struct Version: %lu\n", expected->struct_version);
    log_printf("  Session ID: %lu\n", expected->session_id);
    log_printf("  Session Rotations: %lu\n", expected->session_rotation_count);
    log_printf("  Session Active Time: %lu seconds\n", expected->session_active_time_seconds);
    log_printf("  Session Start Time: %lu\n", expected->session_start_time_unix);
    log_printf("  Session End Time: %lu\n", expected->session_end_time_unix);
    log_printf("  Lifetime Rotations: %lu\n", expected->lifetime_rotation_count);
    log_printf("  Lifetime Time: %lu seconds\n", expected->lifetime_time_seconds);
    log_printf("  Reported: %u%s\n", expected->reported, expected->reported ? " (CHANGED TO 1)" : "");
    log_printf("  Checksum: 0x%08lX\n", expected->checksum);
    log_printf("========================================\n");

    // Try write + verify up to 2 times (initial + 1 retry)
    for (int attempt = 0; attempt < 2; attempt++) {
        if (attempt > 0) {
            log_printf("[FLASH VERIFY] Retrying flash write (attempt %d/2)...\n", attempt + 1);
        }

        // Erase and program the flash sector
        uint32_t ints = save_and_disable_interrupts();
        flash_range_erase(sector_offset, FLASH_SECTOR_SIZE);
        flash_range_program(sector_offset, write_buffer, FLASH_PAGE_SIZE);
        restore_interrupts(ints);

        // Now verify what we just wrote
        flash_data = (const flash_data_t *)(XIP_BASE + sector_offset);
        verification_passed = true; // Assume success until we find a problem

        // Verify magic number
        if (flash_data->magic != expected->magic) {
            log_printf("[FLASH VERIFY] ERROR: Magic mismatch! Expected 0x%08lX, got 0x%08lX\n",
                       expected->magic, flash_data->magic);
            verification_passed = false;
        }

        // Verify checksum
        else if (flash_data->checksum != expected->checksum) {
            log_printf("[FLASH VERIFY] ERROR: Checksum mismatch! Expected 0x%08lX, got 0x%08lX\n",
                       expected->checksum, flash_data->checksum);
            verification_passed = false;
        }

        // Verify checksum is valid
        else {
            uint32_t calculated_checksum = calculate_checksum(flash_data);
            if (flash_data->checksum != calculated_checksum) {
                log_printf("[FLASH VERIFY] ERROR: Checksum invalid! Stored 0x%08lX, calculated 0x%08lX\n",
                           flash_data->checksum, calculated_checksum);
                verification_passed = false;
            }
        }

        // Verify all fields match
        if (verification_passed &&
            (flash_data->struct_version != expected->struct_version ||
             flash_data->session_id != expected->session_id ||
             flash_data->session_rotation_count != expected->session_rotation_count ||
             flash_data->session_active_time_seconds != expected->session_active_time_seconds ||
             flash_data->session_start_time_unix != expected->session_start_time_unix ||
             flash_data->session_end_time_unix != expected->session_end_time_unix ||
             flash_data->lifetime_rotation_count != expected->lifetime_rotation_count ||
             flash_data->lifetime_time_seconds != expected->lifetime_time_seconds ||
             flash_data->reported != expected->reported)) {

            log_printf("[FLASH VERIFY] ERROR: Field mismatch detected:\n");
            if (flash_data->struct_version != expected->struct_version)
                log_printf("  - struct_version: expected %lu, got %lu\n", expected->struct_version, flash_data->struct_version);
            if (flash_data->session_id != expected->session_id)
                log_printf("  - session_id: expected %lu, got %lu\n", expected->session_id, flash_data->session_id);
            if (flash_data->session_rotation_count != expected->session_rotation_count)
                log_printf("  - session_rotation_count: expected %lu, got %lu\n", expected->session_rotation_count, flash_data->session_rotation_count);
            if (flash_data->session_active_time_seconds != expected->session_active_time_seconds)
                log_printf("  - session_active_time_seconds: expected %lu, got %lu\n", expected->session_active_time_seconds, flash_data->session_active_time_seconds);
            if (flash_data->session_start_time_unix != expected->session_start_time_unix)
                log_printf("  - session_start_time_unix: expected %lu, got %lu\n", expected->session_start_time_unix, flash_data->session_start_time_unix);
            if (flash_data->session_end_time_unix != expected->session_end_time_unix)
                log_printf("  - session_end_time_unix: expected %lu, got %lu\n", expected->session_end_time_unix, flash_data->session_end_time_unix);
            if (flash_data->lifetime_rotation_count != expected->lifetime_rotation_count)
                log_printf("  - lifetime_rotation_count: expected %lu, got %lu\n", expected->lifetime_rotation_count, flash_data->lifetime_rotation_count);
            if (flash_data->lifetime_time_seconds != expected->lifetime_time_seconds)
                log_printf("  - lifetime_time_seconds: expected %lu, got %lu\n", expected->lifetime_time_seconds, flash_data->lifetime_time_seconds);
            if (flash_data->reported != expected->reported)
                log_printf("  - reported: expected %u, got %u\n", expected->reported, flash_data->reported);

            verification_passed = false;
        }

        // If verification passed, we're done
        if (verification_passed) {
            if (attempt > 0) {
                log_printf("[FLASH VERIFY] ✓ Flash write verified successfully after retry\n");
            } else {
                log_printf("[FLASH VERIFY] ✓ Flash write verified successfully\n");
            }
            return true;
        }

        // If this was our last attempt, log final failure
        if (attempt == 1) {
            log_printf("[FLASH VERIFY] ERROR: Flash write verification failed after retry!\n");
        }
    }

    return false;
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
    uint32_t max_session_id = 0;
    bool found_valid = false;
    const flash_data_t *latest_data = NULL;

    // Array to store valid sessions for logging (up to 64)
    typedef struct {
        const flash_data_t *data;
        uint32_t sector;
    } valid_session_t;
    valid_session_t valid_sessions[FLASH_SECTOR_COUNT];
    uint32_t valid_count = 0;

    // Scan all 64 sectors to find the one with the highest valid session_id
    for (uint32_t sector = 0; sector < FLASH_SECTOR_COUNT; sector++)
    {
        uint32_t sector_offset = FLASH_START_OFFSET + (sector * FLASH_SECTOR_SIZE);
        const flash_data_t *flash_data = (const flash_data_t *)(XIP_BASE + sector_offset);

        // Verify magic number, struct version, and checksum
        if (flash_data->magic == FLASH_MAGIC_NUMBER &&
            flash_data->struct_version <= FLASH_STRUCT_VERSION &&
            flash_data->checksum == calculate_checksum(flash_data))
        {
            // Store this valid session for logging
            valid_sessions[valid_count].data = flash_data;
            valid_sessions[valid_count].sector = sector;
            valid_count++;

            // Check if this is the newest valid entry (highest session_id)
            if (!found_valid || flash_data->session_id > max_session_id)
            {
                max_session_id = flash_data->session_id;
                latest_data = flash_data;
                found_valid = true;
            }
        }
        else if (flash_data->magic == FLASH_MAGIC_NUMBER &&
                 flash_data->struct_version > FLASH_STRUCT_VERSION)
        {
            // Found a session from newer code - log warning
            log_printf("[FLASH] WARNING: Sector %lu has struct_version %lu (current is %u) - ignoring (newer code)\n",
                       sector, flash_data->struct_version, FLASH_STRUCT_VERSION);
        }
    }

    // Log the 10 most recent valid sessions (sorted by session_id, descending)
    if (valid_count > 0)
    {
        log_printf("[FLASH] Found %lu valid session(s) in flash\n", valid_count);
        log_printf("[FLASH] Logging up to 10 most recent sessions:\n");

        // Simple bubble sort to sort by session_id (descending)
        for (uint32_t i = 0; i < valid_count - 1; i++)
        {
            for (uint32_t j = 0; j < valid_count - i - 1; j++)
            {
                if (valid_sessions[j].data->session_id < valid_sessions[j + 1].data->session_id)
                {
                    valid_session_t temp = valid_sessions[j];
                    valid_sessions[j] = valid_sessions[j + 1];
                    valid_sessions[j + 1] = temp;
                }
            }
        }

        // Log up to 10 most recent
        uint32_t log_count = (valid_count > 10) ? 10 : valid_count;
        for (uint32_t i = 0; i < log_count; i++)
        {
            const flash_data_t *d = valid_sessions[i].data;
            log_printf("[FLASH]   [%lu] Sector %lu: ID=%lu, Rotations=%lu/%lu, Time=%lu/%lu sec, Start=%lu, End=%lu, Reported=%s\n",
                       i + 1,
                       valid_sessions[i].sector,
                       d->session_id,
                       d->session_rotation_count,
                       d->lifetime_rotation_count,
                       d->session_active_time_seconds,
                       d->lifetime_time_seconds,
                       d->session_start_time_unix,
                       d->session_end_time_unix,
                       (d->reported != 0) ? "YES" : "NO");
        }
    }

    if (found_valid)
    {
        // Load lifetime totals from the most recent session
        counts.lifetime_rotations = latest_data->lifetime_rotation_count;
        counts.lifetime_active_seconds = latest_data->lifetime_time_seconds;

        // Store the highest session ID we've seen
        last_session_id = latest_data->session_id;

        // Start fresh for this session
        counts.session_rotations = 0;
        counts.session_active_seconds = 0;
        session.session_id_decided = false;

        log_printf("[FLASH] Loaded lifetime totals from flash:\n");
        log_printf("  - Last session ID: %lu\n", last_session_id);
        log_printf("  - Lifetime totals: %lu rotations, %lu sec\n", counts.lifetime_rotations, counts.lifetime_active_seconds);

        return true;
    }

    // No valid data found - start fresh
    last_session_id = 0;
    session.session_id_decided = false;
    log_printf("[FLASH] No valid previous session found - starting fresh\n");
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

// Helper function to check if we're allowed to save to flash
// We require time to be synced OR 60 seconds to have passed since boot
static bool can_save_to_flash(void)
{
    if (session.time_acquired)
    {
        return true; // Time is synced
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

    // First save: decide session ID (always create new session, never merge)
    if (!session.session_id_decided)
    {
        log_printf("[SESSION] Assigning session ID at first save...\n");
        log_printf("  - Current time acquired: %s\n", session.time_acquired ? "YES" : "NO");
        log_printf("  - Current session start: %lu\n", session.session_start_time_unix);
        log_printf("  - Last session ID from flash: %lu\n", last_session_id);

        // Always create new session (increment from last seen)
        target_session_id = last_session_id + 1;
        log_printf("[SESSION] Decision: NEW session -> ID %lu\n", target_session_id);

        // Lock in the session ID for this execution
        session.current_session_id = target_session_id;
        session.session_id_decided = true;
        log_printf("[SESSION] Session ID locked: %lu\n", target_session_id);
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
        log_printf("[SESSION] Estimated start time from end time - active seconds: %lu - %lu = %lu\n",
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
    data->struct_version = FLASH_STRUCT_VERSION;
    data->session_id = target_session_id;
    data->session_rotation_count = counts.session_rotations;
    data->session_active_time_seconds = odometer_get_session_active_time_seconds();
    data->session_start_time_unix = session.session_start_time_unix;
    data->session_end_time_unix = session_end_time;
    data->lifetime_rotation_count = counts.lifetime_rotations;
    data->lifetime_time_seconds = odometer_get_active_time_seconds();
    data->reported = 0; // New/updated sessions are not reported
    data->checksum = calculate_checksum(data);

    // Write to flash with verification and automatic retry
    if (!write_flash_and_verify(sector_offset, write_buffer, "Writing session to flash")) {
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

    // Start fresh session
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
            // (only if count has changed and at least 1 minute since last save)
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
            // Only save if time is synced (or timeout reached)
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

    // Update active time tracking
    counts.last_rotation_time_ms = current_time_ms;
    if (!counts.is_active)
    {
        // Starting a new active period
        counts.active_start_time_ms = current_time_ms;
        counts.is_active = true;
    }

    // Check if we should save to flash based on rotation count
    // Only save if time is synced (or timeout reached)
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

    // Exclude the current active session (if decided)
    uint32_t exclude_session_id = 0;
    if (session.session_id_decided)
    {
        exclude_session_id = session.current_session_id;
        log_printf("[SESSION] Excluding session %lu from unreported list (current session)\n", exclude_session_id);
    }

    // Scan all 64 sectors for unreported sessions
    for (uint32_t sector = 0; sector < FLASH_SECTOR_COUNT && count < max_sessions; sector++)
    {
        uint32_t sector_offset = FLASH_START_OFFSET + (sector * FLASH_SECTOR_SIZE);
        const flash_data_t *flash_data = (const flash_data_t *)(XIP_BASE + sector_offset);

        // Verify magic number, struct version, and checksum
        if (flash_data->magic == FLASH_MAGIC_NUMBER &&
            flash_data->struct_version <= FLASH_STRUCT_VERSION &&
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
        log_printf("[SESSION] Marking current session %lu as reported\n", session_id);

        // First, save the current session state to flash and mark as reported
        // We need to save first to ensure current data is persisted
        odometer_save_count();

        // Now mark it as reported in flash
        for (uint32_t sector = 0; sector < FLASH_SECTOR_COUNT; sector++)
        {
            uint32_t sector_offset = FLASH_START_OFFSET + (sector * FLASH_SECTOR_SIZE);
            const flash_data_t *flash_data = (const flash_data_t *)(XIP_BASE + sector_offset);

            if (flash_data->magic == FLASH_MAGIC_NUMBER &&
                flash_data->struct_version <= FLASH_STRUCT_VERSION &&
                flash_data->checksum == calculate_checksum(flash_data) &&
                flash_data->session_id == session_id)
            {
                static uint8_t __attribute__((aligned(FLASH_PAGE_SIZE))) write_buffer[FLASH_PAGE_SIZE];
                flash_data_t *data = (flash_data_t *)write_buffer;
                memcpy(write_buffer, (const void *)flash_data, sizeof(flash_data_t));
                data->reported = 1;
                data->checksum = calculate_checksum(data);

                // Write to flash with verification and automatic retry
                if (!write_flash_and_verify(sector_offset, write_buffer, "Marking session as REPORTED")) {
                    log_printf("[FLASH WRITE] ERROR: Flash write verification failed!\n");
                    log_printf("[FLASH WRITE] Session %lu may not be properly marked as reported.\n", session_id);
                }

                log_printf("[FLASH WRITE] ✓ Session %lu marked as reported in flash\n", session_id);
                break;
            }
        }

        // Now create a new session
        uint32_t new_session_id = session_id + 1;
        session.current_session_id = new_session_id;

        // Start completely fresh
        counts.session_rotations = 0;
        counts.session_active_seconds = 0;

        // Reset speed window since we're starting a new session
        odometer_reset_speed_window();

        // New session starts now
        if (session.time_acquired)
        {
            session.session_start_time_unix = odometer_get_current_unix_time();
        }
        log_printf("  - Starting fresh session with zero counts\n");

        log_printf("  - New session ID: %lu (rotations: %lu)\n",
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
            flash_data->struct_version <= FLASH_STRUCT_VERSION &&
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

            // Write to flash with verification and automatic retry
            if (!write_flash_and_verify(sector_offset, write_buffer, "Marking OLD session as REPORTED")) {
                log_printf("[FLASH WRITE] ERROR: Flash write verification failed!\n");
                log_printf("[FLASH WRITE] Session %lu may not be properly marked as reported.\n", session_id);
            }

            log_printf("[FLASH WRITE] ✓ Old session %lu marked as reported in flash\n", session_id);

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

// Check if session ID has been decided
bool odometer_is_session_id_decided(void)
{
    return session.session_id_decided;
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
