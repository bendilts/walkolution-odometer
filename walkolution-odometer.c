/**
 * Copyright (c) 2020 Raspberry Pi (Trading) Ltd.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */

#include "pico/stdlib.h"
#include "hardware/i2c.h"
#include "hardware/clocks.h"
#include "odometer.h"
#include "oled.h"
#include "font.h"
#include "icons.h"
#include "user_settings.h"
#include "logging.h"
#include <string.h>
#include <stdio.h>

// Pico W devices use a GPIO on the WIFI chip for the LED,
// so when building for Pico W, CYW43_WL_GPIO_LED_PIN will be defined
#ifdef CYW43_WL_GPIO_LED_PIN
#include "pico/cyw43_arch.h"
#include "pico/btstack_cyw43.h"
#include "btstack.h"
#include "walkolution-odometer.h" // Generated GATT database
#endif

#ifndef SENSOR_PIN
#define SENSOR_PIN 21
#endif

#ifndef POLL_DELAY_MS
// Polling interval calculated for 8 mph with 3 polls per revolution:
// - 8 mph = 37,260 rotations/hour = 10.35 rot/sec = 96.6 ms/rotation
// - 3 polls per rotation = 96.6 ms / 3 = 32.2 ms
// - Using 30 ms for safety margin (~3.2 polls per rotation at 8 mph)
#define POLL_DELAY_MS 30
#endif

// Debug mode: simulate rotations at ~2 MPH
#define DEBUG_FAKE_ROTATIONS 0 // Set to 1 to enable

#if DEBUG_FAKE_ROTATIONS
// Calculate rotation interval for 2 MPH:
// 2 mph * 1609.344 m/mi = 3218.688 m/hr
// 3218.688 m * 100 cm/m = 321868.8 cm/hr
// 321868.8 cm/hr / 34.56 cm/rotation = 9313 rotations/hr
// 9313 rotations/hr / 3600 s/hr = 2.587 rotations/s
// 1 rotation every 386.5 ms
#define DEBUG_ROTATION_INTERVAL_MS 1000 /* 387 */
#endif

#ifndef OLED_UPDATE_INTERVAL_MS
#define OLED_UPDATE_INTERVAL_MS 1000 // 1 second update rate for power savings
#endif

#ifndef DISPLAY_SWITCH_INTERVAL_MS
#define DISPLAY_SWITCH_INTERVAL_MS 5000
#endif

// OLED I2C configuration
#define OLED_I2C_PORT i2c1
#define OLED_SDA_PIN 26
#define OLED_SCL_PIN 27
#define OLED_ADDR 0x3C

#ifndef VOLTAGE_CHECK_INTERVAL_MS
#define VOLTAGE_CHECK_INTERVAL_MS 1000
#endif

#define OLED_VOLTAGE_OFF_THRESHOLD_MV 3000 // 3.0V - turn off OLED below this
#define OLED_VOLTAGE_ON_THRESHOLD_MV 3500  // 3.5V - turn on OLED above this (hysteresis prevents flickering)
#define BLE_VOLTAGE_THRESHOLD_MV 4200      // 4.2V minimum for Bluetooth
#define BLE_UPDATE_INTERVAL_MS 1000        // Send data to phone every second
#define SPEED_WINDOW_SECONDS 5             // 5-second running average for speed

// Perform initialisation
int pico_led_init(void)
{
#if defined(PICO_DEFAULT_LED_PIN)
    // A device like Pico that uses a GPIO for the LED will define PICO_DEFAULT_LED_PIN
    // so we can use normal GPIO functionality to turn the led on and off
    gpio_init(PICO_DEFAULT_LED_PIN);
    gpio_set_dir(PICO_DEFAULT_LED_PIN, GPIO_OUT);
    return PICO_OK;
#elif defined(CYW43_WL_GPIO_LED_PIN)
    // For Pico W devices we need to initialise the driver etc
    return cyw43_arch_init();
#endif
}

// Turn the led on or off
void pico_set_led(bool led_on)
{
#if defined(PICO_DEFAULT_LED_PIN)
    // Just set the GPIO on or off
    gpio_put(PICO_DEFAULT_LED_PIN, led_on);
#elif defined(CYW43_WL_GPIO_LED_PIN)
    // Ask the wifi "driver" to set the GPIO on or off
    cyw43_arch_gpio_put(CYW43_WL_GPIO_LED_PIN, led_on);
#endif
}

// Convert rotations to distance
// Each rotation = 34.56 cm = 0.3456 meters = 0.0002147 miles = 0.0003456 km
#define CM_PER_ROTATION 34.56f
#define METERS_PER_MILE 1609.344f
#define METERS_PER_KM 1000.0f
#define MILES_PER_ROTATION (CM_PER_ROTATION / 100.0f / METERS_PER_MILE)
#define KM_PER_ROTATION (CM_PER_ROTATION / 100.0f / METERS_PER_KM)

float rotations_to_distance(uint32_t rotations, bool metric)
{
    if (metric)
    {
        return (float)rotations * KM_PER_ROTATION;
    }
    else
    {
        return (float)rotations * MILES_PER_ROTATION;
    }
}

// Legacy function for compatibility
float rotations_to_miles(uint32_t rotations)
{
    return (float)rotations * MILES_PER_ROTATION;
}

