# Generated Cmake Pico project file

cmake_minimum_required(VERSION 3.13)

set(CMAKE_C_STANDARD 11)
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_EXPORT_COMPILE_COMMANDS ON)

# Initialise pico_sdk from installed location
# (note this can come from environment, CMake cache etc)

# == DO NOT EDIT THE FOLLOWING LINES for the Raspberry Pi Pico VS Code Extension to work ==
if(WIN32)
    set(USERHOME $ENV{USERPROFILE})
else()
    set(USERHOME $ENV{HOME})
endif()
set(sdkVersion 2.2.0)
set(toolchainVersion 14_2_Rel1)
set(picotoolVersion 2.2.0-a4)
set(picoVscode ${USERHOME}/.pico-sdk/cmake/pico-vscode.cmake)
if (EXISTS ${picoVscode})
    include(${picoVscode})
endif()
# ====================================================================================
set(PICO_BOARD pico_w CACHE STRING "Board type")

# Pull in Raspberry Pi Pico SDK (must be before project)
include(pico_sdk_import.cmake)

project(walkolution-odometer C CXX ASM)

# Initialise the Raspberry Pi Pico SDK
pico_sdk_init()

# Add executable. Default name is the project name, version 0.1

add_executable(walkolution-odometer
    walkolution-odometer.c
    odometer.c
    oled.c
    adafruit_fonts.c
    icons.c
    user_settings.c
    logging.c
    )

# Add current directory to include path for btstack_config.h
target_include_directories(walkolution-odometer PRIVATE ${CMAKE_CURRENT_LIST_DIR})

# pull in common dependencies
target_link_libraries(walkolution-odometer
    pico_stdlib
    hardware_flash
    hardware_sync
    hardware_i2c
    hardware_adc
    pico_multicore
)

if (PICO_CYW43_SUPPORTED)
    target_link_libraries(walkolution-odometer
        pico_cyw43_arch_lwip_poll
        pico_btstack_ble
        pico_btstack_cyw43
        pico_btstack_run_loop_async_context
    )
    target_compile_definitions(walkolution-odometer PRIVATE
        CYW43_ENABLE_BLUETOOTH=1
    )

    # Generate GATT database header from .gatt file
    pico_btstack_make_gatt_header(walkolution-odometer PRIVATE ${CMAKE_CURRENT_LIST_DIR}/walkolution-odometer.gatt)
endif()

# Enable USB serial output for debugging
pico_enable_stdio_usb(walkolution-odometer 1)
pico_enable_stdio_uart(walkolution-odometer 0)

# create map/bin/hex file etc.
pico_add_extra_outputs(walkolution-odometer)

# add url via pico_set_program_url
