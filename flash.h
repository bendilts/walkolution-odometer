/**
 * Flash storage module for odometer session data
 * Provides read/write operations with verification and wear-leveling
 */

#ifndef FLASH_H
#define FLASH_H

#include <stdint.h>
#include <stdbool.h>

// Flash storage configuration - wear leveling with 64 sectors
#define FLASH_SECTOR_COUNT 64
#define FLASH_START_OFFSET (PICO_FLASH_SIZE_BYTES - (FLASH_SECTOR_SIZE * FLASH_SECTOR_COUNT))
#define FLASH_MAGIC_NUMBER 0x4F444F53 // "ODOS" in hex (Odometer Session)
#define FLASH_STRUCT_VERSION 1         // Current struct version (increment when changing flash_data_t)

// Public session data structure - this is what callers work with
// Internal flash fields (magic, version, checksum) are handled by flash module
typedef struct
{
    uint32_t session_id;                  // Monotonically increasing session counter
    uint32_t session_rotation_count;      // Rotations in this session
    uint32_t session_active_time_seconds; // Active time in this session (seconds)
    uint32_t session_start_time_unix;     // Unix timestamp when session started (0 = unknown)
    uint32_t session_end_time_unix;       // Unix timestamp when session ended (0 = unknown)
    uint32_t lifetime_rotation_count;     // All-time total rotations
    uint32_t lifetime_time_seconds;       // All-time total active seconds
    uint8_t reported;                     // 0 = not reported to fitness app, 1 = reported
} session_data_t;

/**
 * Write session data to flash with automatic verification and retry
 * Handles internal fields (magic number, version, checksum), erase, program, verify, and retry logic
 *
 * @param sector_index Sector index (0 to FLASH_SECTOR_COUNT-1)
 * @param data Pointer to session_data_t structure to write
 * @param operation_title Human-readable operation description for logging
 * @return true if write succeeded and verified, false otherwise
 */
bool flash_write(uint32_t sector_index, const session_data_t *data, const char *operation_title);

/**
 * Read and verify session data from flash
 * Checks internal fields (magic number, struct version, checksum) and returns only session data
 *
 * @param sector_index Sector index (0 to FLASH_SECTOR_COUNT-1)
 * @param data Pointer to session_data_t structure to fill with read data
 * @return true if data is valid and was read successfully, false otherwise
 */
bool flash_read(uint32_t sector_index, session_data_t *data);

#endif // FLASH_H
