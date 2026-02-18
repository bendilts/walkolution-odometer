# Speed Module Unit Tests

This directory contains comprehensive unit tests for the `speed.c` module using the Unity testing framework.

> **⚠️ IMPORTANT FOR CLAUDE**: See [TESTING_POLICY.md](TESTING_POLICY.md) for mandatory testing requirements. Always run tests after modifying firmware code!

> **💡 VS Code Users**: If you see IntelliSense errors in test files, switch to the **"Tests (Native)"** configuration. See [../.vscode/README_INTELLISENSE.md](../.vscode/README_INTELLISENSE.md) for details.

## Overview

The test suite validates all functionality of the speed tracking module:

- **Rolling 5-second window** - Circular buffer management for speed averaging
- **Running average calculations** - Both mph and km/h conversions
- **Session average calculations** - Independent from rolling window
- **Slow walking detection** - OLED power management state machine
- **Edge cases** - Large values, zero time differences, boundary conditions

## Test Framework

Uses [Unity](https://github.com/ThrowTheSwitch/Unity), a lightweight C unit testing framework ideal for embedded projects.

## Building and Running Tests

### Quick Start

From the `test/` directory:

```bash
# Configure the build (only needed once)
cmake -B build -S .

# Build the tests
cmake --build build

# Run the tests
./build/test_speed
```

### From Project Root

```bash
# Build
cmake --build test/build

# Run
./test/build/test_speed
```

### Expected Output

When all tests pass:

```
27 Tests 0 Failures 0 Ignored
OK
```

## Test Coverage

### Initialization & Reset (2 tests)
- `test_speed_init_zeros_state` - Verifies initialization
- `test_speed_reset_clears_window` - Verifies reset behavior

### Running Average Speed (9 tests)
- Insufficient data handling (< 2 points)
- Simple two-point calculations (mph and km/h)
- Realistic walking speeds
- Full 5-second window behavior
- Circular buffer wrapping
- Dynamic speed changes
- Zero time difference edge case

### Session Average Speed (5 tests)
- Zero time handling
- mph and km/h conversions
- Short and long session calculations

### Slow Walking Detection (10 tests)
- Initial state (OLED allowed)
- Stopped state (speed = 0)
- Fast walking (speed >= 1.5 mph)
- Entering slow range (0 < speed < 1.5 mph)
- 5-second timer behavior
- Exiting to fast walking
- Exiting to stopped
- Threshold boundary testing
- Complex enter/exit/re-enter scenarios

### Edge Cases (2 tests)
- Large rotation counts (approaching uint32_t limits)
- Large time values (long-running sessions)

## Test Structure

```
test/
├── README.md           # This file
├── CMakeLists.txt      # Build configuration
├── test_speed.c        # Test suite (27 tests)
├── mock_logging.c      # Mock implementation of logging
├── mock_logging.h      # Mock logging header
└── unity/              # Unity framework
    ├── unity.c
    ├── unity.h
    └── unity_internals.h
```

## Mocking

The test suite uses a mock implementation of `logging.h` since the speed module depends on logging functions. The mock implementation (`mock_logging.c`) provides stub functions that do nothing, allowing the tests to run independently of the actual logging system.

To see log output during test runs (useful for debugging), uncomment the printf code in `mock_logging.c`.

## Adding New Tests

To add a new test:

1. Create a test function in `test_speed.c`:
```c
void test_my_new_test(void) {
    // Setup
    speed_init();

    // Execute
    speed_update(100, 1000);

    // Assert
    float speed = speed_get_running_avg(false);
    assert_float_equal(expected_value, speed, "Description");
}
```

2. Register it in `main()`:
```c
RUN_TEST(test_my_new_test);
```

3. Rebuild and run.

## Integration with CI/CD

The test suite can be integrated into CI/CD pipelines:

```bash
# Build and run tests, exit with error code if tests fail
cmake -B test/build -S test && \
cmake --build test/build && \
test/build/test_speed
```

The test executable returns 0 on success, non-zero on failure.

## Notes

- Tests run natively on the host machine (not on Pico hardware)
- All floating-point comparisons use an epsilon of 0.001 for tolerance
- Conversion constants are calculated identically to `speed.c` to ensure exact matches
- Tests are independent - each test calls `setUp()` which reinitializes the module
