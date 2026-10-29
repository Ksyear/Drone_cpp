#pragma once
#include <stdint.h>

// Reject truncated control frames before legacy read8/read16 can reuse stale bytes.
inline bool validControlPayload(uint8_t command, uint8_t size) {
    switch (command) {
        case 150: return size == 5;
        case 200: return size == 16;
        case 151:
        case 152: return size == 0;
        default: return true; // shortcut: other legacy commands retain their validation, audit before enabling writes.
    }
}