// Get current clock time as string (12-hour format with AM/PM)
void get_clock_time_str(char *buffer, size_t size)
{
    if (!odometer_has_time())
    {
        buffer[0] = '\0'; // No time available
        return;
    }

    // Get current Unix timestamp (UTC)
    uint32_t current_boot_ms = to_ms_since_boot(get_absolute_time());
    uint32_t unix_time = odometer_get_current_unix_time();

    if (unix_time == 0)
    {
        buffer[0] = '\0';
        return;
    }

    // Apply timezone offset to convert from UTC to local time
    int32_t timezone_offset = user_settings_get_timezone_offset();
    int64_t local_time = (int64_t)unix_time + (int64_t)timezone_offset;

    // Handle day wraparound
    if (local_time < 0)
    {
        local_time += 86400;
    }

    // Convert to hours and minutes
    uint32_t seconds_since_midnight = (uint32_t)(local_time % 86400); // Seconds in a day
    uint32_t hours = seconds_since_midnight / 3600;
    uint32_t minutes = (seconds_since_midnight % 3600) / 60;

    // Convert to 12-hour format
    const char *am_pm = (hours >= 12) ? "PM" : "AM";
    if (hours == 0)
        hours = 12;
    else if (hours > 12)
        hours -= 12;

    snprintf(buffer, size, "%lu:%02lu %s", hours, minutes, am_pm);
}

// Forward declarations
static float calculate_running_avg_speed(void);

// Draw common status bar at bottom of display
// Shows: Clock (left) | Voltage (center) | Connection icon (right)
static void draw_status_bar(bool ble_connected, bool ble_advertising)
{
    // Get voltage
    uint16_t voltage_mv = odometer_read_voltage();
    float voltage_v = voltage_mv / 1000.0f;
    char voltage_str[16];
    snprintf(voltage_str, sizeof(voltage_str), "%.1fV", voltage_v);

    // Get clock time
    char clock_str[16];
    get_clock_time_str(clock_str, sizeof(clock_str));

    const int SEPARATOR_Y = 51;
    const int TEXT_BASELINE_Y = 63;
    oled_fill_rect(0, SEPARATOR_Y, OLED_WIDTH, 1, true);

    // Clock on left (if available)
    if (clock_str[0] != '\0')
    {
        oled_draw_text(1, TEXT_BASELINE_Y, clock_str, &Font5x7Fixed);
    }

    // Voltage centered
    oled_draw_text_centered(OLED_WIDTH / 2, TEXT_BASELINE_Y, voltage_str, &Font5x7Fixed);

    // Connection icon on right
    if (ble_connected)
    {
        oled_draw_bitmap(OLED_WIDTH - icon_bluetooth.width, OLED_HEIGHT - icon_bluetooth.height,
                         icon_bluetooth.bitmap, icon_bluetooth.width, icon_bluetooth.height);
    }
    else if (ble_advertising)
    {
        // BLE advertising - flash icon every 250ms
        uint32_t current_time_ms = to_ms_since_boot(get_absolute_time());
        bool show_icon = ((current_time_ms / 250) % 2) == 0;
        if (show_icon)
        {
            oled_draw_bitmap(OLED_WIDTH - icon_bluetooth.width, OLED_HEIGHT - icon_bluetooth.height,
                             icon_bluetooth.bitmap, icon_bluetooth.width, icon_bluetooth.height);
        }
    }
}

void update_oled_session(bool ble_connected_state, bool ble_advertising_state)
{
    uint32_t session = odometer_get_session_count();
    uint32_t session_time = odometer_get_session_active_time_seconds();

    // Get metric setting
    bool metric = user_settings_is_metric();

    // Convert to distance (miles or km)
    float session_distance = rotations_to_distance(session, metric);
    const char *unit = metric ? "km" : "mi";

    // Format distance with appropriate precision
    char distance_str[32];
    if (session_distance >= 10.0f)
    {
        snprintf(distance_str, sizeof(distance_str), "%.1f %s", session_distance, unit);
    }
    else
    {
        snprintf(distance_str, sizeof(distance_str), "%.2f %s", session_distance, unit);
    }

    // Format clock time
    char clock_str[16];
    get_clock_time_str(clock_str, sizeof(clock_str));

    // Format session time (HH:MM:SS or MM:SS)
    char session_time_str[16];
    if (session_time >= 3600)
    {
        uint32_t hours = session_time / 3600;
        uint32_t minutes = (session_time % 3600) / 60;
        uint32_t seconds = session_time % 60;
        snprintf(session_time_str, sizeof(session_time_str), "%lu:%02lu:%02lu", hours, minutes, seconds);
    }
    else
    {
        uint32_t minutes = session_time / 60;
        uint32_t seconds = session_time % 60;
        snprintf(session_time_str, sizeof(session_time_str), "%lu:%02lu", minutes, seconds);
    }

    // Clear display
    oled_clear();

    // Layout: Main content area (0-52), separator (53), status bar (54-63)
    // Display height: 64px, Font5x7Fixed height: ~8px

    // SESSION CONTENT AREA: Distance (large) and session time (small)

    // Large centered distance (FreeSans18pt, baseline at y=24)
    oled_draw_text_centered(OLED_WIDTH / 2, 24, distance_str, &FreeSans18pt7b);

    // Centered session time below (FreeSans9pt7b, baseline at y=42)
    oled_draw_text_centered(OLED_WIDTH / 2, 42, session_time_str, &FreeSans9pt7b);

    // Draw status bar (clock, voltage, connection icon)
    draw_status_bar(ble_connected_state, ble_advertising_state);

    // Request display update
    oled_update();
}

