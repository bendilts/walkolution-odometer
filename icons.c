#include "icons.h"

// Bluetooth icon - 6x11 pixels
// Classic Bluetooth symbol - clean and simple design
const uint8_t bluetooth_bitmap[] = {
    0b00100000,
    0b00110000,
    0b00101000,
    0b10100100,
    0b01101000,
    0b00110000,
    0b01101000,
    0b10100100,
    0b00101000,
    0b00110000,
    0b00100000,
};

const Icon icon_bluetooth = {
    .bitmap = bluetooth_bitmap,
    .width = 6,
    .height = 11};

// WiFi icon - 11x9 pixels
// Classic WiFi symbol with three arcs
const uint8_t wifi_bitmap[] = {
    0b00010000, 0b00000000,
    0b01111100, 0b00000000,
    0b10000010, 0b00000000,
    0b00101000, 0b00000000,
    0b01010100, 0b00000000,
    0b00000000, 0b00000000,
    0b00101000, 0b00000000,
    0b00000000, 0b00000000,
    0b00010000, 0b00000000,
};

const Icon icon_wifi = {
    .bitmap = wifi_bitmap,
    .width = 11,
    .height = 9};
