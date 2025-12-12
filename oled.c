#include "oled.h"
#include <stdio.h>
#include <string.h>
#include "pico/stdlib.h"
#include "pico/multicore.h"
#include "pico/mutex.h"
#include "hardware/i2c.h"

// Display buffer (double buffered for thread safety)
static uint8_t oled_buffer[OLED_WIDTH * OLED_HEIGHT / 8];
static uint8_t oled_render_buffer[OLED_WIDTH * OLED_HEIGHT / 8];

// Thread synchronization
static mutex_t buffer_mutex;
static volatile bool update_in_progress = false;

// Hardware configuration
static i2c_inst_t* i2c_port;
static uint8_t oled_addr;

// Send command to OLED
static void oled_send_cmd(uint8_t cmd) {
    uint8_t buf[2] = {0x00, cmd};
    i2c_write_timeout_us(i2c_port, oled_addr, buf, 2, false, 50000);
}

// Send data byte to OLED
static void oled_send_data(uint8_t data) {
    uint8_t buf[2] = {0x40, data};
    i2c_write_timeout_us(i2c_port, oled_addr, buf, 2, false, 50000);
}

// Hardware initialization for SH1106
static void oled_hw_init(void) {
    oled_send_cmd(0xAE); // Display off
    oled_send_cmd(0x02); // Set lower column address
    oled_send_cmd(0x10); // Set higher column address
    oled_send_cmd(0x40); // Set display start line to 0
    oled_send_cmd(0x81); // Set contrast control
    oled_send_cmd(0xCF); // Contrast value
    oled_send_cmd(0xA1); // Set segment remap
    oled_send_cmd(0xC8); // Set COM output scan direction
    oled_send_cmd(0xA6); // Normal display
    oled_send_cmd(0xA8); // Set multiplex ratio
    oled_send_cmd(0x3F); // 1/64 duty
    oled_send_cmd(0xD3); // Set display offset
    oled_send_cmd(0x00); // No offset
    oled_send_cmd(0xD5); // Set display clock divide ratio
    oled_send_cmd(0x80); // Default ratio
    oled_send_cmd(0xD9); // Set pre-charge period
    oled_send_cmd(0xF1);
    oled_send_cmd(0xDA); // Set COM pins hardware configuration
    oled_send_cmd(0x12);
    oled_send_cmd(0xDB); // Set VCOMH deselect level
    oled_send_cmd(0x40);
    oled_send_cmd(0x8D); // Set DC-DC enable
    oled_send_cmd(0x14); // Enable charge pump
    oled_send_cmd(0xAF); // Display on
}

// Send buffer to display (called from core 1)
static void oled_render(void) {
    for (int page = 0; page < 8; page++) {
        oled_send_cmd(0xB0 + page); // Set page address
        oled_send_cmd(0x02); // Set lower column address
        oled_send_cmd(0x10); // Set higher column address

        for (int x = 0; x < OLED_WIDTH; x++) {
            oled_send_data(oled_render_buffer[x + page * OLED_WIDTH]);
        }
    }
}

// Core 1 entry point - rendering thread
static void core1_entry(void) {
    while (true) {
        // Wait for signal from core 0 (blocks efficiently, no busy-wait)
        multicore_fifo_pop_blocking();

        // Mark as in progress immediately
        update_in_progress = true;

        // Drain FIFO - if multiple frames queued, skip to latest
        uint32_t skipped_frames = 0;
        while (multicore_fifo_rvalid()) {
            multicore_fifo_pop_blocking();
            skipped_frames++;
        }

        if (skipped_frames > 0) {
            printf("[OLED Core 1] Skipped %lu frame%s (can't keep up)\n",
                   skipped_frames, skipped_frames == 1 ? "" : "s");
        }

        // Copy working buffer to render buffer (minimize mutex hold time)
        mutex_enter_blocking(&buffer_mutex);
        memcpy(oled_render_buffer, oled_buffer, sizeof(oled_buffer));
        mutex_exit(&buffer_mutex);

        // Send to display (slow I2C operation, no mutex held)
        oled_render();

        update_in_progress = false;
    }
}

// Public API implementation

void oled_init(void* i2c_inst, uint8_t sda_pin, uint8_t scl_pin, uint8_t addr) {
    i2c_port = (i2c_inst_t*)i2c_inst;
    oled_addr = addr;

    // Initialize I2C at 1MHz (faster than standard 400kHz)
    i2c_init(i2c_port, 1000 * 1000);
    gpio_set_function(sda_pin, GPIO_FUNC_I2C);
    gpio_set_function(scl_pin, GPIO_FUNC_I2C);
    gpio_set_pulls(sda_pin, true, false);
    gpio_set_pulls(scl_pin, true, false);

    // Initialize mutex
    mutex_init(&buffer_mutex);

    // Clear buffers
    memset(oled_buffer, 0, sizeof(oled_buffer));
    memset(oled_render_buffer, 0, sizeof(oled_render_buffer));

    // Initialize hardware
    sleep_ms(100);
    oled_hw_init();

    // Launch rendering thread on core 1
    multicore_launch_core1(core1_entry);
}