void update_oled_totals(bool ble_connected_state, bool ble_advertising_state)
{
    uint32_t total = odometer_get_count();
    uint32_t total_time = odometer_get_active_time_seconds();

    // Get metric setting
    bool metric = user_settings_is_metric();

    // Convert to distance (miles or km)
    float total_distance = rotations_to_distance(total, metric);
    const char *unit = metric ? "km" : "mi";

    // Convert to hours
    uint32_t total_hours = total_time / 3600;

    // Get voltage
    uint16_t voltage_mv = odometer_read_voltage();
    float voltage_v = voltage_mv / 1000.0f;

    // Format strings
    char distance_str[32];
    if (total_distance >= 100.0f)
    {
        snprintf(distance_str, sizeof(distance_str), "%.0f %s", total_distance, unit);
    }
    else if (total_distance >= 10.0f)
    {
        snprintf(distance_str, sizeof(distance_str), "%.1f %s", total_distance, unit);
    }
    else
    {
        snprintf(distance_str, sizeof(distance_str), "%.2f %s", total_distance, unit);
    }

    // Format totals with "total" label
    char distance_total_str[32];
    if (total_distance >= 100.0f)
    {
        snprintf(distance_total_str, sizeof(distance_total_str), "%.0f total %s", total_distance, unit);
    }
    else if (total_distance >= 10.0f)
    {
        snprintf(distance_total_str, sizeof(distance_total_str), "%.1f total %s", total_distance, unit);
    }
    else
    {
        snprintf(distance_total_str, sizeof(distance_total_str), "%.2f total %s", total_distance, unit);
    }

    char hours_total_str[32];
    snprintf(hours_total_str, sizeof(hours_total_str), "%lu total hr", total_hours);

    // Format clock time for status bar
    char clock_str[16];
    get_clock_time_str(clock_str, sizeof(clock_str));

    char voltage_str[16];
    snprintf(voltage_str, sizeof(voltage_str), "%.1fV", voltage_v);

    // Clear display
    oled_clear();

    // Layout: Main content area (0-52), separator (53), status bar (54-63)
    // Display height: 64px, FreeSans12pt y_advance: 29px

    // TOTALS CONTENT AREA: Distance and hours (both medium, centered)

    // Total distance centered (FreeSans12pt, baseline at y=18)
    oled_draw_text_centered(OLED_WIDTH / 2, 18, distance_total_str, &FreeSans12pt7b);

    // Total hours centered below (FreeSans12pt, baseline at y=42)
    oled_draw_text_centered(OLED_WIDTH / 2, 42, hours_total_str, &FreeSans12pt7b);

    // Draw status bar (clock, voltage, connection icon)
    draw_status_bar(ble_connected_state, ble_advertising_state);

    // Request display update
    oled_update();
}

// Bluetooth LE state
static bool ble_advertising = false;
static bool ble_connected = false;
static bool ble_notification_enabled = false;
static hci_con_handle_t connection_handle;
// Note: odometer_characteristic_handle is defined in the generated header as:
// ATT_CHARACTERISTIC_12345678_1234_5678_1234_56789ABCDEF1_01_VALUE_HANDLE

// BLE activation delay tracking (requires voltage to be stable for 15 seconds)
#define BLE_ACTIVATION_DELAY_MS 15000  // 15 seconds
static uint32_t ble_voltage_above_threshold_start_ms = 0;  // When voltage first went above threshold
static bool ble_voltage_is_above_threshold = false;        // Current voltage state

// Connection status is now integrated into update_oled_session() and update_oled_totals()

// Speed calculation
typedef struct
{
    uint32_t rotations[SPEED_WINDOW_SECONDS];
    uint32_t timestamps_ms[SPEED_WINDOW_SECONDS];
    int index;
    bool filled;
} speed_window_t;

static speed_window_t speed_window = {0};

// Define custom UUIDs for our service
// Service UUID: 12345678-1234-5678-1234-56789abcdef0
static const uint8_t odometer_service_uuid[] = {0xf0, 0xde, 0xbc, 0x9a, 0x78, 0x56, 0x34, 0x12, 0x78, 0x56, 0x34, 0x12, 0x78, 0x56, 0x34, 0x12};
// Characteristic UUID: 12345678-1234-5678-1234-56789abcdef1
static const uint8_t odometer_characteristic_uuid[] = {0xf1, 0xde, 0xbc, 0x9a, 0x78, 0x56, 0x34, 0x12, 0x78, 0x56, 0x34, 0x12, 0x78, 0x56, 0x34, 0x12};

// Data packet structure (65 bytes total)
typedef struct __attribute__((packed))
{
    uint32_t session_rotations;    // 4 bytes
    uint32_t total_rotations;      // 4 bytes
    uint32_t session_time_seconds; // 4 bytes
    uint32_t total_time_seconds;   // 4 bytes
    float running_avg_speed;       // 4 bytes (mph or km/h depending on metric setting)
    float session_avg_speed;       // 4 bytes (mph or km/h depending on metric setting)
    uint32_t voltage_mv;           // 4 bytes (millivolts)
    uint32_t session_id;           // 4 bytes (current session ID, 0 if not decided)
    uint8_t metric;                // 1 byte (0=miles, 1=km)
} odometer_data_t;

// Bluetooth LE advertisement data - minimal, just flags (3 bytes)
// Note: BTstack on Pico W has issues transmitting advertisement data properly,
// but scan response data works correctly. So we put all discoverable data
// (name and UUID) in the scan response instead.
static const uint8_t adv_data[] = {
    // Flags general discoverable, BR/EDR not supported
    0x02,
    BLUETOOTH_DATA_TYPE_FLAGS,
    0x06,
};
static const uint8_t adv_data_len = sizeof(adv_data);

