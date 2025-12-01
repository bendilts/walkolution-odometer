/**
 * Simple I2C LCD driver for HD44780 LCD with PCF8574 I2C backpack
 * Compatible with Raspberry Pi Pico SDK
 */

#ifndef LCD_I2C_H
#define LCD_I2C_H

#include <stdint.h>
#include <stdbool.h>

// Initialize the LCD
// addr: I2C address (typically 0x27 or 0x3F)
// cols: number of columns (16 or 20)
// rows: number of rows (2 or 4)
void lcd_init(uint8_t addr, uint8_t cols, uint8_t rows);

// Reinitialize the LCD after waking from dormant sleep
void lcd_reinit(void);

// Clear the display (resets buffer)
void lcd_clear(void);

// Smart print: only updates characters that have changed
// col, row: starting position
// str: string to print
void lcd_print_at(uint8_t col, uint8_t row, const char *str);

// Control backlight
void lcd_backlight(bool on);

#endif // LCD_I2C_H
