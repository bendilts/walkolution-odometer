/**
 * Voltage monitoring module for Pico W
 */

#ifndef VOLTAGE_H
#define VOLTAGE_H

#include <stdint.h>

// Initialize voltage monitoring
void voltage_init(void);

// Read current VSYS voltage in millivolts
// Returns the voltage reading, or last valid cached reading if current reading is invalid
uint16_t voltage_read(void);

#endif // VOLTAGE_H