// Scan response data with shortened name AND UUID (27 bytes total)
// This is sent when Android actively scans (which it always does)
static const uint8_t scan_rsp_data[] = {
    // Shortened name: "Walk Odo" (9 bytes with header)
    0x09,
    BLUETOOTH_DATA_TYPE_SHORTENED_LOCAL_NAME,
    'W',
    'a',
    'l',
    'k',
    ' ',
    'O',
    'd',
    'o',
    // 128-bit Service UUID (18 bytes with header)
    0x11,
    BLUETOOTH_DATA_TYPE_COMPLETE_LIST_OF_128_BIT_SERVICE_CLASS_UUIDS,
    0xF0,
    0xDE,
    0xBC,
    0x9A,
    0x78,
    0x56,
    0x34,
    0x12,
    0x78,
    0x56,
    0x34,
    0x12,
    0x78,
    0x56,
    0x34,
    0x12,
};
static const uint8_t scan_rsp_data_len = sizeof(scan_rsp_data);

// Forward declarations
static float calculate_running_avg_speed(void);
static float calculate_session_avg_speed(void);

// Packet handler for HCI and GAP events
static void packet_handler(uint8_t packet_type, uint16_t channel, uint8_t *packet, uint16_t size)
{
    UNUSED(channel);
    UNUSED(size);

    if (packet_type != HCI_EVENT_PACKET)
        return;

    switch (hci_event_packet_get_type(packet))
    {
    case BTSTACK_EVENT_STATE:
        if (btstack_event_state_get_state(packet) == HCI_STATE_WORKING)
        {
            log_printf("BTstack initialized\n");
        }
        break;

    case HCI_EVENT_DISCONNECTION_COMPLETE:
        ble_connected = false;
        ble_notification_enabled = false;
        log_printf("[BLE] Disconnected (reason=0x%02x)\n", hci_event_disconnection_complete_get_reason(packet));
        log_printf("  - Time acquired before disconnect: %s\n", odometer_has_time() ? "YES" : "NO");
        break;

    case HCI_EVENT_LE_META:
        switch (hci_event_le_meta_get_subevent_code(packet))
        {
        case HCI_SUBEVENT_LE_CONNECTION_COMPLETE:
            connection_handle = hci_subevent_le_connection_complete_get_connection_handle(packet);
            ble_connected = true;
            log_printf("[BLE] *** CONNECTED! ***\n");
            log_printf("  - Handle: 0x%04x\n", connection_handle);
            log_printf("  - Time already acquired: %s\n", odometer_has_time() ? "YES" : "NO");
            log_printf("  - Waiting for Android app to send time sync...\n");
            break;
        default:
            log_printf("[BLE] LE Meta event: subevent=0x%02x\n", hci_event_le_meta_get_subevent_code(packet));
            break;
        }
        break;

    case ATT_EVENT_CAN_SEND_NOW:
        // Send notification when BTstack is ready
        // log_printf("ATT_EVENT_CAN_SEND_NOW: connected=%d, notify_enabled=%d\n", ble_connected, ble_notification_enabled);
        if (ble_connected && ble_notification_enabled)
        {
            odometer_data_t data;
            data.session_rotations = odometer_get_session_count();
            data.total_rotations = odometer_get_count();
            data.session_time_seconds = odometer_get_session_active_time_seconds();
            data.total_time_seconds = odometer_get_active_time_seconds();
            data.running_avg_speed = calculate_running_avg_speed();
            data.session_avg_speed = calculate_session_avg_speed();
            data.voltage_mv = odometer_read_voltage();
            data.session_id = odometer_get_current_session_id();

            // Add current settings to data packet
            const user_settings_t *settings = user_settings_get();
            data.metric = settings->metric ? 1 : 0;

            int result = att_server_notify(connection_handle, ATT_CHARACTERISTIC_12345678_1234_5678_1234_56789ABCDEF1_01_VALUE_HANDLE, (uint8_t *)&data, sizeof(data));
            // log_printf("Sent notification: result=%d, sess_rot=%lu, total_rot=%lu, speed=%.2f, voltage=%lu mV, session_id=%lu\n",
            //            result, data.session_rotations, data.total_rotations, data.running_avg_speed, data.voltage_mv, data.session_id);
        }
        break;
    }
}

void start_ble_advertising(void)
{
    if (ble_advertising)
        return;

    bd_addr_t null_addr;
    memset(null_addr, 0, 6);

    // Setup advertisement
    uint16_t adv_int_min = 0x0030; // 30ms
    uint16_t adv_int_max = 0x0030; // 30ms
    uint8_t adv_type = 0;          // ADV_IND
    gap_advertisements_set_params(adv_int_min, adv_int_max, adv_type, 0, null_addr, 0x07, 0x00);
    gap_advertisements_set_data(adv_data_len, (uint8_t *)adv_data);
    gap_scan_response_set_data(scan_rsp_data_len, (uint8_t *)scan_rsp_data);
    gap_advertisements_enable(1);

    ble_advertising = true;
    log_printf("BLE advertising started\n");
}

// Reset speed window (called when starting a new session)
void odometer_reset_speed_window(void)
{
    memset(&speed_window, 0, sizeof(speed_window));
    log_printf("[SPEED] Speed window reset (starting new session)\n");
}

// Update speed window with new rotation
void update_speed_window(uint32_t current_time_ms)
{
    // Use session rotations for speed calculation
    uint32_t current_rotations = odometer_get_session_count();

    speed_window.rotations[speed_window.index] = current_rotations;
    speed_window.timestamps_ms[speed_window.index] = current_time_ms;

    speed_window.index = (speed_window.index + 1) % SPEED_WINDOW_SECONDS;
    if (speed_window.index == 0)
    {
        speed_window.filled = true;
    }
}

