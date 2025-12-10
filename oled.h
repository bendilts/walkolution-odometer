#ifndef OLED_H
#define OLED_H

#include <stdint.h>
#include <stdbool.h>
#include "font.h"

// OLED display dimensions
#define OLED_WIDTH 128
#define OLED_HEIGHT 64

// Initialize OLED module and start rendering thread on core 1
// i2c_port: The I2C instance to use (i2c0 or i2c1)
// sda_pin: GPIO pin for SDA
// scl_pin: GPIO pin for SCL
// addr: I2C address of the display (typically 0x3C or 0x3D)
void oled_init(void* i2c_port, uint8_t sda_pin, uint8_t scl_pin, uint8_t addr);

// Clear the entire display buffer
void oled_clear(void);

// Set a single pixel (thread-safe)
void oled_set_pixel(int x, int y, bool on);

// Draw a filled circle (thread-safe)
void oled_fill_circle(int x0, int y0, int radius);

// Draw a filled rectangle (thread-safe, highly optimized)
// x, y: top-left corner
// width, height: dimensions
// on: true to fill, false to clear
void oled_fill_rect(int x, int y, int width, int height, bool on);

// Draw text using Adafruit GFX font (variable-width, professional quality)
// x, y: baseline position (note: y is the baseline, not top-left!)
// text: null-terminated string to draw
// font: pointer to GFXfont structure (e.g., &FreeSansBold12pt7b)
void oled_draw_text(int x, int y, const char *text, const GFXfont *font);

// Draw centered text using Adafruit GFX font
// center_x: x-coordinate at which to center the text (e.g., OLED_WIDTH/2)
// y: baseline position
// text: null-terminated string to draw
// font: pointer to GFXfont structure
void oled_draw_text_centered(int center_x, int y, const char *text, const GFXfont *font);

// Measure text dimensions without rendering
// text: null-terminated string to measure
// font: pointer to GFXfont structure
// width: pointer to store total width (can be NULL)
// ascent: pointer to store ascent above baseline (can be NULL)
// descent: pointer to store descent below baseline (can be NULL)
void oled_measure_text(const char *text, const GFXfont *font, int *width, int *ascent, int *descent);

// Draw a bitmap/icon
// x, y: top-left corner position
// bitmap: pointer to bitmap data (1 bit per pixel, packed in bytes, row-major)
// width, height: dimensions of the bitmap in pixels
// Bitmap format: MSB first, rows packed into bytes (e.g., 12x12 icon = 12 bytes per row, rounded up)
void oled_draw_bitmap(int x, int y, const uint8_t *bitmap, int width, int height);

// Request a display update (non-blocking - signals core 1 to send data)
void oled_update(void);

// Wait for any pending update to complete (optional, for synchronization)
void oled_wait_for_update(void);

#endif // OLED_H
