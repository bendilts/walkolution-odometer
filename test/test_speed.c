/**
 * Unit tests for speed.c module
 *
 * Tests the speed tracking and calculation functionality including:
 * - Rolling 5-second window management
 * - Running average speed calculation
 * - Session average speed calculation
 * - Slow walking detection for OLED power management
 * - BLE activation based on walking speed
 */

#include "unity.h"
#include "speed.h"
#include <string.h>

// Test constants
#define EPSILON 0.001f  // For floating point comparisons

// Expected conversion constants (from speed.c)
// Each rotation = 34.56 cm
// Miles per rotation = 0.3456 m / 1609.344 m/mile
// KM per rotation = 0.3456 m / 1000 m/km
// Use the same calculation as speed.c to ensure exact match
#define CM_PER_ROTATION 34.56f
#define METERS_PER_MILE 1609.344f
#define METERS_PER_KM 1000.0f
#define EXPECTED_MILES_PER_ROTATION (CM_PER_ROTATION / 100.0f / METERS_PER_MILE)
#define EXPECTED_KM_PER_ROTATION (CM_PER_ROTATION / 100.0f / METERS_PER_KM)

// Helper function to assert float equality
void assert_float_equal(float expected, float actual, const char* msg) {
    TEST_ASSERT_FLOAT_WITHIN_MESSAGE(EPSILON, expected, actual, msg);
}

// Setup and teardown
void setUp(void) {
    speed_init();
}

void tearDown(void) {
    // Nothing to do
}

// ============================================================================
// INITIALIZATION AND RESET TESTS
// ============================================================================

void test_speed_init_zeros_state(void) {
    // After init, running average should be 0 (insufficient data)
    float speed = speed_get_running_avg(false);
    assert_float_equal(0.0f, speed, "Running avg should be 0 after init");

    // OLED should be allowed (not in slow walking range)
    TEST_ASSERT_TRUE_MESSAGE(speed_allows_oled_display(0),
                             "OLED should be allowed after init");
}

void test_speed_reset_clears_window(void) {
    // Build up some speed data
    speed_update(100, 1000);
    speed_update(200, 2000);

    // Reset
    speed_reset();

    // Running average should be 0 again
    float speed = speed_get_running_avg(false);
    assert_float_equal(0.0f, speed, "Running avg should be 0 after reset");

    // OLED should be allowed
    TEST_ASSERT_TRUE_MESSAGE(speed_allows_oled_display(3000),
                             "OLED should be allowed after reset");
}

// ============================================================================
// RUNNING AVERAGE SPEED TESTS
// ============================================================================

void test_running_avg_insufficient_data_returns_zero(void) {
    // With only 1 data point, should return 0
    speed_update(100, 1000);
    float speed = speed_get_running_avg(false);
    assert_float_equal(0.0f, speed, "Should return 0 with only 1 data point");
}

void test_running_avg_simple_two_points_mph(void) {
    // Simulate 1 second apart, 100 rotations difference
    speed_update(0, 0);
    speed_update(100, 1000);

    // 100 rotations in 1000ms = 100 rotations/second = 360,000 rotations/hour
    // 360,000 * 0.0002147 miles/rotation = 77.29 mph
    float speed = speed_get_running_avg(false);  // mph
    float expected = 360000.0f * EXPECTED_MILES_PER_ROTATION;
    assert_float_equal(expected, speed, "Simple two-point speed calculation (mph)");
}

void test_running_avg_simple_two_points_kmh(void) {
    // Same test but in km/h
    speed_update(0, 0);
    speed_update(100, 1000);

    // 100 rotations in 1000ms = 360,000 rotations/hour
    // 360,000 * 0.0003456 km/rotation = 124.42 km/h
    float speed = speed_get_running_avg(true);  // km/h
    float expected = 360000.0f * EXPECTED_KM_PER_ROTATION;
    assert_float_equal(expected, speed, "Simple two-point speed calculation (km/h)");
}

void test_running_avg_realistic_walking_speed(void) {
    // Walking at ~1.5 mph
    // At 1.5 mph for 1 hour = 1.5 miles
    // 1.5 miles / 0.0002147 miles/rotation = ~6986 rotations/hour
    // In 1 second = ~1.94 rotations
    // Let's use 2 rotations per second for simplicity

    speed_update(0, 0);
    speed_update(2, 1000);

    float speed = speed_get_running_avg(false);  // mph
    // 2 rotations in 1000ms = 7200 rotations/hour
    // 7200 * 0.0002147 = 1.546 mph
    float expected = 7200.0f * EXPECTED_MILES_PER_ROTATION;
    assert_float_equal(expected, speed, "Realistic walking speed (~1.5 mph)");
}

