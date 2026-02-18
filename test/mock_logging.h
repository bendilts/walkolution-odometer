#ifndef MOCK_LOGGING_H
#define MOCK_LOGGING_H

#include <stdarg.h>
#include <stddef.h>
#include <stdint.h>

// Mock implementation of logging for unit tests
// These functions do nothing in tests

void logging_init(void);
int log_printf(const char* format, ...);
size_t logging_get_new_logs(char* dest_buffer, size_t max_len);
size_t logging_get_available_bytes(void);

#endif // MOCK_LOGGING_H
