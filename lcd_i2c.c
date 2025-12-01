/**
 * Simple I2C LCD driver for HD44780 LCD with PCF8574 I2C backpack
 * Compatible with Raspberry Pi Pico SDK
 */

#include "lcd_i2c.h"
#include "hardware/i2c.h"
#include "pico/stdlib.h"
#include <string.h>
#include <stdio.h>

// LCD commands
#define LCD_CLEARDISPLAY 0x01
#define LCD_RETURNHOME 0x02
#define LCD_ENTRYMODESET 0x04
#define LCD_DISPLAYCONTROL 0x08
#define LCD_FUNCTIONSET 0x20
#define LCD_SETDDRAMADDR 0x80

// Flags for display entry mode
#define LCD_ENTRYLEFT 0x02
#define LCD_ENTRYSHIFTDECREMENT 0x00

// Flags for display on/off control
#define LCD_DISPLAYON 0x04
#define LCD_CURSOROFF 0x00
#define LCD_BLINKOFF 0x00

// Flags for function set
#define LCD_4BITMODE 0x00
#define LCD_2LINE 0x08
#define LCD_5x8DOTS 0x00

// Flags for backlight control
#define LCD_BACKLIGHT 0x08
#define LCD_NOBACKLIGHT 0x00

// PCF8574 pins
#define En 0x04  // Enable bit
#define Rw 0x02  // Read/Write bit
#define Rs 0x01  // Register select bit

// I2C configuration
#define I2C_PORT i2c0
#define I2C_SDA_PIN 4
#define I2C_SCL_PIN 5

static uint8_t lcd_addr;
static uint8_t lcd_cols;
static uint8_t lcd_rows;
static uint8_t backlight_val = LCD_NOBACKLIGHT;

// Display state buffer (max 4 rows x 20 cols)
#define MAX_LCD_COLS 20
#define MAX_LCD_ROWS 4
static char display_buffer[MAX_LCD_ROWS][MAX_LCD_COLS];
static bool buffer_initialized = false;

static void i2c_write_byte(uint8_t val) {
    i2c_write_blocking(I2C_PORT, lcd_addr, &val, 1, false);
}

static void lcd_pulse_enable(uint8_t data) {
    i2c_write_byte(data | En);
    sleep_us(1);
    i2c_write_byte(data & ~En);
    sleep_us(50);
}

static void lcd_write_nibble(uint8_t nibble, uint8_t mode) {
    uint8_t data = (nibble & 0xF0) | mode | backlight_val;
    i2c_write_byte(data);
    lcd_pulse_enable(data);
}

static void lcd_send(uint8_t value, uint8_t mode) {
    lcd_write_nibble(value & 0xF0, mode);
    lcd_write_nibble((value << 4) & 0xF0, mode);
}

static void lcd_command(uint8_t cmd) {
    lcd_send(cmd, 0);
}

static void lcd_write_char(uint8_t ch) {
    lcd_send(ch, Rs);
}

// Internal: Set cursor position (used by buffer update system)
static void lcd_set_cursor(uint8_t col, uint8_t row) {
    static const uint8_t row_offsets[] = { 0x00, 0x40, 0x14, 0x54 };
    if (row >= lcd_rows) {
        row = lcd_rows - 1;
    }
    lcd_command(LCD_SETDDRAMADDR | (col + row_offsets[row]));
}

// Initialize display buffer with spaces
static void init_display_buffer(void) {
    for (uint8_t row = 0; row < MAX_LCD_ROWS; row++) {
        for (uint8_t col = 0; col < MAX_LCD_COLS; col++) {
            display_buffer[row][col] = ' ';
        }
    }
    buffer_initialized = true;
}

// Update a character in the buffer and on display if changed
static void update_char_at(uint8_t col, uint8_t row, char ch) {
    // Bounds checking: prevent buffer overrun
    if (row >= lcd_rows || col >= lcd_cols) return;
    if (row >= MAX_LCD_ROWS || col >= MAX_LCD_COLS) return;

    // Only update if character changed
    if (display_buffer[row][col] != ch) {
        display_buffer[row][col] = ch;
        lcd_set_cursor(col, row);
        lcd_write_char(ch);
    }
}

// Update a string starting at position, only changing what's different
static void update_string_at(uint8_t col, uint8_t row, const char *str) {
    // Bounds checking: prevent buffer overrun
    if (row >= lcd_rows || col >= lcd_cols) return;
    if (row >= MAX_LCD_ROWS || col >= MAX_LCD_COLS) return;
    if (str == NULL) return;

    uint8_t pos = col;
    // Truncate at display width and buffer bounds
    while (*str && pos < lcd_cols && pos < MAX_LCD_COLS) {
        update_char_at(pos, row, *str);
        str++;
        pos++;
    }
}

