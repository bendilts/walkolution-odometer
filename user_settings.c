/**
 * User settings storage implementation
 */

#include "user_settings.h"
#include "logging.h"
#include "pico/stdlib.h"
#include "hardware/flash.h"
#include "hardware/sync.h"
#include <string.h>
#include <stdio.h>

// Flash storage configuration - use a dedicated sector for settings
// Place it just before the session data sectors
#define SETTINGS_FLASH_OFFSET (PICO_FLASH_SIZE_BYTES - (FLASH_SECTOR_SIZE * 65))
#define SETTINGS_MAGIC_NUMBER 0x53455454 // "SETT" in hex

// Current settings in RAM
static user_settings_t current_settings;
static bool settings_initialized = false;

// Forward declarations
static bool save_settings_to_flash(void);

static uint32_t calculate_checksum(const user_settings_t *settings)
{
    // XOR of all fields except checksum
    uint32_t checksum = settings->magic ^ settings->version;
    checksum ^= settings->metric ? 1 : 0;
    checksum ^= (uint32_t)settings->timezone_offset_seconds;
    return checksum;
}

static uint32_t calculate_checksum_v2(const user_settings_v2_t *settings)
{
    // XOR of all fields except checksum (v2 format - with WiFi and timezone)
    uint32_t checksum = settings->magic ^ settings->version;
    checksum ^= settings->metric ? 1 : 0;

    // XOR SSID bytes
    for (size_t i = 0; i < sizeof(settings->ssid); i++)
    {
        checksum ^= (uint32_t)(settings->ssid[i]) << ((i % 4) * 8);
    }

    // XOR password bytes
    for (size_t i = 0; i < sizeof(settings->wifi_password); i++)
    {
        checksum ^= (uint32_t)(settings->wifi_password[i]) << ((i % 4) * 8);
    }

    // XOR timezone offset
    checksum ^= (uint32_t)settings->timezone_offset_seconds;

    return checksum;
}

static uint32_t calculate_checksum_v1(const user_settings_v1_t *settings)
{
    // XOR of all fields except checksum (v1 format - no timezone)
    uint32_t checksum = settings->magic ^ settings->version;
    checksum ^= settings->metric ? 1 : 0;

    // XOR SSID bytes
    for (size_t i = 0; i < sizeof(settings->ssid); i++)
    {
        checksum ^= (uint32_t)(settings->ssid[i]) << ((i % 4) * 8);
    }

    // XOR password bytes
    for (size_t i = 0; i < sizeof(settings->wifi_password); i++)
    {
        checksum ^= (uint32_t)(settings->wifi_password[i]) << ((i % 4) * 8);
    }

    return checksum;
}

static bool load_settings_from_flash(void)
{
    const uint32_t *flash_ptr = (const uint32_t *)(XIP_BASE + SETTINGS_FLASH_OFFSET);
    uint32_t magic = flash_ptr[0];
    uint32_t version = flash_ptr[1];

    // Check magic number first
    if (magic != SETTINGS_MAGIC_NUMBER)
    {
        log_printf("[SETTINGS] No valid settings in flash (bad magic) - using defaults\n");
        return false;
    }

    // Handle different versions
    if (version == SETTINGS_VERSION)
    {
        // Current version (v3) - load directly
        const user_settings_t *flash_settings = (const user_settings_t *)(XIP_BASE + SETTINGS_FLASH_OFFSET);

        if (flash_settings->checksum != calculate_checksum(flash_settings))
        {
            log_printf("[SETTINGS] v%d settings checksum invalid - using defaults\n", SETTINGS_VERSION);
            return false;
        }

        // Valid v3 settings found - copy to RAM
        memcpy(&current_settings, flash_settings, sizeof(user_settings_t));
        log_printf("[SETTINGS] Loaded v%d settings from flash:\n", SETTINGS_VERSION);
        log_printf("  - Metric: %s\n", current_settings.metric ? "YES (km)" : "NO (miles)");
        log_printf("  - Timezone offset: %ld seconds (%.1f hours)\n",
               current_settings.timezone_offset_seconds,
               current_settings.timezone_offset_seconds / 3600.0f);
        return true;
    }
    else if (version == 2)
    {
        // Migrate v2 to v3 (remove WiFi fields)
        log_printf("[SETTINGS] Found v2 settings, migrating to v%d...\n", SETTINGS_VERSION);

        const user_settings_v2_t *v2_settings = (const user_settings_v2_t *)(XIP_BASE + SETTINGS_FLASH_OFFSET);

        // Validate v2 checksum
        if (v2_settings->checksum != calculate_checksum_v2(v2_settings))
        {
            log_printf("[SETTINGS] v2 settings checksum invalid - using defaults\n");
            return false;
        }

        // Migrate v2 fields to current settings (drop WiFi fields)
        memset(&current_settings, 0, sizeof(user_settings_t));
        current_settings.magic = SETTINGS_MAGIC_NUMBER;
        current_settings.version = SETTINGS_VERSION;
        current_settings.metric = v2_settings->metric;
        current_settings.timezone_offset_seconds = v2_settings->timezone_offset_seconds;

        log_printf("[SETTINGS] Migrated v2 settings:\n");
        log_printf("  - Metric: %s\n", current_settings.metric ? "YES (km)" : "NO (miles)");
        log_printf("  - Timezone offset: %ld seconds (%.1f hours)\n",
               current_settings.timezone_offset_seconds,
               current_settings.timezone_offset_seconds / 3600.0f);
        log_printf("  - WiFi settings removed\n");

        // Save migrated settings as v3
        save_settings_to_flash();
        log_printf("[SETTINGS] Migration complete, saved as v%d\n", SETTINGS_VERSION);

        return true;
    }
    else if (version == 1)
    {
        // Migrate v1 to v3 (drop WiFi fields)
        log_printf("[SETTINGS] Found v1 settings, migrating to v%d...\n", SETTINGS_VERSION);

        const user_settings_v1_t *v1_settings = (const user_settings_v1_t *)(XIP_BASE + SETTINGS_FLASH_OFFSET);

        // Validate v1 checksum
        if (v1_settings->checksum != calculate_checksum_v1(v1_settings))
        {
            log_printf("[SETTINGS] v1 settings checksum invalid - using defaults\n");
            return false;
        }

        // Migrate v1 fields to current settings (drop WiFi fields)
        memset(&current_settings, 0, sizeof(user_settings_t));
        current_settings.magic = SETTINGS_MAGIC_NUMBER;
        current_settings.version = SETTINGS_VERSION;
        current_settings.metric = v1_settings->metric;
        current_settings.timezone_offset_seconds = 0; // Default to UTC for migrated settings

        log_printf("[SETTINGS] Migrated v1 settings:\n");
        log_printf("  - Metric: %s\n", current_settings.metric ? "YES (km)" : "NO (miles)");
        log_printf("  - Timezone offset: %ld seconds (default UTC)\n", current_settings.timezone_offset_seconds);
        log_printf("  - WiFi settings removed\n");

        // Save migrated settings as v3
        save_settings_to_flash();
        log_printf("[SETTINGS] Migration complete, saved as v%d\n", SETTINGS_VERSION);

        return true;
    }
    else
    {
        log_printf("[SETTINGS] Unknown settings version %lu - using defaults\n", version);
        return false;
    }
}