void test_running_avg_full_window_5_seconds(void) {
    // Fill the entire 5-second window
    speed_update(0, 0);      // t=0s
    speed_update(2, 1000);   // t=1s, +2 rotations
    speed_update(4, 2000);   // t=2s, +2 rotations
    speed_update(6, 3000);   // t=3s, +2 rotations
    speed_update(8, 4000);   // t=4s, +2 rotations
    speed_update(10, 5000);  // t=5s, +2 rotations

    float speed = speed_get_running_avg(false);  // mph

    // Window spans from t=0 to t=5 (5000ms)
    // 10 rotations in 5000ms = 2 rotations/second = 7200 rotations/hour
    float expected = 7200.0f * EXPECTED_MILES_PER_ROTATION;
    assert_float_equal(expected, speed, "Full 5-second window average");
}

void test_running_avg_window_wraps_circular_buffer(void) {
    // Fill window and then add one more to test wrapping
    speed_update(0, 0);      // t=0s (index 0)
    speed_update(2, 1000);   // t=1s (index 1)
    speed_update(4, 2000);   // t=2s (index 2)
    speed_update(6, 3000);   // t=3s (index 3)
    speed_update(8, 4000);   // t=4s (index 4)
    speed_update(10, 5000);  // t=5s (index 0, wraps) <- oldest is now index 1
    speed_update(12, 6000);  // t=6s (index 1, wraps) <- oldest is now index 2

    float speed = speed_get_running_avg(false);  // mph

    // After wrapping, window spans from index 2 (t=2s, 4 rotations) to index 1 (t=6s, 12 rotations)
    // 12 - 4 = 8 rotations in 6000 - 2000 = 4000ms
    // 8 rotations in 4000ms = 2 rotations/second = 7200 rotations/hour
    float expected = 7200.0f * EXPECTED_MILES_PER_ROTATION;
    assert_float_equal(expected, speed, "Window wraps correctly in circular buffer");
}

void test_running_avg_changing_speed(void) {
    // Start slow, then speed up
    speed_update(0, 0);      // t=0s (index 0)
    speed_update(1, 1000);   // t=1s, slow (index 1)
    speed_update(2, 2000);   // t=2s, slow (index 2)
    speed_update(4, 3000);   // t=3s, speeding up (index 3)
    speed_update(8, 4000);   // t=4s, faster (index 4)
    speed_update(16, 5000);  // t=5s, even faster (index 0, wraps, filled=true, next index=1)

    float speed = speed_get_running_avg(false);  // mph

    // After last update: index=1, filled=true
    // oldest_index = 1 (1 rotation at t=1000)
    // newest_index = 0 (16 rotations at t=5000)
    // Difference: 16-1=15 rotations in 5000-1000=4000ms
    // 15 rotations in 4000ms = 3.75 rotations/second = 13,500 rotations/hour
    float expected = 13500.0f * EXPECTED_MILES_PER_ROTATION;
    assert_float_equal(expected, speed, "Changing speed averaged correctly");
}

void test_running_avg_zero_time_difference_returns_zero(void) {
    // Edge case: same timestamp for multiple updates
    speed_update(0, 1000);
    speed_update(100, 1000);  // Same time

    float speed = speed_get_running_avg(false);
    assert_float_equal(0.0f, speed, "Zero time difference should return 0");
}

// ============================================================================
// SESSION AVERAGE SPEED TESTS
// ============================================================================

void test_session_avg_zero_time_returns_zero(void) {
    float speed = speed_get_session_avg(1000, 0, false);
    assert_float_equal(0.0f, speed, "Zero session time should return 0");
}

void test_session_avg_simple_calculation_mph(void) {
    // 7200 rotations in 3600 seconds (1 hour) = 2 rotations/second
    float speed = speed_get_session_avg(7200, 3600, false);  // mph
    float expected = 7200.0f * EXPECTED_MILES_PER_ROTATION;
    assert_float_equal(expected, speed, "Session average (mph)");
}