void oled_clear(void) {
    mutex_enter_blocking(&buffer_mutex);
    memset(oled_buffer, 0, sizeof(oled_buffer));
    mutex_exit(&buffer_mutex);
}

void oled_set_pixel(int x, int y, bool on) {
    if (x < 0 || x >= OLED_WIDTH || y < 0 || y >= OLED_HEIGHT) {
        return;
    }

    mutex_enter_blocking(&buffer_mutex);
    if (on) {
        oled_buffer[x + (y / 8) * OLED_WIDTH] |= (1 << (y & 7));
    } else {
        oled_buffer[x + (y / 8) * OLED_WIDTH] &= ~(1 << (y & 7));
    }
    mutex_exit(&buffer_mutex);
}

void oled_fill_circle(int x0, int y0, int radius) {
    mutex_enter_blocking(&buffer_mutex);

    // Bresenham-based scanline algorithm - only iterate actual pixels
    int radius_sq = radius * radius;

    for (int y = -radius; y <= radius; y++) {
        int py = y0 + y;
        if (py < 0 || py >= OLED_HEIGHT) continue;

        // Calculate horizontal extent at this y using sqrt
        // x^2 + y^2 = r^2  =>  x = sqrt(r^2 - y^2)
        int y_sq = y * y;
        int dx = 0;

        // Find the max x extent for this scanline (integer sqrt)
        while (dx * dx + y_sq <= radius_sq) {
            dx++;
        }
        dx--; // Back off one

        // Draw horizontal line from -dx to +dx
        int x_start = x0 - dx;
        int x_end = x0 + dx;

        // Clip to screen bounds
        if (x_start < 0) x_start = 0;
        if (x_end >= OLED_WIDTH) x_end = OLED_WIDTH - 1;

        // Pre-calculate byte offset for this row
        int byte_offset = (py / 8) * OLED_WIDTH;
        int bit_mask = 1 << (py & 7);

        // Fill scanline - single bit operation per pixel
        for (int px = x_start; px <= x_end; px++) {
            oled_buffer[px + byte_offset] |= bit_mask;
        }
    }

    mutex_exit(&buffer_mutex);
}

void oled_fill_rect(int x, int y, int width, int height, bool on) {
    mutex_enter_blocking(&buffer_mutex);

    // Clip to screen bounds
    if (x < 0) { width += x; x = 0; }
    if (y < 0) { height += y; y = 0; }
    if (x + width > OLED_WIDTH) width = OLED_WIDTH - x;
    if (y + height > OLED_HEIGHT) height = OLED_HEIGHT - y;
    if (width <= 0 || height <= 0) {
        mutex_exit(&buffer_mutex);
        return;
    }

    int y_end = y + height;
    int x_end = x + width;

    // Find which pages (8-pixel rows) we're affecting
    int start_page = y / 8;
    int end_page = (y_end - 1) / 8;

    for (int page = start_page; page <= end_page; page++) {
        int page_y_start = page * 8;
        int page_y_end = page_y_start + 8;

        // Calculate which bits in this page we need to modify
        int bit_start = (y > page_y_start) ? (y - page_y_start) : 0;
        int bit_end = (y_end < page_y_end) ? (y_end - page_y_start) : 8;

        // Create bit mask for this page
        uint8_t mask = 0;
        for (int bit = bit_start; bit < bit_end; bit++) {
            mask |= (1 << bit);
        }

        // Apply mask to all columns in this page
        int page_offset = page * OLED_WIDTH;
        if (on) {
            // Set bits (fill)
            for (int px = x; px < x_end; px++) {
                oled_buffer[page_offset + px] |= mask;
            }
        } else {
            // Clear bits (erase)
            uint8_t clear_mask = ~mask;
            for (int px = x; px < x_end; px++) {
                oled_buffer[page_offset + px] &= clear_mask;
            }
        }
    }

    mutex_exit(&buffer_mutex);
}

