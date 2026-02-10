/**
 * Voltage monitoring module implementation
 */

#include "voltage.h"
#include "logging.h"
#include "pico/stdlib.h"
#include "hardware/gpio.h"
#include "hardware/adc.h"

// Cache last valid voltage reading
static uint16_t last_valid_voltage = 0;

void voltage_init(void)
{
    // Initialize ADC for voltage monitoring
    adc_init();
    // Note: We do NOT call adc_gpio_init(29) here because GPIO29 is shared with
    // the WiFi chip's SPI CLK on Pico W. Configuring it permanently as ADC would
    // break WiFi/BLE. Instead, we temporarily configure it in voltage_read().
}

uint16_t voltage_read(void)
{
    // Pico W: GPIO29 is shared with WiFi chip SPI CLK
    // Must disable WiFi chip (GP25 high) to read VSYS
    // CRITICAL: Must restore pins afterward or WiFi/BLE will break!

    // Disable WiFi chip by setting GP25 high
    gpio_init(25);
    gpio_set_dir(25, GPIO_OUT);
    gpio_put(25, 1);

    // Configure GP29 as ADC input
    gpio_init(29);
    gpio_set_dir(29, GPIO_IN);
    gpio_disable_pulls(29);

    // VSYS is measured via ADC channel 3 (GPIO29) through a voltage divider (3:1)
    adc_select_input(3);

    // Wait for ADC to settle (needs at least 550us according to Pico W forums)
    sleep_us(600);

    // Read ADC (12-bit: 0-4095)
    uint16_t adc_raw = adc_read();

    // Convert to voltage
    // ADC reads 0-3.3V, VSYS has 3:1 divider, so VSYS = ADC_voltage * 3
    // Voltage = (adc_raw / 4095) * 3.3V * 3
    // In millivolts: (adc_raw * 3.3 * 3 * 1000) / 4095
    uint32_t vsys_mv = (adc_raw * 9900UL) / 4095;

    // CRITICAL: Restore pins to allow WiFi/BLE to work
    // Set GP25 low to re-enable WiFi chip
    gpio_put(25, 0);
    gpio_set_pulls(25, false, true); // Pull down

    // Restore GP29 to ALT function 7 (WiFi chip SPI CLK)
    gpio_set_function(29, GPIO_FUNC_SIO);
    gpio_set_pulls(29, false, true); // Pull down
    // Note: The CYW43 driver will reconfigure this as needed

    // Filter out invalid readings (< 1500mV likely means ADC glitch)
    if (vsys_mv < 1500)
    {
        // Return last valid reading if we have one, otherwise return the bad reading
        if (last_valid_voltage > 0)
        {
            log_printf("WARNING: Invalid voltage reading %lu mV, using cached %u mV\n",
                       vsys_mv, last_valid_voltage);
            return last_valid_voltage;
        }
    }
    else
    {
        // Valid reading - cache it for future use
        last_valid_voltage = (uint16_t)vsys_mv;
    }

    return (uint16_t)vsys_mv;
}