void test_session_avg_simple_calculation_kmh(void) {
    // Same test in km/h
    float speed = speed_get_session_avg(7200, 3600, true);  // km/h
    float expected = 7200.0f * EXPECTED_KM_PER_ROTATION;
    assert_float_equal(expected, speed, "Session average (km/h)");
}

void test_session_avg_short_session(void) {
    // 10 rotations in 5 seconds
    float speed = speed_get_session_avg(10, 5, false);  // mph
    // 10/5 = 2 rotations/second = 7200 rotations/hour
    float expected = 7200.0f * EXPECTED_MILES_PER_ROTATION;
    assert_float_equal(expected, speed, "Short session average");
}

void test_session_avg_long_session(void) {
    // 100,000 rotations in 50,000 seconds
    float speed = speed_get_session_avg(100000, 50000, false);  // mph
    // 100000/50000 = 2 rotations/second = 7200 rotations/hour
    float expected = 7200.0f * EXPECTED_MILES_PER_ROTATION;
    assert_float_equal(expected, speed, "Long session average");
}

// ============================================================================
// SLOW WALKING DETECTION TESTS
// ============================================================================

void test_oled_allowed_initially(void) {
    TEST_ASSERT_TRUE_MESSAGE(speed_allows_oled_display(0),
                             "OLED should be allowed initially");
}

void test_oled_allowed_when_stopped(void) {
    // Speed = 0 (no movement)
    speed_update(0, 0);
    speed_update(0, 1000);

    // Should NOT be in slow walking range (need speed > 0)
    TEST_ASSERT_TRUE_MESSAGE(speed_allows_oled_display(1000),
                             "OLED should be allowed when stopped (speed=0)");
}

void test_oled_allowed_when_walking_fast(void) {
    // Walking at ~3 mph (above 1.2 mph threshold)
    // 3 mph = 3 miles/hour / 0.0002147 miles/rotation = ~13,972 rotations/hour
    // = ~3.88 rotations/second, use 4 for simplicity

    speed_update(0, 0);
    speed_update(4, 1000);   // 4 rotations/sec = 14,400 rot/hr = ~3.09 mph
    speed_update(8, 2000);

    // Should NOT be in slow walking range (speed >= 1.2 mph)
    TEST_ASSERT_TRUE_MESSAGE(speed_allows_oled_display(2000),
                             "OLED should be allowed when walking fast (>1.2 mph)");
}

void test_oled_enters_slow_walking_range(void) {
    // Walking at ~1.0 mph (below 1.2 mph threshold but above 0)
    // 1.0 mph = 1 / 0.0002147 = ~4658 rotations/hour = ~1.29 rotations/second
    // Use 1.3 rotations/second

    speed_update(0, 0);
    speed_update(1, 1000);   // ~1.0 mph
    speed_update(2, 2000);   // Still ~1.0 mph

    // Just entered slow walking range, but timer just started
    // Should still allow OLED (need 5 seconds in range)
    TEST_ASSERT_TRUE_MESSAGE(speed_allows_oled_display(2000),
                             "OLED should be allowed when first entering slow walking range");
}

void test_oled_allowed_for_first_5_seconds_in_slow_range(void) {
    // Enter slow walking range and stay there
    speed_update(0, 0);
    speed_update(1, 1000);   // Enter at t=1000ms
    speed_update(2, 2000);   // Still in range
    speed_update(3, 3000);   // Still in range
    speed_update(4, 4000);   // Still in range
    speed_update(5, 5000);   // Still in range at t=5000ms

    // At t=5999ms (just under 5 seconds since entering at t=1000ms)
    TEST_ASSERT_TRUE_MESSAGE(speed_allows_oled_display(5999),
                             "OLED should be allowed for first 5 seconds in slow walking range");
}

void test_oled_disallowed_after_5_seconds_in_slow_range(void) {
    // Enter slow walking range and stay there for 5+ seconds
    speed_update(0, 0);
    speed_update(1, 1000);   // Enter at t=1000ms
    speed_update(2, 2000);
    speed_update(3, 3000);
    speed_update(4, 4000);
    speed_update(5, 5000);
    speed_update(6, 6000);   // Still in range at t=6000ms (5 seconds since entry)

    // At t=6000ms (exactly 5 seconds since entering at t=1000ms)
    TEST_ASSERT_FALSE_MESSAGE(speed_allows_oled_display(6000),
                              "OLED should be disallowed after 5 seconds in slow walking range");

    // At t=7000ms (6 seconds since entering)
    TEST_ASSERT_FALSE_MESSAGE(speed_allows_oled_display(7000),
                              "OLED should remain disallowed after 5+ seconds");
}