// Calculate 5-second running average speed in mph or km/h (based on metric setting)
float calculate_running_avg_speed(void)
{
    if (!speed_window.filled && speed_window.index < 2)
    {
        return 0.0f; // Need at least 2 data points
    }

    int oldest_index = speed_window.filled ? speed_window.index : 0;
    int newest_index = speed_window.filled ? ((speed_window.index - 1 + SPEED_WINDOW_SECONDS) % SPEED_WINDOW_SECONDS) : (speed_window.index - 1);

    uint32_t rotation_diff = speed_window.rotations[newest_index] - speed_window.rotations[oldest_index];
    uint32_t time_diff_ms = speed_window.timestamps_ms[newest_index] - speed_window.timestamps_ms[oldest_index];

    if (time_diff_ms == 0)
    {
        return 0.0f;
    }

    // Convert to rotations per hour
    float rotations_per_ms = (float)rotation_diff / (float)time_diff_ms;
    float rotations_per_hour = rotations_per_ms * 3600000.0f;

    // Convert to mph or km/h based on metric setting
    bool metric = user_settings_is_metric();
    return rotations_per_hour * (metric ? KM_PER_ROTATION : MILES_PER_ROTATION);
}

// Calculate session average speed in mph or km/h (based on metric setting)
// Note: This uses session rotations/time which may include merged sessions.
// This is intentional - we want the average speed for the entire session (including merged data).
float calculate_session_avg_speed(void)
{
    uint32_t session_time = odometer_get_session_active_time_seconds();
    if (session_time == 0)
    {
        return 0.0f;
    }

    uint32_t session_rotations = odometer_get_session_count();

    // Convert to rotations per hour
    float rotations_per_second = (float)session_rotations / (float)session_time;
    float rotations_per_hour = rotations_per_second * 3600.0f;

    // Convert to mph or km/h based on metric setting
    bool metric = user_settings_is_metric();
    return rotations_per_hour * (metric ? KM_PER_ROTATION : MILES_PER_ROTATION);
}

// GATT database is now generated from walkolution-odometer.gatt
// The profile_data array and characteristic handles are defined in the generated header

// ATT Read callback
static uint16_t att_read_callback(hci_con_handle_t con_handle, uint16_t att_handle, uint16_t offset, uint8_t *buffer, uint16_t buffer_size)
{
    UNUSED(con_handle);

    // Main odometer data characteristic
    if (att_handle == ATT_CHARACTERISTIC_12345678_1234_5678_1234_56789ABCDEF1_01_VALUE_HANDLE)
    {
        odometer_data_t data;
        data.session_rotations = odometer_get_session_count();
        data.total_rotations = odometer_get_count();
        data.session_time_seconds = odometer_get_session_active_time_seconds();
        data.total_time_seconds = odometer_get_active_time_seconds();
        data.running_avg_speed = calculate_running_avg_speed();
        data.session_avg_speed = calculate_session_avg_speed();
        data.voltage_mv = odometer_read_voltage();
        data.session_id = odometer_get_current_session_id();

        // Add current settings to data packet
        const user_settings_t *settings = user_settings_get();
        data.metric = settings->metric ? 1 : 0;

        return att_read_callback_handle_blob((uint8_t *)&data, sizeof(data), offset, buffer, buffer_size);
    }

    // Sessions list characteristic
    if (att_handle == ATT_CHARACTERISTIC_12345678_1234_5678_1234_56789ABCDEF2_01_VALUE_HANDLE)
    {
        // Read unreported sessions from flash
        static session_record_t sessions[64]; // Max 64 sessions
        uint32_t session_count = odometer_get_unreported_sessions(sessions, 64);
        uint32_t data_size = session_count * sizeof(session_record_t);

        log_printf("Reading sessions list: %lu unreported sessions, %lu bytes\n", session_count, data_size);

        return att_read_callback_handle_blob((uint8_t *)sessions, data_size, offset, buffer, buffer_size);
    }

    // User settings characteristic
    if (att_handle == ATT_CHARACTERISTIC_12345678_1234_5678_1234_56789ABCDEF5_01_VALUE_HANDLE)
    {
        // Return current settings (1 byte: metric only, no WiFi)
        static uint8_t settings_buffer[1];
        memset(settings_buffer, 0, sizeof(settings_buffer));

        const user_settings_t *settings = user_settings_get();
        settings_buffer[0] = settings->metric ? 1 : 0;

        log_printf("Reading user settings: metric=%d\n", settings_buffer[0]);

        return att_read_callback_handle_blob(settings_buffer, sizeof(settings_buffer), offset, buffer, buffer_size);
    }

    // Logs characteristic
    if (att_handle == ATT_CHARACTERISTIC_12345678_1234_5678_1234_56789ABCDEF8_01_VALUE_HANDLE)
    {
        // Read new logs from circular buffer (limited by buffer_size which respects MTU)
        // BLE MTU is typically 185 bytes, minus 3 bytes overhead = 182 bytes max payload
        // buffer_size is provided by BTstack and already accounts for MTU
        static uint8_t log_buffer[182]; // Max one MTU worth of logs per read
        size_t max_read = (buffer_size < sizeof(log_buffer)) ? buffer_size : sizeof(log_buffer);
        size_t bytes_read = logging_get_new_logs((char *)log_buffer, max_read);

        // Note: It's okay to return 0 bytes if no new logs available
        // Don't log here - it would create a feedback loop!
        return att_read_callback_handle_blob(log_buffer, bytes_read, offset, buffer, buffer_size);
    }

    return 0;
}

