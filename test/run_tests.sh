#!/bin/bash
# Run all test suites in the test directory
# Usage: ./test/run_tests.sh

set -e  # Exit on first error

echo "=================================="
echo "Running Walkolution Odometer Tests"
echo "=================================="
echo ""

# Get the directory where this script is located
SCRIPT_DIR="$( cd "$( dirname "${BASH_SOURCE[0]}" )" && pwd )"
PROJECT_ROOT="$(dirname "$SCRIPT_DIR")"

echo "📁 Project root: $PROJECT_ROOT"
echo "🧪 Test directory: $SCRIPT_DIR"
echo ""

# Check if build directory exists
if [ ! -d "$SCRIPT_DIR/build" ]; then
    echo "⚠️  Build directory not found. Creating initial build..."
    cmake -B "$SCRIPT_DIR/build" -S "$SCRIPT_DIR"
fi

# Build tests
echo "🔨 Building tests..."
cmake --build "$SCRIPT_DIR/build"
echo "✅ Build complete"
echo ""

# Run test_speed
echo "🧪 Running speed module tests..."
echo "=================================="
"$SCRIPT_DIR/build/test_speed"
SPEED_RESULT=$?

echo ""
echo "=================================="
echo "Test Summary"
echo "=================================="

if [ $SPEED_RESULT -eq 0 ]; then
    echo ""
    echo "🎉 All tests passed!"
    exit 0
else
    echo "❌ test_speed: FAILED"
    echo ""
    echo "⚠️  Tests failed. Please fix the issues before committing."
    exit 1
fi
