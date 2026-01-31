#ifndef ICONS_H
#define ICONS_H

#include <stdint.h>

// Icon structure - simple bitmap representation
typedef struct {
    const uint8_t *bitmap;  // Bitmap data (1 bit per pixel, MSB first, row-major)
    uint8_t width;          // Width in pixels
    uint8_t height;         // Height in pixels
} Icon;

// Available icons
extern const Icon icon_bluetooth;

#endif // ICONS_H
