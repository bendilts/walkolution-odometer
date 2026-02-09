/**
 * Flash storage module implementation
 */

#include "flash.h"
#include "logging.h"
#include "hardware/flash.h"
#include "hardware/sync.h"
#include <string.h>

// Internal flash storage structure - includes verification fields
// This is never exposed to callers; they work with session_data_t
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

// Calculate XOR checksum for flash data (internal function)
static uint32_t flash_calculate_checksum(const flash_data_t *data)
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

bool flash_write(uint32_t sector_index, const session_data_t *data, const char *operation_title)
{
    const flash_data_t *flash_data;
    bool verification_passed = false;
    uint32_t sector_offset = FLASH_START_OFFSET + (sector_index * FLASH_SECTOR_SIZE);

    // Prepare aligned write buffer with internal flash structure
    static uint8_t __attribute__((aligned(FLASH_PAGE_SIZE))) write_buffer[FLASH_PAGE_SIZE];
    memset(write_buffer, 0, FLASH_PAGE_SIZE);

    // Build internal flash structure from session data
    flash_data_t internal_data;
    internal_data.magic = FLASH_MAGIC_NUMBER;
    internal_data.struct_version = FLASH_STRUCT_VERSION;
    internal_data.session_id = data->session_id;
    internal_data.session_rotation_count = data->session_rotation_count;
    internal_data.session_active_time_seconds = data->session_active_time_seconds;
    internal_data.session_start_time_unix = data->session_start_time_unix;
    internal_data.session_end_time_unix = data->session_end_time_unix;
    internal_data.lifetime_rotation_count = data->lifetime_rotation_count;
    internal_data.lifetime_time_seconds = data->lifetime_time_seconds;
    internal_data.reported = data->reported;
    internal_data.checksum = flash_calculate_checksum(&internal_data);

    memcpy(write_buffer, &internal_data, sizeof(flash_data_t));

    const flash_data_t *expected = (const flash_data_t *)write_buffer;

    // Log all data being written to flash BEFORE writing
    log_printf("========================================\n");
    log_printf("[FLASH WRITE] %s:\n", operation_title);
    log_printf("  Sector: %lu (offset 0x%08lX)\n", sector_index, sector_offset);
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
            uint32_t calculated_checksum = flash_calculate_checksum(flash_data);
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

bool flash_read(uint32_t sector_index, session_data_t *data)
{
    uint32_t sector_offset = FLASH_START_OFFSET + (sector_index * FLASH_SECTOR_SIZE);
    const flash_data_t *flash_data = (const flash_data_t *)(XIP_BASE + sector_offset);

    // Verify magic number
    if (flash_data->magic != FLASH_MAGIC_NUMBER) {
        return false;
    }

    // Check if struct version is newer than what we can handle
    if (flash_data->struct_version > FLASH_STRUCT_VERSION) {
        // Verify checksum to confirm this is valid data from newer code
        uint32_t calculated_checksum = flash_calculate_checksum(flash_data);
        if (flash_data->checksum == calculated_checksum) {
            // Valid data but from newer code - erase it to prevent corruption
            log_printf("[FLASH] WARNING: Sector %lu has newer struct_version %lu (current is %u)\n",
                       sector_index, flash_data->struct_version, FLASH_STRUCT_VERSION);
            log_printf("[FLASH] Erasing sector to prevent corruption...\n");

            uint32_t ints = save_and_disable_interrupts();
            flash_range_erase(sector_offset, FLASH_SECTOR_SIZE);
            restore_interrupts(ints);

            log_printf("[FLASH] Sector %lu erased successfully\n", sector_index);
        }
        return false;
    }

    // Verify checksum
    uint32_t calculated_checksum = flash_calculate_checksum(flash_data);
    if (flash_data->checksum != calculated_checksum) {
        return false;
    }

    // All checks passed - copy session data to output (excluding internal fields)
    data->session_id = flash_data->session_id;
    data->session_rotation_count = flash_data->session_rotation_count;
    data->session_active_time_seconds = flash_data->session_active_time_seconds;
    data->session_start_time_unix = flash_data->session_start_time_unix;
    data->session_end_time_unix = flash_data->session_end_time_unix;
    data->lifetime_rotation_count = flash_data->lifetime_rotation_count;
    data->lifetime_time_seconds = flash_data->lifetime_time_seconds;
    data->reported = flash_data->reported;
    return true;
}
