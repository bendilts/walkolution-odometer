# Testing Policy for Future Claude Code Sessions

## MANDATORY: Run Tests After Code Changes

**IMPORTANT**: Any time you modify firmware code (`.c` or `.h` files in the project root), you **MUST** run all test suites in the `/test` directory to verify your changes didn't break existing functionality.

## Quick Test Command

### Option 1: Convenience Script (Recommended)

From the project root:

```bash
./test/run_tests.sh
```

This script automatically:
- Creates build directory if needed
- Builds all tests
- Runs all test suites
- Provides clear pass/fail summary
- Returns proper exit codes for CI/CD

### Option 2: Manual Command

From the project root:

```bash
# Build and run all tests (single command)
cmake --build test/build && test/build/test_speed
```

Expected output when all tests pass:
```
27 Tests 0 Failures 0 Ignored
OK
```

## When to Run Tests

### Required (MUST run tests):
- ✅ After modifying `speed.c` or `speed.h`
- ✅ After modifying any module that `speed.c` depends on
- ✅ Before committing changes to git
- ✅ After refactoring existing code
- ✅ After bug fixes
- ✅ After performance optimizations

### Recommended (SHOULD run tests):
- ✅ After adding new features to other modules (to ensure no side effects)
- ✅ Before creating a pull request
- ✅ Periodically during long coding sessions

### Optional (MAY run tests):
- Documentation-only changes
- Test-only changes
- Non-firmware code changes (e.g., Android app)

## Workflow for Code Changes

### Standard Workflow

1. **Make your code changes** to firmware files
2. **Build the tests**: `cmake --build test/build`
3. **Run the tests**: `test/build/test_speed`
4. **Verify all tests pass**: Look for "27 Tests 0 Failures 0 Ignored OK"
5. **If tests fail**:
   - Fix the code issue, OR
   - Update the tests if the behavior change was intentional
6. **Commit only when tests pass**

### Example Session

```bash
# 1. Edit speed.c to fix a bug
# (make your changes in the editor)

# 2. Build and run tests
cmake --build test/build && test/build/test_speed

# 3a. If tests pass:
git add speed.c
git commit -m "Fix speed calculation bug

All unit tests passing (27/27)."

# 3b. If tests fail, investigate and fix:
# - Read the failure message
# - Fix the code or update tests
# - Rebuild and retest
```

## Test Failure Protocol

If tests fail after your changes:

### 1. Understand the Failure
```bash
# Run tests to see detailed failure output
test/build/test_speed
```

The output will show:
- Which test failed (e.g., `test_running_avg_simple_two_points_mph`)
- Expected vs actual values
- Line number in test_speed.c

### 2. Determine Root Cause

**Case A: Your code has a bug**
- Fix the code in `speed.c`
- Rebuild: `cmake --build test/build`
- Retest: `test/build/test_speed`
- Repeat until passing

**Case B: Your change intentionally modified behavior**
- Update the test expectations in `test_speed.c`
- Rebuild: `cmake --build test/build`
- Retest: `test/build/test_speed`
- Document why the test changed in commit message

**Case C: Test has a bug**
- Fix the test in `test_speed.c`
- Rebuild and retest
- Consider adding comments explaining the fix

### 3. Never Skip Failing Tests

**NEVER** commit code with failing tests unless:
- You're explicitly adding a TODO test for future functionality
- You mark it as IGNORED in the test framework
- You document why in comments and commit message

## Adding New Tests

When you add new functionality to `speed.c`:

### 1. Write Tests First (TDD approach)
```c
void test_new_feature(void) {
    speed_init();

    // Test your new feature
    // Add assertions
    TEST_ASSERT_EQUAL(expected, actual);
}
```

### 2. Register the Test
Add to `main()` in test_speed.c:
```c
RUN_TEST(test_new_feature);
```

### 3. Update Test Count
The test count will automatically increment. Update this document if needed.

### 4. Run All Tests
```bash
cmake --build test/build && test/build/test_speed
```

## CI/CD Integration (Future)

For automated testing, use this command:
```bash
#!/bin/bash
set -e  # Exit on first error

# Build tests
cmake -B test/build -S test
cmake --build test/build

# Run tests (exits with error code if tests fail)
test/build/test_speed

echo "✅ All tests passed!"
```

## Test Maintenance

### Rebuilding from Scratch

If tests behave strangely, rebuild from scratch:
```bash
# Clean build directory
rm -rf test/build

# Reconfigure and build
cmake -B test/build -S test
cmake --build test/build

# Run tests
test/build/test_speed
```

### Updating Unity Framework

If Unity framework needs updating:
```bash
cd test/unity
curl -L -o unity.c https://raw.githubusercontent.com/ThrowTheSwitch/Unity/master/src/unity.c
curl -L -o unity.h https://raw.githubusercontent.com/ThrowTheSwitch/Unity/master/src/unity.h
curl -L -o unity_internals.h https://raw.githubusercontent.com/ThrowTheSwitch/Unity/master/src/unity_internals.h
```

## Current Test Suites

### test_speed (27 tests)
Tests the `speed.c` module:
- Running average calculations
- Session average calculations
- Slow walking detection
- Circular buffer management
- Edge cases

**Dependencies**:
- Unity framework
- mock_logging.c (stub implementation)

**Current Status**: All 27 tests passing ✅

## Adding New Test Suites

When adding tests for other modules (e.g., `odometer.c`):

### 1. Create test file
```bash
test/test_odometer.c
```

### 2. Update CMakeLists.txt
```cmake
add_executable(test_odometer
    test_odometer.c
    ../odometer.c
    mock_logging.c
    unity/unity.c
)

add_test(NAME odometer_unit_tests COMMAND test_odometer)
```

### 3. Update this document
Add the new test suite to "Current Test Suites" section.

### 4. Update test runner
Create a script to run all test suites:
```bash
#!/bin/bash
# test/run_all_tests.sh

cmake --build test/build

echo "Running speed tests..."
test/build/test_speed

echo "Running odometer tests..."
test/build/test_odometer

echo "✅ All test suites passed!"
```

## Summary for Claude

When you (future Claude instance) are modifying firmware code:

1. ✅ **Always run tests after changes**: `cmake --build test/build && test/build/test_speed`
2. ✅ **Verify all tests pass**: Look for "27 Tests 0 Failures 0 Ignored OK"
3. ✅ **Never commit with failing tests** (unless explicitly documented)
4. ✅ **Add tests for new features** before or immediately after implementation
5. ✅ **Update tests when behavior intentionally changes**

This ensures code quality and prevents regressions!
