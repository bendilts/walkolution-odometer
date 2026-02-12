/**
 * Rotation detection via GPIO interrupt
 *
 * This module handles edge detection on a Hall effect sensor pin using GPIO interrupts
 * and counts rotations.
 */

#ifndef IRQ_H
#define IRQ_H

#include <stdint.h>
#include <stdbool.h>

/**
 * Initialize rotation detection
 *
 * Sets up GPIO interrupts on the sensor pin for edge detection.
 * Rotations are counted on falling edges.
 *
 * @param sensor_pin GPIO pin number connected to the Hall effect sensor
 */
void irq_init(uint8_t sensor_pin);

/**
 * Atomically read and clear pending rotation count
 *
 * This function uses atomic operations to safely read and reset the rotation
 * counter without disabling interrupts. No rotations are lost, even if an
 * interrupt occurs during this call.
 *
 * @return Number of rotations detected since last call
 */
uint32_t irq_read_and_clear_rotations(void);

#endif // IRQ_H
