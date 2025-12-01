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
#define SETTINGS_VERSION 2

// User settings structure (stored in flash)
// WARNING: Any changes to this structure require incrementing SETTINGS_VERSION above!
typedef struct __attribute__((packed))
{
    uint32_t magic;           // 0x53455454 ("SETT" in hex)
    uint32_t version;         // Current version (see SETTINGS_VERSION)
    bool metric;              // false=miles, true=kilometers
    char ssid[64];            // WiFi SSID (null-terminated)
    char wifi_password[128];  // WiFi password (null-terminated, WPA2 max 63 chars)
    int32_t timezone_offset_seconds; // Timezone offset in seconds from UTC (e.g., -28800 for PST, -25200 for PDT)
    uint32_t checksum;        // XOR checksum of all fields above
} user_settings_t;

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

// WiFi validation status
typedef enum
{
    WIFI_VALIDATION_IDLE = 0,
    WIFI_VALIDATION_TESTING = 1,
    WIFI_VALIDATION_SUCCESS = 2,
    WIFI_VALIDATION_FAILED = 3
} wifi_validation_status_t;

// Initialize settings subsystem (loads from flash or creates defaults)
void user_settings_init(void);

// Get current settings (read-only pointer)
const user_settings_t* user_settings_get(void);

// Update settings and save to flash
// Returns true if saved successfully
bool user_settings_update(bool metric, const char *ssid, const char *wifi_password);

// Get WiFi credentials for connection
void user_settings_get_wifi_credentials(char *ssid_out, size_t ssid_size,
                                       char *password_out, size_t password_size);

// Get current metric setting
bool user_settings_is_metric(void);

// Get timezone offset in seconds (e.g., -28800 for PST)
int32_t user_settings_get_timezone_offset(void);

// Set timezone offset in seconds and save to flash if changed
void user_settings_set_timezone_offset(int32_t offset_seconds);

// Set WiFi validation status (used during testing)
void user_settings_set_validation_status(wifi_validation_status_t status);

// Get WiFi validation status
wifi_validation_status_t user_settings_get_validation_status(void);

#endif // USER_SETTINGS_H