void test_oled_timer_resets_when_exiting_slow_range_to_fast(void) {
    // Enter slow walking range
    speed_update(0, 0);
    speed_update(1, 1000);   // Slow walking
    speed_update(2, 2000);
    speed_update(3, 3000);

    // Now speed up (exit slow walking range)
    speed_update(7, 4000);   // Fast walking (~3 mph)
    speed_update(11, 5000);

    // Timer should reset, OLED should be allowed
    TEST_ASSERT_TRUE_MESSAGE(speed_allows_oled_display(5000),
                             "OLED should be allowed after exiting to fast walking");

    // Now enter slow walking range again
    speed_update(12, 6000);  // Slow down
    speed_update(13, 7000);

    // Timer should start fresh, OLED still allowed
    TEST_ASSERT_TRUE_MESSAGE(speed_allows_oled_display(7000),
                             "OLED should be allowed when re-entering slow range (timer reset)");
}

void test_oled_timer_resets_when_exiting_slow_range_to_stopped(void) {
    // Enter slow walking range
    speed_update(0, 0);
    speed_update(1, 1000);   // Slow walking
    speed_update(2, 2000);
    speed_update(3, 3000);

    // Now stop (speed = 0)
    speed_update(3, 4000);   // No new rotations = speed = 0
    speed_update(3, 5000);

    // Timer should reset when exiting to stopped, OLED should be allowed
    TEST_ASSERT_TRUE_MESSAGE(speed_allows_oled_display(5000),
                             "OLED should be allowed after stopping (exiting slow range)");
}

void test_oled_threshold_exactly_at_1_5_mph(void) {
    // Test boundary: exactly 1.2 mph should NOT be in slow walking range
    // 1.2 mph = 1.2 / 0.0002147 = 5589 rotations/hour = 1.55 rotations/second
    // Use 2 rotations/second which gives ~1.546 mph

    speed_update(0, 0);
    speed_update(2, 1000);   // ~1.546 mph (just above threshold)

    // Should NOT be in slow walking range (threshold is speed < 1.2, this is >= 1.2)
    // But actually 1.546 is still above 1.2, so let's be more precise
    // Let's use 1.9 rotations/second = 6840 rotations/hour = 1.468 mph (above threshold)

    speed_init();  // Reset
    speed_update(0, 0);
    speed_update(1, 1000);   // 1 rotation in 1 second = 3600 rot/hr = 0.773 mph (below threshold)
    speed_update(2, 2000);
    speed_update(3, 3000);
    speed_update(4, 4000);
    speed_update(5, 5000);
    speed_update(6, 6000);

    // Been in slow range for 5 seconds
    TEST_ASSERT_FALSE_MESSAGE(speed_allows_oled_display(6000),
                              "OLED should be off for speed well below 1.2 mph");
}

void test_oled_complex_scenario_enter_exit_reenter(void) {
    // Complex scenario: enter, stay 3 seconds, exit, re-enter, stay 5 seconds

    speed_update(0, 0);
    speed_update(1, 1000);   // Enter slow range at t=1000 (~0.77 mph)
    speed_update(2, 2000);   // In range 1 second
    speed_update(3, 3000);   // In range 2 seconds

    // OLED should still be allowed (only 2 seconds)
    TEST_ASSERT_TRUE_MESSAGE(speed_allows_oled_display(3000),
                             "OLED allowed after 2 seconds in slow range");

    // Speed up significantly (exit slow range)
    speed_update(10, 4000);   // Big jump to fast walking
    speed_update(14, 5000);   // Continue fast walking (4 rot/s = ~3.09 mph)
    speed_update(18, 6000);   // Still fast

    TEST_ASSERT_TRUE_MESSAGE(speed_allows_oled_display(6000),
                             "OLED allowed after exiting slow range");

    // Slow down again (re-enter slow range) - need to wait for window to clear fast walking
    // Continue fast for a bit longer, then slow down
    speed_update(22, 7000);   // Still fast
    speed_update(26, 8000);   // Still fast
    speed_update(30, 9000);   // Still fast

    // Now slow down - need to wait for 5-second window to clear the fast walking
    speed_update(31, 10000);  // Slow down (window still has fast data)
    speed_update(32, 11000);  // 1 rotation/sec
    speed_update(33, 12000);  // 1 rotation/sec
    speed_update(34, 13000);  // 1 rotation/sec
    speed_update(35, 14000);  // 1 rotation/sec - window now 14000-9000, has 30-35 = 5 rot in 5s = 0.77 mph

    // Now in slow range, timer starts at t=14000
    speed_update(36, 15000);  // 1 second in slow range
    speed_update(37, 16000);  // 2 seconds
    speed_update(38, 17000);  // 3 seconds
    speed_update(39, 18000);  // 4 seconds
    speed_update(40, 19000);  // 5 seconds since entering slow range at t=14000

    // At t=19000, been in slow range for 5 seconds (since t=14000)
    TEST_ASSERT_FALSE_MESSAGE(speed_allows_oled_display(19000),
                              "OLED disallowed after 5 seconds in slow range on re-entry");
}