void lcd_init(uint8_t addr, uint8_t cols, uint8_t rows) {
    lcd_addr = addr;
    lcd_cols = cols;
    lcd_rows = rows;

    // Initialize I2C
    i2c_init(I2C_PORT, 100 * 1000);  // 100 kHz
    gpio_set_function(I2C_SDA_PIN, GPIO_FUNC_I2C);
    gpio_set_function(I2C_SCL_PIN, GPIO_FUNC_I2C);
    gpio_pull_up(I2C_SDA_PIN);
    gpio_pull_up(I2C_SCL_PIN);

    // Wait for LCD to power up (needs >40ms after Vcc rises to 2.7V)
    sleep_ms(100);

    // Start with backlight off to save power on startup
    backlight_val = LCD_NOBACKLIGHT;
    i2c_write_byte(backlight_val);
    sleep_ms(100);

    // Initialize LCD in 4-bit mode (HD44780 standard sequence)
    // Start in 8-bit mode
    lcd_write_nibble(0x30, 0);
    sleep_ms(5);  // Wait >4.1ms

    lcd_write_nibble(0x30, 0);
    sleep_ms(1);  // Wait >100us

    lcd_write_nibble(0x30, 0);
    sleep_us(200);

    // Now switch to 4-bit mode
    lcd_write_nibble(0x20, 0);
    sleep_us(200);

    // Configure display (now in 4-bit mode, so use full commands)
    uint8_t displayfunction = LCD_4BITMODE | LCD_2LINE | LCD_5x8DOTS;
    lcd_command(LCD_FUNCTIONSET | displayfunction);
    sleep_us(100);

    // Turn display on
    uint8_t displaycontrol = LCD_DISPLAYON | LCD_CURSOROFF | LCD_BLINKOFF;
    lcd_command(LCD_DISPLAYCONTROL | displaycontrol);
    sleep_us(100);

    // Clear display
    lcd_clear();

    // Set entry mode
    uint8_t displaymode = LCD_ENTRYLEFT | LCD_ENTRYSHIFTDECREMENT;
    lcd_command(LCD_ENTRYMODESET | displaymode);
    sleep_ms(10);

    // Initialize display buffer
    init_display_buffer();
}

void lcd_reinit(void) {
    // Reinitialize I2C (GPIO pins are preserved across dormant sleep if configured)
    i2c_init(I2C_PORT, 100 * 1000);
    gpio_set_function(I2C_SDA_PIN, GPIO_FUNC_I2C);
    gpio_set_function(I2C_SCL_PIN, GPIO_FUNC_I2C);
    gpio_pull_up(I2C_SDA_PIN);
    gpio_pull_up(I2C_SCL_PIN);

    sleep_ms(100);

    // Preserve backlight state from before sleep (backlight_val is unchanged)
    i2c_write_byte(backlight_val);
    sleep_ms(100);

    // Re-run LCD initialization sequence
    lcd_write_nibble(0x30, 0);
    sleep_ms(5);
    lcd_write_nibble(0x30, 0);
    sleep_ms(1);
    lcd_write_nibble(0x30, 0);
    sleep_us(200);
    lcd_write_nibble(0x20, 0);
    sleep_us(200);

    uint8_t displayfunction = LCD_4BITMODE | LCD_2LINE | LCD_5x8DOTS;
    lcd_command(LCD_FUNCTIONSET | displayfunction);
    sleep_us(100);

    uint8_t displaycontrol = LCD_DISPLAYON | LCD_CURSOROFF | LCD_BLINKOFF;
    lcd_command(LCD_DISPLAYCONTROL | displaycontrol);
    sleep_us(100);

    lcd_clear();

    uint8_t displaymode = LCD_ENTRYLEFT | LCD_ENTRYSHIFTDECREMENT;
    lcd_command(LCD_ENTRYMODESET | displaymode);
    sleep_ms(10);

    // Reinitialize display buffer
    init_display_buffer();
}

void lcd_clear(void) {
    lcd_command(LCD_CLEARDISPLAY);
    sleep_ms(2);

    // Reset buffer after clear
    if (buffer_initialized) {
        init_display_buffer();
    }
}

void lcd_print_at(uint8_t col, uint8_t row, const char *str) {
    update_string_at(col, row, str);
}

void lcd_backlight(bool on) {
    backlight_val = on ? LCD_BACKLIGHT : LCD_NOBACKLIGHT;
    i2c_write_byte(backlight_val);
}