void oled_draw_text(int x, int y, const char *text, const GFXfont *font) {
    if (!text || !font) return;

    mutex_enter_blocking(&buffer_mutex);

    int cursor_x = x;

    while (*text) {
        char c = *text++;

        if (c < font->first || c > font->last) {
            continue;
        }

        const GFXglyph *glyph = &font->glyph[c - font->first];

        int glyph_width = glyph->width;
        int glyph_height = glyph->height;
        int xo = glyph->x_offset;
        int yo = glyph->y_offset;

        // Calculate actual bounds of this character
        int char_left = cursor_x + xo;
        int char_right = char_left + glyph_width;
        int char_top = y + yo;
        int char_bottom = char_top + glyph_height;

        // Early exit: skip character if completely off-screen to the right
        if (char_left >= OLED_WIDTH) {
            break;
        }

        // Skip character if completely off-screen (left, top, or bottom)
        if (char_right <= 0 || char_bottom <= 0 || char_top >= OLED_HEIGHT) {
            cursor_x += glyph->x_advance;
            continue;
        }

        // Character is at least partially visible - render it
        const uint8_t *bitmap = &font->bitmap[glyph->bitmap_offset];
        uint8_t bit = 0;
        uint8_t byte_val = 0;

        for (int yy = 0; yy < glyph_height; yy++) {
            int py = y + yo + yy;

            for (int xx = 0; xx < glyph_width; xx++) {
                if (!(bit++ & 7)) {
                    byte_val = *bitmap++;
                }

                if (byte_val & 0x80) {
                    int px = cursor_x + xo + xx;

                    // Bounds check for individual pixel
                    if (px >= 0 && px < OLED_WIDTH && py >= 0 && py < OLED_HEIGHT) {
                        oled_buffer[px + (py / 8) * OLED_WIDTH] |= (1 << (py & 7));
                    }
                }

                byte_val <<= 1;
            }
        }

        cursor_x += glyph->x_advance;
    }

    mutex_exit(&buffer_mutex);
}

void oled_draw_text_centered(int center_x, int y, const char *text, const GFXfont *font) {
    if (!text || !font) return;

    // Measure the text width
    int text_width;
    oled_measure_text(text, font, &text_width, NULL, NULL);

    // Calculate starting x position to center the text
    int x = center_x - (text_width / 2);

    // Draw the text
    oled_draw_text(x, y, text, font);
}

void oled_update(void) {
    // Only signal if core 1 isn't already rendering (skip frames if behind)
    if (!update_in_progress) {
        // Try to push without blocking - if FIFO is full, skip this frame
        if (multicore_fifo_wready()) {
            multicore_fifo_push_blocking(1);
        }
    }
}

void oled_wait_for_update(void) {
    // Wait for any pending update to complete
    while (update_in_progress) {
        tight_loop_contents();
    }
}

void oled_measure_text(const char *text, const GFXfont *font, int *width, int *ascent, int *descent) {
    if (!text || !font) {
        if (width) *width = 0;
        if (ascent) *ascent = 0;
        if (descent) *descent = 0;
        return;
    }

    int total_width = 0;
    int max_ascent = 0;
    int max_descent = 0;

    while (*text) {
        char c = *text++;

        // Skip characters outside font range
        if (c < font->first || c > font->last) {
            continue;
        }

        const GFXglyph *glyph = &font->glyph[c - font->first];

        // Accumulate width
        total_width += glyph->x_advance;

        // Calculate ascent and descent for this character
        // y_offset is negative for characters above baseline
        // ascent is distance from baseline upward (positive)
        // descent is distance from baseline downward (positive)
        int char_ascent = -glyph->y_offset;
        int char_descent = glyph->height + glyph->y_offset;

        // Track maximum ascent and descent
        if (char_ascent > max_ascent) {
            max_ascent = char_ascent;
        }
        if (char_descent > max_descent) {
            max_descent = char_descent;
        }
    }

    // Return results (only if pointers are non-NULL)
    if (width) *width = total_width;
    if (ascent) *ascent = max_ascent;
    if (descent) *descent = max_descent;
}

void oled_draw_bitmap(int x, int y, const uint8_t *bitmap, int width, int height) {
    if (!bitmap || width <= 0 || height <= 0) return;

    mutex_enter_blocking(&buffer_mutex);

    // Calculate bytes per row (round up to nearest byte)
    int bytes_per_row = (width + 7) / 8;

    for (int row = 0; row < height; row++) {
        int py = y + row;

        // Skip rows that are off-screen
        if (py < 0 || py >= OLED_HEIGHT) continue;

        // Pre-calculate display buffer offset for this row
        int display_offset = (py / 8) * OLED_WIDTH;
        int display_bit = 1 << (py & 7);

        for (int col = 0; col < width; col++) {
            int px = x + col;

            // Skip pixels that are off-screen
            if (px < 0 || px >= OLED_WIDTH) continue;

            // Get bit from bitmap (MSB first)
            int byte_index = row * bytes_per_row + (col / 8);
            int bit_index = 7 - (col & 7);

            if (bitmap[byte_index] & (1 << bit_index)) {
                // Set pixel in display buffer
                oled_buffer[display_offset + px] |= display_bit;
            }
        }
    }

    mutex_exit(&buffer_mutex);
}

void oled_display_on(void) {
    oled_send_cmd(0xAF); // Display on
}

void oled_display_off(void) {
    oled_send_cmd(0xAE); // Display off (sleep mode)
}
