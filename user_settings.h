/**
 * User settings storage in flash
 */

#ifndef USER_SETTINGS_H
#define USER_SETTINGS_H

#include <stdint.h>
#include <stdbool.h>
#include <stddef.h>

// IMPORTANT: When modifying this structure, increment SETTINGS_VERSION and add migration code in load_settings_from_flash()
// Version history:
//   v1: Initial version (metric, ssid, wifi_password)
//   v2: Added timezone_offset_seconds
//   v3: Removed WiFi support (ssid, wifi_password)
#define SETTINGS_VERSION 3

// User settings structure (stored in flash)
// WARNING: Any changes to this structure require incrementing SETTINGS_VERSION above!
typedef struct __attribute__((packed))
{
    uint32_t magic;           // 0x53455454 ("SETT" in hex)
    uint32_t version;         // Current version (see SETTINGS_VERSION)
    bool metric;              // false=miles, true=kilometers
    int32_t timezone_offset_seconds; // Timezone offset in seconds from UTC (e.g., -28800 for PST, -25200 for PDT)
    uint32_t checksum;        // XOR checksum of all fields above
} user_settings_t;

// Version 2 structure (for backward compatibility)
typedef struct __attribute__((packed))
{
    uint32_t magic;
    uint32_t version;
    bool metric;
    char ssid[64];
    char wifi_password[128];
    int32_t timezone_offset_seconds;
    uint32_t checksum;
} user_settings_v2_t;

// Version 1 structure (for backward compatibility)
typedef struct __attribute__((packed))
{
    uint32_t magic;
    uint32_t version;
    bool metric;
    char ssid[64];
    char wifi_password[128];
    uint32_t checksum;
} user_settings_v1_t;

// Initialize settings subsystem (loads from flash or creates defaults)
void user_settings_init(void);

// Get current settings (read-only pointer)
const user_settings_t* user_settings_get(void);

// Update settings and save to flash
// Returns true if saved successfully
bool user_settings_update(bool metric);

// Get current metric setting
bool user_settings_is_metric(void);

// Get timezone offset in seconds (e.g., -28800 for PST)
int32_t user_settings_get_timezone_offset(void);

// Set timezone offset in seconds and save to flash if changed
void user_settings_set_timezone_offset(int32_t offset_seconds);

#endif // USER_SETTINGS_H
