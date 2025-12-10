#ifndef FONT_H
#define FONT_H

#include <stdint.h>

// Adafruit GFX font format - variable-width, professional quality fonts
typedef struct {
    uint16_t bitmap_offset; // Pointer into GFXfont->bitmap
    uint8_t width;          // Bitmap dimensions in pixels
    uint8_t height;
    uint8_t x_advance;      // Distance to advance cursor (x axis)
    int8_t x_offset;        // X offset for glyph
    int8_t y_offset;        // Y offset for glyph
} GFXglyph;

typedef struct {
    const uint8_t *bitmap;  // Glyph bitmaps, concatenated
    const GFXglyph *glyph;  // Glyph array
    uint8_t first;          // ASCII value of first character
    uint8_t last;           // ASCII value of last character
    uint8_t y_advance;      // Newline distance (y axis)
} GFXfont;

// Available fonts (from Adafruit GFX library)
// Tiny font (perfect for indicators, status text)
extern const GFXfont Picopixel;

// Fixed-width 5x7 font (author: Rob Jennings)
extern const GFXfont Font5x7Fixed;

// Regular weight (cleaner, easier to read)
extern const GFXfont FreeSans9pt7b;
extern const GFXfont FreeSans12pt7b;
extern const GFXfont FreeSans18pt7b;
extern const GFXfont FreeSans24pt7b;

// Bold weight (more prominent, better for emphasis)
extern const GFXfont FreeSansBold9pt7b;
extern const GFXfont FreeSansBold12pt7b;
extern const GFXfont FreeSansBold18pt7b;
extern const GFXfont FreeSansBold24pt7b;

#endif // FONT_H
