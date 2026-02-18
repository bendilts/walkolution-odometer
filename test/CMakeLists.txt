cmake_minimum_required(VERSION 3.13)

# Unit test build for speed.c module
# This builds a native executable (not Pico target) to run on the host machine

project(speed_tests C)

set(CMAKE_C_STANDARD 11)
set(CMAKE_EXPORT_COMPILE_COMMANDS ON)  # Generate compile_commands.json for IDE support

# Include the parent directory for speed.h
include_directories(${CMAKE_CURRENT_SOURCE_DIR}/..)

# Include the Unity framework
include_directories(${CMAKE_CURRENT_SOURCE_DIR}/unity)

# Create test executable
add_executable(test_speed
    test_speed.c
    ../speed.c          # Module under test
    mock_logging.c      # Mock logging implementation
    unity/unity.c       # Unity test framework
)

# Enable testing
enable_testing()
add_test(NAME speed_unit_tests COMMAND test_speed)
