#include "mock_logging.h"
#include <stdio.h>

// Mock implementation - logs are ignored in tests
void logging_init(void) {
    // No-op in tests
}

int log_printf(const char* format, ...) {
    // Optionally print to stdout for debugging tests
    // Uncomment below to see log output during test runs
    /*
    va_list args;
    va_start(args, format);
    int result = vprintf(format, args);
    va_end(args);
    return result;
    */
    return 0;
}

size_t logging_get_new_logs(char* dest_buffer, size_t max_len) {
    return 0;
}

size_t logging_get_available_bytes(void) {
    return 0;
}