// ATT Write callback - handles client characteristic configuration (notification enable/disable) and mark session reported
static int att_write_callback(hci_con_handle_t con_handle, uint16_t att_handle, uint16_t transaction_mode, uint16_t offset, uint8_t *buffer, uint16_t buffer_size)
{
    UNUSED(transaction_mode);
    UNUSED(offset);

    log_printf("ATT write: handle=0x%04x, size=%u\n", att_handle, buffer_size);

    // CCCD for main odometer data characteristic
    if (att_handle == ATT_CHARACTERISTIC_12345678_1234_5678_1234_56789ABCDEF1_01_CLIENT_CONFIGURATION_HANDLE)
    {
        uint16_t config_value = little_endian_read_16(buffer, 0);
        ble_notification_enabled = (config_value == GATT_CLIENT_CHARACTERISTICS_CONFIGURATION_NOTIFICATION);
        connection_handle = con_handle;
        log_printf("CCCD write: value=0x%04x, notifications %s (handle=0x%04x)\n",
                   config_value, ble_notification_enabled ? "ENABLED" : "disabled", connection_handle);
    }

    // Mark session reported characteristic
    if (att_handle == ATT_CHARACTERISTIC_12345678_1234_5678_1234_56789ABCDEF3_01_VALUE_HANDLE)
    {
        if (buffer_size == 4)
        {
            // Read session_id (little-endian uint32_t)
            uint32_t session_id = little_endian_read_32(buffer, 0);
            log_printf("Mark session reported: session_id=%lu\n", session_id);

            // Mark the session as reported
            bool success = odometer_mark_session_reported(session_id);
            if (success)
            {
                log_printf("Session %lu marked as reported successfully\n", session_id);
            }
            else
            {
                log_printf("Failed to mark session %lu as reported (not found)\n", session_id);
            }
        }
        else
        {
            log_printf("Invalid write size for mark reported: %u bytes (expected 4)\n", buffer_size);
        }
    }

    // Time sync characteristic (now includes timezone offset)
    if (att_handle == ATT_CHARACTERISTIC_12345678_1234_5678_1234_56789ABCDEF4_01_VALUE_HANDLE)
    {
        if (buffer_size == 4)
        {
            // Backward compatibility: 4 bytes = timestamp only (assume UTC)
            uint32_t unix_timestamp = little_endian_read_32(buffer, 0);
            log_printf("[BLE] Time sync received from Android app (old format - UTC assumed)!\n");
            log_printf("  - Raw timestamp: %lu\n", unix_timestamp);

            // Set the time reference
            odometer_set_time_reference(unix_timestamp);

            // Validate the timestamp looks reasonable
            if (unix_timestamp > 1700000000 && unix_timestamp < 2000000000)
            {
                log_printf("[BLE] Time sync SUCCESS - timestamp looks valid\n");
            }
            else
            {
                log_printf("[BLE] WARNING: Timestamp may be invalid (expected 2023-2033 range)\n");
            }
        }
        else if (buffer_size == 8)
        {
            // New format: 4 bytes timestamp + 4 bytes timezone offset
            uint32_t unix_timestamp = little_endian_read_32(buffer, 0);
            int32_t timezone_offset = (int32_t)little_endian_read_32(buffer, 4);

            log_printf("[BLE] Time sync received from Android app (with timezone)!\n");
            log_printf("  - UTC timestamp: %lu\n", unix_timestamp);
            log_printf("  - Timezone offset: %ld seconds (%.1f hours)\n", timezone_offset, timezone_offset / 3600.0f);

            // Set the time reference (still in UTC)
            odometer_set_time_reference(unix_timestamp);

            // Update timezone offset in settings if changed
            user_settings_set_timezone_offset(timezone_offset);

            // Validate the timestamp looks reasonable
            if (unix_timestamp > 1700000000 && unix_timestamp < 2000000000)
            {
                log_printf("[BLE] Time sync SUCCESS - timestamp looks valid\n");
            }
            else
            {
                log_printf("[BLE] WARNING: Timestamp may be invalid (expected 2023-2033 range)\n");
            }
        }
        else
        {
            log_printf("[BLE] ERROR: Invalid write size for time sync: %u bytes (expected 4 or 8)\n", buffer_size);
        }
    }

    // User settings characteristic
    if (att_handle == ATT_CHARACTERISTIC_12345678_1234_5678_1234_56789ABCDEF5_01_VALUE_HANDLE)
    {
        if (buffer_size == 1)
        {
            // Parse settings (1 byte metric only, no WiFi)
            bool metric = (buffer[0] != 0);

            log_printf("[BLE] Settings write received:\n");
            log_printf("  - Metric: %s\n", metric ? "YES (km)" : "NO (miles)");

            user_settings_update(metric);
        }
        else
        {
            log_printf("[BLE] ERROR: Invalid write size for settings: %u bytes (expected 1)\n", buffer_size);
        }
    }

    // Set lifetime totals characteristic
    if (att_handle == ATT_CHARACTERISTIC_12345678_1234_5678_1234_56789ABCDEF7_01_VALUE_HANDLE)
    {
        if (buffer_size == 8)
        {
            // Parse 8 bytes: 4 bytes hours (float) + 4 bytes distance (float)
            float hours, distance;
            memcpy(&hours, &buffer[0], 4);
            memcpy(&distance, &buffer[4], 4);

            log_printf("[BLE] Set lifetime totals received:\n");
            log_printf("  - Hours: %.2f\n", hours);
            log_printf("  - Distance: %.2f miles\n", distance);

            // Get current metric setting to determine if we need to convert
            const user_settings_t *settings = user_settings_get();
            bool metric = settings->metric;

            // If metric mode, convert km to miles for internal storage
            float distance_miles = distance;
            if (metric)
            {
                // Convert km to miles (1 km = 0.621371 miles)
                distance_miles = distance * 0.621371f;
                log_printf("  - Converted from %.2f km to %.2f miles\n", distance, distance_miles);
            }

            // Set the lifetime totals
            odometer_set_lifetime_totals(hours, distance_miles);
        }
        else
        {
            log_printf("[BLE] ERROR: Invalid write size for set lifetime totals: %u bytes (expected 8)\n", buffer_size);
        }
    }

    return 0;
}