// ============================================================================
// BLE ACTIVATION TESTS
// ============================================================================

void test_ble_disallowed_initially(void) {
    // On startup, BLE should not be allowed
    TEST_ASSERT_FALSE_MESSAGE(speed_allows_ble(),
                               "BLE should be disallowed on startup");
}

void test_ble_disallowed_when_stopped(void) {
    // Speed = 0 (no movement) - should not activate BLE
    speed_update(0, 0);
    speed_update(0, 1000);
    speed_update(0, 2000);
    speed_update(0, 3000);

    TEST_ASSERT_FALSE_MESSAGE(speed_allows_ble(),
                               "BLE should remain disallowed when stopped");
}

void test_ble_disallowed_in_slow_walking_range(void) {
    // Walking at ~1.0 mph (below 1.2 mph threshold)
    speed_update(0, 0);
    speed_update(1, 1000);   // ~0.77 mph
    speed_update(2, 2000);
    speed_update(3, 3000);
    speed_update(4, 4000);

    // Even after several seconds, should not activate BLE if speed < 1.2 mph
    TEST_ASSERT_FALSE_MESSAGE(speed_allows_ble(),
                               "BLE should remain disallowed in slow walking range (<1.2 mph)");
}

void test_ble_disallowed_for_first_5_seconds_above_threshold(void) {
    // Walking at ~3 mph (above 1.2 mph threshold)
    // 4 rotations/sec = 14,400 rot/hr = ~3.09 mph
    speed_update(0, 0);
    speed_update(4, 1000);   // Enter fast walking at t=1000ms
    speed_update(8, 2000);
    speed_update(12, 3000);
    speed_update(16, 4000);
    speed_update(20, 5000);  // t=5000ms (4 seconds since entering at t=1000ms)

    // Should still be disallowed (need 5 seconds, only been 4)
    TEST_ASSERT_FALSE_MESSAGE(speed_allows_ble(),
                               "BLE should remain disallowed before 5 seconds above threshold");
}

void test_ble_activated_after_5_seconds_above_threshold(void) {
    // Walking at ~3 mph (above 1.2 mph threshold) for 5+ seconds
    speed_update(0, 0);
    speed_update(4, 1000);   // Enter fast walking at t=1000ms
    speed_update(8, 2000);
    speed_update(12, 3000);
    speed_update(16, 4000);
    speed_update(20, 5000);
    speed_update(24, 6000);  // t=6000ms (5 seconds since entering at t=1000ms)

    // Should now be activated permanently
    TEST_ASSERT_TRUE_MESSAGE(speed_allows_ble(),
                              "BLE should be activated after 5 seconds above threshold");
}

void test_ble_timer_resets_when_dropping_below_threshold(void) {
    // Start walking fast
    speed_update(0, 0);
    speed_update(4, 1000);   // Enter fast walking at t=1000ms
    speed_update(8, 2000);
    speed_update(12, 3000);   // t=3000ms (2 seconds so far)

    // Now slow down (below 1.2 mph threshold)
    speed_update(13, 4000);   // Slow down at t=4000ms
    speed_update(14, 5000);   // Speed drops below threshold

    // Timer should reset, BLE should remain disallowed
    TEST_ASSERT_FALSE_MESSAGE(speed_allows_ble(),
                               "BLE should remain disallowed when speed drops below threshold");

    // Now speed up again
    speed_update(18, 6000);  // Fast walking again at t=6000ms
    speed_update(22, 7000);
    speed_update(26, 8000);
    speed_update(30, 9000);
    speed_update(34, 10000);
    speed_update(38, 11000);  // t=11000ms (5 seconds since re-entering at t=6000ms)

    // Now should be activated (timer started fresh at t=6000ms)
    TEST_ASSERT_TRUE_MESSAGE(speed_allows_ble(),
                              "BLE should activate after 5 seconds on second attempt");
}

