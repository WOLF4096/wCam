#ifndef WCAM_DEMOSAIC_COMMON_H
#define WCAM_DEMOSAIC_COMMON_H

#include <stdint.h>
#include <algorithm>
#include <cmath>
#include <vector>
#include <cstring>

// Bayer Patterns
enum BayerPattern {
    RGGB = 0,
    GRBG = 1,
    GBRG = 2,
    BGGR = 3
};

struct DemosaicParams {
    uint8_t* rawData;
    uint64_t rawDataSize; // Added for safety
    int width;
    int height;
    int rowStride;
    int pixelStride;
    float* wbGains;
    int bayerPattern;
    int blackLevel;
    int whiteLevel;
    float normFactor;
};

extern float gamma_lut[1024];
extern bool lut_init_flag;

void init_lut();

inline int to_srgb(float v) {
    if (!lut_init_flag) init_lut();
    int idx = std::clamp((int)(v * 1023.0f), 0, 1023);
    return std::clamp((int)(gamma_lut[idx] * 255.0f), 0, 255);
}

inline float get_raw(const DemosaicParams& p, int x, int y) {
    if (p.width <= 0 || p.height <= 0) return 0.0f;
    x = std::clamp(x, 0, p.width - 1);
    y = std::clamp(y, 0, p.height - 1);

    if (p.rawData == nullptr) return 0.0f;

    uint64_t offset = (uint64_t)y * (uint64_t)p.rowStride + (uint64_t)x * (uint64_t)p.pixelStride;

    // Safety check against buffer size
    if (offset + 1 >= p.rawDataSize) {
        return 0.0f;
    }

    const uint8_t* ptr = p.rawData + offset;
    uint16_t val = 0;
    // Use memcpy for safe unaligned read
    std::memcpy(&val, ptr, 2);

    return std::max(0.0f, (float)val - p.blackLevel) * p.normFactor;
}

inline int get_channel_type(int x, int y, int bayerPattern) {
    // Ensure x and y are treated as positive for bitwise ops
    // 0:R, 1:Gr, 2:Gb, 3:B
    int ix = (x & 1);
    int iy = (y & 1);
    switch (bayerPattern) {
        case RGGB: return (iy << 1) | ix;
        case GRBG: return iy ? (ix ? 2 : 3) : (ix ? 0 : 1);
        case GBRG: return iy ? (ix ? 1 : 0) : (ix ? 3 : 2);
        case BGGR: return iy ? (ix ? 0 : 1) : (ix ? 2 : 3);
        default: return (iy << 1) | ix;
    }
}

// Get raw value with Bayer consistency: returns the nearest pixel of the same channel
inline float get_raw_safe(const DemosaicParams& p, int x, int y, int targetChannel) {
    // Adjust x, y to nearest pixel of targetChannel if necessary
    // This is a simplified version; for better quality, mirrored padding is preferred.
    x = std::clamp(x, 0, p.width - 1);
    y = std::clamp(y, 0, p.height - 1);

    // If the clamped pixel isn't the right channel, move it by 1
    if (get_channel_type(x, y, p.bayerPattern) != targetChannel) {
        // Try adjusting x first
        int nx = x + (x == 0 ? 1 : -1);
        if (nx >= 0 && nx < p.width && get_channel_type(nx, y, p.bayerPattern) == targetChannel) {
            x = nx;
        } else {
            int ny = y + (y == 0 ? 1 : -1);
            if (ny >= 0 && ny < p.height && get_channel_type(x, ny, p.bayerPattern) == targetChannel) {
                y = ny;
            } else if (nx >= 0 && nx < p.width && ny >= 0 && ny < p.height) {
                x = nx; y = ny;
            }
        }
    }
    return get_raw(p, x, y);
}

inline float get_pixel_wb(const DemosaicParams& p, int x, int y) {
    // Correctly handle boundary with bayer consistency
    int targetChannel = get_channel_type(x, y, p.bayerPattern);
    float val = get_raw_safe(p, x, y, targetChannel);
    return val * p.wbGains[targetChannel];
}

inline uint32_t rgb_to_pixel(float r, float g, float b) {
    // Simple color matrix for better look
    float rr = 1.4f * r - 0.3f * g - 0.1f * b;
    float gg = -0.1f * r + 1.2f * g - 0.1f * b;
    float bb = -0.1f * r - 0.3f * g + 1.4f * b;
    return 0xFF000000 | (to_srgb(rr) << 16) | (to_srgb(gg) << 8) | to_srgb(bb);
}

#endif // WCAM_DEMOSAIC_COMMON_H
