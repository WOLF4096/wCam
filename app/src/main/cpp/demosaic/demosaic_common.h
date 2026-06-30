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
    // Fast path: many values are out of bounds or near zero
    if (v <= 0.0f) return 0;
    if (v >= 1.0f) return 255;

    // index = v * 1023
    int idx = (int)(v * 1023.0f);
    // idx is guaranteed [0, 1022] here
    return (int)(gamma_lut[idx] * 255.0f);
}

inline float get_raw(const DemosaicParams& p, int x, int y) {
    // Fast path for in-bounds
    if (x >= 0 && x < p.width && y >= 0 && y < p.height) {
        uint64_t offset = (uint64_t)y * (uint64_t)p.rowStride + (uint64_t)x * (uint64_t)p.pixelStride;
        const uint8_t* ptr = p.rawData + offset;
        uint16_t val;
        std::memcpy(&val, ptr, 2);
        return std::max(0.0f, (float)val - p.blackLevel) * p.normFactor;
    }

    // Slow path for out-of-bounds
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

// Filmic Tone Mapping to match system camera "Look" (ACES Filmic approximation)
inline float tonemap(float x) {
    if (x <= 0.0f) return 0.0f;
    float a = 2.51f;
    float b = 0.03f;
    float c = 2.43f;
    float d = 0.59f;
    float e = 0.14f;
    float res = (x * (a * x + b)) / (x * (c * x + d) + e);
    return std::clamp(res, 0.0f, 1.0f);
}

inline uint32_t rgb_to_pixel(float r, float g, float b) {
    // 1. 曝光补偿
    float exposure = 1.45f;
    r *= exposure; g *= exposure; b *= exposure;

    // 2. 色彩校正矩阵 (CCM) - 整合了 -2% 的色调 (Hue) 旋转
    float rr =  1.58f * r - 0.35f * g - 0.23f * b;
    float gg = -0.32f * r + 1.49f * g - 0.17f * b;
    float bb =  0.04f * r - 0.64f * g + 1.60f * b;

    // 3. 应用 Tonemap (ACES Filmic)
    rr = tonemap(rr);
    gg = tonemap(gg);
    bb = tonemap(bb);

    // 4. 高光去色 (Highlight Desaturation) - 解决过曝区域偏粉/偏紫问题 (#FDE0FE)
    // 当亮度接近饱和时，强行将颜色向白色（峰值）靠拢
    float peak = std::max({rr, gg, bb});
    if (peak > 0.85f) {
        float blend = std::min(1.0f, (peak - 0.85f) / 0.12f); // 0.85 -> 0.97 快速完成去色
        rr = rr + (peak - rr) * blend;
        gg = gg + (peak - gg) * blend;
        bb = bb + (peak - bb) * blend;
    }

    // 5. 饱和度 - 适度增加饱和度，并对高光区域应用遮罩以保持纯白
    float luma = 0.2126f * rr + 0.7152f * gg + 0.0722f * bb;
    float sat = 1.15f;
    // 高光处线性降低饱和度增益，至峰值时增益归 1.0
    float sat_mask = 1.0f - std::min(1.0f, std::max(0.0f, (peak - 0.80f) / 0.20f));
    float effective_sat = 1.0f + (sat - 1.0f) * sat_mask;

    rr = luma + effective_sat * (rr - luma);
    gg = luma + effective_sat * (gg - luma);
    bb = luma + effective_sat * (bb - luma);

    return 0xFF000000 | (to_srgb(rr) << 16) | (to_srgb(gg) << 8) | to_srgb(bb);
}

#endif // WCAM_DEMOSAIC_COMMON_H