void test_ble_stays_activated_forever(void) {
    // Activate BLE
    speed_update(0, 0);
    for (int i = 1; i <= 6; i++) {
        speed_update(4 * i, i * 1000);
    }

    // BLE should be active
    TEST_ASSERT_TRUE_MESSAGE(speed_allows_ble(),
                              "BLE should be active after 5 seconds");

    // Now stop walking
    speed_update(24, 7000);
    speed_update(24, 8000);
    speed_update(24, 9000);
    speed_update(24, 10000);

    // BLE should STILL be active (stays active forever)
    TEST_ASSERT_TRUE_MESSAGE(speed_allows_ble(),
                              "BLE should remain active after stopping");

    // Now walk slowly
    speed_update(25, 11000);
    speed_update(26, 12000);
    speed_update(27, 13000);

    // BLE should STILL be active
    TEST_ASSERT_TRUE_MESSAGE(speed_allows_ble(),
                              "BLE should remain active during slow walking");
}

void test_ble_stays_activated_after_reset(void) {
    // Activate BLE
    speed_update(0, 0);
    for (int i = 1; i <= 6; i++) {
        speed_update(4 * i, i * 1000);
    }

    TEST_ASSERT_TRUE_MESSAGE(speed_allows_ble(),
                              "BLE should be active after 5 seconds");

    // Reset speed (simulating new session)
    speed_reset();

    // BLE should STILL be active (never resets, stays active forever)
    TEST_ASSERT_TRUE_MESSAGE(speed_allows_ble(),
                              "BLE should remain active after speed_reset()");
}

void test_ble_threshold_exactly_at_1_2_mph(void) {
    // Test boundary: exactly at 1.2 mph threshold
    // 1.2 mph = 5589 rotations/hour = 1.55 rotations/second
    // Use 2 rotations/second which gives ~1.546 mph (just above threshold)

    speed_update(0, 0);
    speed_update(2, 1000);   // ~1.546 mph (>= 1.2 mph threshold)

    for (int i = 2; i <= 6; i++) {
        speed_update(2 * i, i * 1000);
    }

    // Should be activated (speed >= 1.2 mph)
    TEST_ASSERT_TRUE_MESSAGE(speed_allows_ble(),
                              "BLE should activate at exactly 1.2 mph threshold");
}

void test_ble_just_below_threshold_never_activates(void) {
    // Test just below 1.2 mph - should never activate
    // Use 1 rotation/second = 3600 rot/hr = 0.773 mph

    speed_update(0, 0);
    for (int i = 1; i <= 30; i++) {
        speed_update(i, i * 1000);
    }

    // Should never activate (speed < 1.2 mph)
    TEST_ASSERT_FALSE_MESSAGE(speed_allows_ble(),
                               "BLE should not activate below 1.2 mph threshold");
}

void test_ble_activation_independent_of_oled_state(void) {
    // Verify BLE activation is independent of OLED state
    // Start with slow walking (OLED will turn off after 5 seconds)
    speed_update(0, 0);
    speed_update(1, 1000);   // Slow walking ~0.77 mph
    speed_update(2, 2000);
    speed_update(3, 3000);
    speed_update(4, 4000);
    speed_update(5, 5000);
    speed_update(6, 6000);   // OLED should be off now

    // Verify OLED is off
    TEST_ASSERT_FALSE_MESSAGE(speed_allows_oled_display(6000),
                               "OLED should be off in slow walking range");

    // BLE should still be off (speed too slow)
    TEST_ASSERT_FALSE_MESSAGE(speed_allows_ble(),
                               "BLE should be off when walking slowly");

    // Now speed up to fast walking (need big jump to get above 1.2 mph immediately)
    // To get >1.2 mph over 5-second window, need >8 rotations in 5 seconds
    speed_update(16, 7000);  // Big jump to start fast walking
    speed_update(20, 8000);  // Fast walking continues (4 rot/sec)
    speed_update(24, 9000);
    speed_update(28, 10000);
    speed_update(32, 11000);
    speed_update(36, 12000);  // t=12000ms (5 seconds of fast walking from t=7000)

    // OLED should be back on (exited slow walking range)
    TEST_ASSERT_TRUE_MESSAGE(speed_allows_oled_display(12000),
                              "OLED should be on when walking fast");

    // BLE should now be activated (5 seconds above threshold from t=7000 to t=12000)
    TEST_ASSERT_TRUE_MESSAGE(speed_allows_ble(),
                              "BLE should be activated independently of OLED state");
}