static void create_default_settings(void)
{
    memset(&current_settings, 0, sizeof(user_settings_t));
    current_settings.magic = SETTINGS_MAGIC_NUMBER;
    current_settings.version = SETTINGS_VERSION;
    current_settings.metric = false; // Default to miles
    current_settings.timezone_offset_seconds = 0; // Default to UTC
    current_settings.checksum = calculate_checksum(&current_settings);

    log_printf("[SETTINGS] Created default v%d settings (miles, UTC timezone)\n", SETTINGS_VERSION);
}

static bool save_settings_to_flash(void)
{
    // Update checksum before saving
    current_settings.checksum = calculate_checksum(&current_settings);

    // Prepare aligned buffer for flash write
    static uint8_t __attribute__((aligned(FLASH_PAGE_SIZE))) write_buffer[FLASH_PAGE_SIZE];
    memset(write_buffer, 0, FLASH_PAGE_SIZE);
    memcpy(write_buffer, &current_settings, sizeof(user_settings_t));

    // Write to flash
    uint32_t ints = save_and_disable_interrupts();
    flash_range_erase(SETTINGS_FLASH_OFFSET, FLASH_SECTOR_SIZE);
    flash_range_program(SETTINGS_FLASH_OFFSET, write_buffer, FLASH_PAGE_SIZE);
    restore_interrupts(ints);

    log_printf("[SETTINGS] Saved to flash\n");
    return true;
}

void user_settings_init(void)
{
    if (settings_initialized)
    {
        return;
    }

    log_printf("[SETTINGS] Initializing...\n");

    // Try to load from flash
    if (!load_settings_from_flash())
    {
        // No valid settings - create defaults
        create_default_settings();

        // Save defaults to flash
        save_settings_to_flash();
    }

    settings_initialized = true;
}

const user_settings_t* user_settings_get(void)
{
    if (!settings_initialized)
    {
        user_settings_init();
    }

    return &current_settings;
}

bool user_settings_update(bool metric)
{
    if (!settings_initialized)
    {
        user_settings_init();
    }

    log_printf("[SETTINGS] Updating settings:\n");
    log_printf("  - Metric: %s\n", metric ? "YES (km)" : "NO (miles)");

    // Update settings in RAM
    current_settings.metric = metric;

    // Save to flash
    return save_settings_to_flash();
}

bool user_settings_is_metric(void)
{
    if (!settings_initialized)
    {
        user_settings_init();
    }

    return current_settings.metric;
}

int32_t user_settings_get_timezone_offset(void)
{
    if (!settings_initialized)
    {
        user_settings_init();
    }

    return current_settings.timezone_offset_seconds;
}

void user_settings_set_timezone_offset(int32_t offset_seconds)
{
    if (!settings_initialized)
    {
        user_settings_init();
    }

    // Only save if changed
    if (current_settings.timezone_offset_seconds != offset_seconds)
    {
        log_printf("[SETTINGS] Updating timezone offset: %ld seconds (%.1f hours)\n",
               offset_seconds, offset_seconds / 3600.0f);

        current_settings.timezone_offset_seconds = offset_seconds;
        save_settings_to_flash();
    }
}
