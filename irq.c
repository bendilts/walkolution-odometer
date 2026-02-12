/**
 * Rotation detection via GPIO interrupt implementation
 */

#include "irq.h"
#include "hardware/gpio.h"

// Module state
static uint8_t sensor_pin = 0;

// Pending rotation counter
// This counter is incremented atomically by the IRQ handler and read-and-cleared
// atomically by irq_read_and_clear_rotations() using __atomic_exchange_n, so no
// interrupt disabling is needed and no rotation counts are ever lost.
static volatile uint32_t pending_rotation_count = 0;

// GPIO IRQ handler for sensor pin
// This must be fast and minimal - just count edges
// Uses atomic operations so no rotation counts are lost
static void gpio_irq_handler(uint gpio, uint32_t events)
{
    // Check if this is our sensor pin
    if (gpio != sensor_pin)
    {
        return;
    }

    // Handle falling edge (sensor goes LOW) - count rotation
    if (events & GPIO_IRQ_EDGE_FALL)
    {
        __atomic_fetch_add(&pending_rotation_count, 1u, __ATOMIC_RELAXED);
    }
}

void irq_init(uint8_t pin)
{
    sensor_pin = pin;

    // Initialize GPIO pin
    gpio_init(sensor_pin);
    gpio_set_dir(sensor_pin, GPIO_IN);
    gpio_pull_up(sensor_pin); // Enable internal pull-up resistor

    // Setup GPIO IRQ for edge detection
    // LED control and rotation counting happen in the IRQ handler
    gpio_set_irq_enabled_with_callback(sensor_pin, GPIO_IRQ_EDGE_RISE | GPIO_IRQ_EDGE_FALL, true, &gpio_irq_handler);
}

uint32_t irq_read_and_clear_rotations(void)
{
    // Atomically read and clear pending rotations
    // No need to disable interrupts - __atomic_exchange_n is a single atomic operation
    return __atomic_exchange_n(&pending_rotation_count, 0u, __ATOMIC_ACQ_REL);
}