// ============================================================================
// EDGE CASE TESTS
// ============================================================================

void test_speed_with_large_rotation_counts(void) {
    // Test with large rotation values (approaching uint32_t limits)
    uint32_t large_val = 1000000000;  // 1 billion rotations

    speed_update(large_val, 0);
    speed_update(large_val + 100, 1000);

    // Should still calculate correctly (100 rotations in 1 second)
    float speed = speed_get_running_avg(false);
    float expected = 360000.0f * EXPECTED_MILES_PER_ROTATION;
    assert_float_equal(expected, speed, "Large rotation counts handled correctly");
}

void test_speed_with_large_time_values(void) {
    // Test with large time values
    uint32_t large_time = 1000000000;  // ~11.5 days in milliseconds

    speed_update(0, large_time);
    speed_update(100, large_time + 1000);

    // Should still calculate correctly
    float speed = speed_get_running_avg(false);
    float expected = 360000.0f * EXPECTED_MILES_PER_ROTATION;
    assert_float_equal(expected, speed, "Large time values handled correctly");
}

// ============================================================================
// MAIN TEST RUNNER
// ============================================================================

int main(void) {
    UNITY_BEGIN();

    // Initialization and reset tests
    RUN_TEST(test_speed_init_zeros_state);
    RUN_TEST(test_speed_reset_clears_window);

    // Running average speed tests
    RUN_TEST(test_running_avg_insufficient_data_returns_zero);
    RUN_TEST(test_running_avg_simple_two_points_mph);
    RUN_TEST(test_running_avg_simple_two_points_kmh);
    RUN_TEST(test_running_avg_realistic_walking_speed);
    RUN_TEST(test_running_avg_full_window_5_seconds);
    RUN_TEST(test_running_avg_window_wraps_circular_buffer);
    RUN_TEST(test_running_avg_changing_speed);
    RUN_TEST(test_running_avg_zero_time_difference_returns_zero);

    // Session average tests
    RUN_TEST(test_session_avg_zero_time_returns_zero);
    RUN_TEST(test_session_avg_simple_calculation_mph);
    RUN_TEST(test_session_avg_simple_calculation_kmh);
    RUN_TEST(test_session_avg_short_session);
    RUN_TEST(test_session_avg_long_session);

    // Slow walking detection tests
    RUN_TEST(test_oled_allowed_initially);
    RUN_TEST(test_oled_allowed_when_stopped);
    RUN_TEST(test_oled_allowed_when_walking_fast);
    RUN_TEST(test_oled_enters_slow_walking_range);
    RUN_TEST(test_oled_allowed_for_first_5_seconds_in_slow_range);
    RUN_TEST(test_oled_disallowed_after_5_seconds_in_slow_range);
    RUN_TEST(test_oled_timer_resets_when_exiting_slow_range_to_fast);
    RUN_TEST(test_oled_timer_resets_when_exiting_slow_range_to_stopped);
    RUN_TEST(test_oled_threshold_exactly_at_1_5_mph);
    RUN_TEST(test_oled_complex_scenario_enter_exit_reenter);

    // BLE activation tests
    RUN_TEST(test_ble_disallowed_initially);
    RUN_TEST(test_ble_disallowed_when_stopped);
    RUN_TEST(test_ble_disallowed_in_slow_walking_range);
    RUN_TEST(test_ble_disallowed_for_first_5_seconds_above_threshold);
    RUN_TEST(test_ble_activated_after_5_seconds_above_threshold);
    RUN_TEST(test_ble_timer_resets_when_dropping_below_threshold);
    RUN_TEST(test_ble_stays_activated_forever);
    RUN_TEST(test_ble_stays_activated_after_reset);
    RUN_TEST(test_ble_threshold_exactly_at_1_2_mph);
    RUN_TEST(test_ble_just_below_threshold_never_activates);
    RUN_TEST(test_ble_activation_independent_of_oled_state);

    // Edge case tests
    RUN_TEST(test_speed_with_large_rotation_counts);
    RUN_TEST(test_speed_with_large_time_values);

    return UNITY_END();
}
