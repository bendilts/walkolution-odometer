#ifndef LOGGING_H
#define LOGGING_H

#include <stdarg.h>
#include <stddef.h>
#include <stdint.h>

// Initialize the logging system
// Must be called before using any other logging functions
void logging_init(void);

// Printf-style logging function
// Logs to both USB serial (printf) and the circular buffer in RAM
// Returns the number of characters that would have been written (like printf)
int log_printf(const char* format, ...);

// Get new log data accumulated since the last call to this function
// Copies unread logs into dest_buffer (up to max_len bytes)
// Returns the number of bytes copied (0 if no new logs available)
// Thread-safe: can be called from any core
// Note: Each call advances the read pointer, so logs are only returned once
size_t logging_get_new_logs(char* dest_buffer, size_t max_len);

// Get the number of unread bytes currently available in the log buffer
// Useful for checking if there are new logs before calling logging_get_new_logs
// Returns the number of bytes available to read
size_t logging_get_available_bytes(void);

#endif // LOGGING_H