void send_odometer_data(void)
{
    if (!ble_connected || !ble_notification_enabled)
    {
        log_printf("send_odometer_data skipped: connected=%d, notify=%d\n", ble_connected, ble_notification_enabled);
        return;
    }

    // Request ATT_EVENT_CAN_SEND_NOW which will trigger notification send in packet_handler
    // log_printf("Requesting CAN_SEND_NOW event...\n");
    att_server_request_can_send_now_event(connection_handle);
}

int main()
{
    stdio_init_all();
    logging_init();

    // // Underclock to 68 MHz for power savings (default is 125 MHz)
    // // Going lower than 68 MHz may cause issues with USB/BLE/I2C peripherals
    // log_printf("\n\n=== WALKOLUTION ODOMETER STARTING ===\n");
    // log_printf("Underclocking system from 125 MHz to 68 MHz...\n");
    // set_sys_clock_khz(68000, true); // 68 MHz = 68000 kHz, true = required
    // log_printf("System clock: %lu Hz (%.1f MHz)\n", clock_get_hz(clk_sys), clock_get_hz(clk_sys) / 1000000.0f);

    int rc = pico_led_init();
    hard_assert(rc == PICO_OK);
    log_printf("LED init OK\n");

    // Initialize Bluetooth with async context
    log_printf("Initializing Bluetooth stack...\n");
    btstack_packet_callback_registration_t hci_event_callback_registration;
    hci_event_callback_registration.callback = &packet_handler;
    hci_add_event_handler(&hci_event_callback_registration);
    log_printf("HCI event handler registered\n");

    // Initialize L2CAP
    l2cap_init();
    log_printf("L2CAP initialized\n");

    // Initialize LE Security Manager
    sm_init();
    log_printf("Security Manager initialized\n");

    // Setup ATT server with our GATT database (generated from .gatt file)
    att_server_init(profile_data, att_read_callback, att_write_callback);
    att_server_register_packet_handler(packet_handler);
    log_printf("ATT server initialized\n");

    // Turn on Bluetooth stack
    hci_power_control(HCI_POWER_ON);
    log_printf("HCI powered on\n");

    // Initialize user settings
    log_printf("Initializing user settings...\n");
    user_settings_init();

    // Initialize OLED display
    log_printf("Initializing OLED display...\n");
    oled_init(OLED_I2C_PORT, OLED_SDA_PIN, OLED_SCL_PIN, OLED_ADDR);

    // Initialize odometer sensor
    log_printf("Initializing odometer sensor on pin %d...\n", SENSOR_PIN);
    odometer_init(SENSOR_PIN);

    // Show startup message (centered, 12pt to fit on screen)
    oled_clear();
    oled_draw_text_centered(OLED_WIDTH / 2, 28, "Walkolution", &FreeSans12pt7b);

    // Read and display voltage
    log_printf("Reading voltage...\n");
    uint16_t voltage_mv = odometer_read_voltage();
    log_printf("Voltage: %u mV\n", voltage_mv);
    char voltage_str[32];
    snprintf(voltage_str, sizeof(voltage_str), "%.2fV", voltage_mv / 1000.0f);
    oled_draw_text_centered(OLED_WIDTH / 2, 48, voltage_str, &FreeSans9pt7b);
    oled_update();

    sleep_ms(2000); // Show startup screen for 2 seconds

    // Set LED callback for visual feedback
    odometer_set_led_callback(pico_set_led);

    // Enable voltage-based flash save: only save when voltage drops below 3.3V
    // This extends flash lifetime when powered by supercapacitor/generator
    odometer_enable_voltage_save(3300); // 3300 mV = 3.3V

    // Display state tracking
    bool showing_session = true; // True = session screen, False = totals screen
    uint32_t last_display_switch_ms = to_ms_since_boot(get_absolute_time());
    uint32_t last_update_ms = 0;
    uint32_t last_voltage_check_ms = 0;
    bool oled_is_on = true; // Track OLED power state

    // BLE data update tracking
    uint32_t last_ble_update_ms = 0;
    uint32_t last_speed_window_update_ms = 0;

#if DEBUG_FAKE_ROTATIONS
    // Debug: track fake rotations
    uint32_t last_debug_rotation_ms = 0;
#endif

    // Initial display
    log_printf("Updating initial OLED display...\n");
    update_oled_session(ble_connected, ble_advertising);

    log_printf("=== ENTERING MAIN LOOP ===\n");
    log_printf("BLE_VOLTAGE_THRESHOLD_MV = %d\n", BLE_VOLTAGE_THRESHOLD_MV);

    while (true)
    {
        uint32_t current_time_ms = to_ms_since_boot(get_absolute_time());

#if DEBUG_FAKE_ROTATIONS
        // Debug: simulate rotations at 2 MPH
        if ((current_time_ms - last_debug_rotation_ms) >= DEBUG_ROTATION_INTERVAL_MS)
        {
            odometer_add_rotation(); // Manually add a rotation
            last_debug_rotation_ms = current_time_ms;
        }
#endif

        // Process sensor reading
        bool rotation_detected = odometer_process();

        // Sync LED with sensor state for visual feedback
        bool sensor_state = gpio_get(SENSOR_PIN);
        pico_set_led(sensor_state);

        // Check voltage periodically and control BLE and OLED
        if ((current_time_ms - last_voltage_check_ms) >= VOLTAGE_CHECK_INTERVAL_MS)
        {
            uint16_t voltage_mv = odometer_read_voltage();

            log_printf("[%lu] %u mV, BLE: adv=%d con=%d, OLED=%d\n",
                       current_time_ms, voltage_mv, ble_advertising, ble_connected, oled_is_on);

            // Control OLED power with hysteresis to prevent flickering
            // Turn off at 3.0V, turn on at 3.5V
            if (voltage_mv < OLED_VOLTAGE_OFF_THRESHOLD_MV && oled_is_on)
            {
                log_printf("*** TURNING OFF OLED (voltage %u < %u) ***\n",
                           voltage_mv, OLED_VOLTAGE_OFF_THRESHOLD_MV);
                oled_display_off();
                oled_is_on = false;
            }
            else if (voltage_mv >= OLED_VOLTAGE_ON_THRESHOLD_MV && !oled_is_on)
            {
                log_printf("*** TURNING ON OLED (voltage %u >= %u) ***\n",
                           voltage_mv, OLED_VOLTAGE_ON_THRESHOLD_MV);
                oled_display_on();
                oled_is_on = true;
                // Force a display update to refresh the screen
                if (showing_session)
                {
                    update_oled_session(ble_connected, ble_advertising);
                }
                else
                {
                    update_oled_totals(ble_connected, ble_advertising);
                }
            }

            // Track voltage state for BLE activation delay
            // Voltage must stay above threshold for BLE_ACTIVATION_DELAY_MS before starting BLE
            if (voltage_mv >= BLE_VOLTAGE_THRESHOLD_MV)
            {
                // Voltage is above threshold
                if (!ble_voltage_is_above_threshold)
                {
                    // Voltage just went above threshold - start the timer
                    ble_voltage_is_above_threshold = true;
                    ble_voltage_above_threshold_start_ms = current_time_ms;
                    log_printf("Voltage above BLE threshold (%u >= %u), starting %d second delay timer\n",
                               voltage_mv, BLE_VOLTAGE_THRESHOLD_MV, BLE_ACTIVATION_DELAY_MS / 1000);
                }
                else if (!ble_advertising)
                {
                    // Check if we've been above threshold long enough
                    uint32_t time_above_threshold_ms = current_time_ms - ble_voltage_above_threshold_start_ms;
                    if (time_above_threshold_ms >= BLE_ACTIVATION_DELAY_MS)
                    {
                        log_printf("*** STARTING BLE ADVERTISING (voltage stable at %u mV for %lu ms) ***\n",
                                   voltage_mv, time_above_threshold_ms);
                        start_ble_advertising();
                    }
                    else
                    {
                        // Still waiting for the delay to complete
                        uint32_t remaining_ms = BLE_ACTIVATION_DELAY_MS - time_above_threshold_ms;
                        log_printf("Voltage stable above threshold for %lu ms, %lu ms remaining before BLE starts\n",
                                   time_above_threshold_ms, remaining_ms);
                    }
                }
            }
            else
            {
                // Voltage is below threshold
                if (ble_voltage_is_above_threshold)
                {
                    // Voltage dropped below threshold - reset the timer
                    ble_voltage_is_above_threshold = false;
                    ble_voltage_above_threshold_start_ms = 0;
                    log_printf("Voltage dropped below BLE threshold (%u < %u), resetting delay timer\n",
                               voltage_mv, BLE_VOLTAGE_THRESHOLD_MV);
                }
                else if (!ble_advertising)
                {
                    log_printf("Voltage too low for BLE: %u < %u\n", voltage_mv, BLE_VOLTAGE_THRESHOLD_MV);
                }
            }

            last_voltage_check_ms = current_time_ms;
        }

        // Poll cyw43 for BLE - MUST be called regularly for BLE to work
        cyw43_arch_poll();

        // Update speed window every second
        if ((current_time_ms - last_speed_window_update_ms) >= 1000)
        {
            update_speed_window(current_time_ms);
            last_speed_window_update_ms = current_time_ms;
        }

        // Send BLE data every second when connected
        if (ble_connected && (current_time_ms - last_ble_update_ms) >= BLE_UPDATE_INTERVAL_MS)
        {
            send_odometer_data();
            last_ble_update_ms = current_time_ms;
        }

        // Switch display mode every 5 seconds (only if OLED is on)
        if (oled_is_on && (current_time_ms - last_display_switch_ms) >= DISPLAY_SWITCH_INTERVAL_MS)
        {
            showing_session = !showing_session;
            last_display_switch_ms = current_time_ms;

            if (showing_session)
            {
                update_oled_session(ble_connected, ble_advertising);
            }
            else
            {
                update_oled_totals(ble_connected, ble_advertising);
            }
            last_update_ms = current_time_ms;
        }
        // Update display frequently when advertising (250ms for smooth flashing animation)
        // Otherwise update every 1 second for power savings (clock still updates smoothly)
        // Skip all updates if OLED is off to conserve power
        else if (oled_is_on)
        {
            uint32_t update_interval = (ble_advertising && !ble_connected) ? 250 : OLED_UPDATE_INTERVAL_MS;

            if (showing_session && (current_time_ms - last_update_ms) >= update_interval)
            {
                update_oled_session(ble_connected, ble_advertising);
                last_update_ms = current_time_ms;
            }
            // Also update totals screen when advertising for flashing animation
            else if (!showing_session && ble_advertising && !ble_connected && (current_time_ms - last_update_ms) >= 250)
            {
                update_oled_totals(ble_connected, ble_advertising);
                last_update_ms = current_time_ms;
            }
            // Update session display on rotation when showing session
            else if (showing_session && rotation_detected)
            {
                update_oled_session(ble_connected, ble_advertising);
                last_update_ms = to_ms_since_boot(get_absolute_time());
            }
        }

        sleep_ms(POLL_DELAY_MS);
    }
}
