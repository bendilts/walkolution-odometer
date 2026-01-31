#include "logging.h"
#include "pico/sync.h"
#include <stdio.h>
#include <stdarg.h>
#include <string.h>

// Circular buffer configuration
#define LOG_BUFFER_SIZE (64 * 1024)  // 64KB buffer

// Circular buffer state
static char log_buffer[LOG_BUFFER_SIZE];
static volatile uint32_t write_index = 0;  // Current write position
static volatile uint32_t read_index = 0;   // Last read position (for get_new_logs)
static mutex_t log_mutex;

// Initialize the logging system
void logging_init(void) {
    mutex_init(&log_mutex);
    write_index = 0;
    read_index = 0;
    memset(log_buffer, 0, LOG_BUFFER_SIZE);
}

// Internal function to write data to the circular buffer
static void write_to_buffer(const char* data, size_t len) {
    mutex_enter_blocking(&log_mutex);

    for (size_t i = 0; i < len; i++) {
        log_buffer[write_index] = data[i];
        write_index = (write_index + 1) % LOG_BUFFER_SIZE;

        // If we've wrapped around and caught up to the read index,
        // move the read index forward to avoid reading stale/overwritten data
        if (write_index == read_index) {
            read_index = (read_index + 1) % LOG_BUFFER_SIZE;
        }
    }

    mutex_exit(&log_mutex);
}

// Printf-style logging function
// Logs to both stdout (USB serial) and the circular buffer
int log_printf(const char* format, ...) {
    char temp_buffer[256];  // Temporary buffer for formatting
    va_list args;

    // Format the string
    va_start(args, format);
    int result = vsnprintf(temp_buffer, sizeof(temp_buffer), format, args);
    va_end(args);

    // Output to stdout (USB serial)
    printf("%s", temp_buffer);

    // Also store in circular buffer (truncate if too long)
    size_t len = (result < sizeof(temp_buffer)) ? result : (sizeof(temp_buffer) - 1);
    write_to_buffer(temp_buffer, len);

    return result;
}

// Get logs accumulated since the last call to this function
// Returns number of bytes copied, or 0 if no new logs
// dest_buffer must be at least max_len bytes
size_t logging_get_new_logs(char* dest_buffer, size_t max_len) {
    if (dest_buffer == NULL || max_len == 0) {
        return 0;
    }

    mutex_enter_blocking(&log_mutex);

    size_t available = 0;
    uint32_t current_read = read_index;
    uint32_t current_write = write_index;

    // Calculate how many bytes are available
    if (current_write >= current_read) {
        available = current_write - current_read;
    } else {
        available = (LOG_BUFFER_SIZE - current_read) + current_write;
    }

    // Limit to the destination buffer size
    size_t to_copy = (available < max_len) ? available : max_len;

    if (to_copy == 0) {
        mutex_exit(&log_mutex);
        return 0;
    }

    // Copy data from circular buffer
    size_t copied = 0;
    while (copied < to_copy) {
        dest_buffer[copied] = log_buffer[current_read];
        current_read = (current_read + 1) % LOG_BUFFER_SIZE;
        copied++;
    }

    // Update read index to mark these logs as read
    read_index = current_read;

    mutex_exit(&log_mutex);

    return copied;
}

// Get the total number of unread bytes in the log buffer
size_t logging_get_available_bytes(void) {
    mutex_enter_blocking(&log_mutex);

    size_t available = 0;
    uint32_t current_write = write_index;
    uint32_t current_read = read_index;

    if (current_write >= current_read) {
        available = current_write - current_read;
    } else {
        available = (LOG_BUFFER_SIZE - current_read) + current_write;
    }

    mutex_exit(&log_mutex);

    return available;
}
