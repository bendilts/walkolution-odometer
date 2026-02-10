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
#define FLASH_STRUCT_VERSION 2         // Current struct version (increment when changing flash_data_t)

// Public session data structure - this is what callers work with
// Internal flash fields (magic, version, checksum) are handled by flash module
typedef struct
{
    uint32_t session_id;                  // Monotonically increasing session counter
    uint32_t write_index;                 // Globally incrementing write counter (determines sector, never resets)
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
 * Sector is determined automatically from data->write_index for wear-leveling
 *
 * @param data Pointer to session_data_t structure to write
 * @param operation_title Human-readable operation description for logging
 * @return true if write succeeded and verified, false otherwise
 */
bool flash_write(const session_data_t *data, const char *operation_title);

/**
 * Scan all flash sectors and build a deduplicated list of sessions
 * For each unique session_id, keeps only the entry with highest write_index
 *
 * This function scans all 64 sectors ONCE and deduplicates in a single pass,
 * avoiding repeated sector scans.
 *
 * @param sessions Array to fill with deduplicated session data (must hold at least FLASH_SECTOR_COUNT entries)
 * @param max_sessions Maximum size of sessions array (should be FLASH_SECTOR_COUNT)
 * @return Number of unique sessions found
 */
uint32_t flash_scan_all_sessions(session_data_t *sessions, uint32_t max_sessions);

/**
 * Find a specific session by session_id
 * Returns the entry with the highest write_index for the given session_id
 *
 * @param session_id The session ID to search for
 * @param data Pointer to session_data_t to fill if found
 * @return true if session found, false otherwise
 */
bool flash_find_session(uint32_t session_id, session_data_t *data);

#endif // FLASH_H
