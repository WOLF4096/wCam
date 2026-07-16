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
    int width;
    int height;
    int rowStride;
    int pixelStride;
    float* wbGains;
    float* ccm; // 新增 CCM 矩阵 (9 elements)
    int bayerPattern;
    int blackLevel;
    int whiteLevel;
    float normFactor;
    // LSC Parameters
    float lscStrength;
    float opticalCenterX;
    float opticalCenterY;
    float maxDistSq;
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
    if (__builtin_expect(x >= 0 && x < p.width && y >= 0 && y < p.height, 1)) {
        uint64_t offset = (uint64_t)y * (uint64_t)p.rowStride + (uint64_t)x * (uint64_t)p.pixelStride;
        const uint8_t* ptr = p.rawData + offset;
        uint16_t val;
        std::memcpy(&val, ptr, 2);
        float val_f = std::max(0.0f, (float)val - p.blackLevel);

        // Apply Lens Shading Correction (LSC)
        if (p.lscStrength > 0.0f) {
            float dx = (float)x - p.opticalCenterX;
            float dy = (float)y - p.opticalCenterY;
            float distSq = dx * dx + dy * dy;
            float r2 = distSq / p.maxDistSq;
            float r4 = r2 * r2;
            float r6 = r4 * r2;
            float lscGain = 1.0f + p.lscStrength * (r2 + r4 + r6);
            val_f *= lscGain;
        }
        return val_f * p.normFactor;
    } else {
        // Slow path for out-of-bounds
        if (p.width <= 0 || p.height <= 0) return 0.0f;
        int cur_x = std::clamp(x, 0, p.width - 1);
        int cur_y = std::clamp(y, 0, p.height - 1);

        uint64_t offset = (uint64_t)cur_y * (uint64_t)p.rowStride + (uint64_t)cur_x * (uint64_t)p.pixelStride;
        const uint8_t* ptr = p.rawData + offset;
        uint16_t val;
        std::memcpy(&val, ptr, 2);
        float val_f = std::max(0.0f, (float)val - p.blackLevel);

        if (p.lscStrength > 0.0f) {
            float dx = (float)cur_x - p.opticalCenterX;
            float dy = (float)cur_y - p.opticalCenterY;
            float distSq = dx * dx + dy * dy;
            float r2 = distSq / p.maxDistSq;
            float r4 = r2 * r2;
            float r6 = r4 * r2;
            float lscGain = 1.0f + p.lscStrength * (r2 + r4 + r6);
            val_f *= lscGain;
        }
        return val_f * p.normFactor;
    }
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

inline uint32_t rgb_to_pixel_impl(float r, float g, float b, const DemosaicParams& p) {
    // 1. 基础曝光微调 (略微降低，防止高光过早溢出)
    float exposure = 1.0f;
    r *= exposure; g *= exposure; b *= exposure;

    // 2. 核心色彩转换
    float rr, gg, bb;
    if (p.ccm != nullptr && (p.ccm[0] != 0.0f || p.ccm[4] != 0.0f)) {
        // A. 转换到 CIE XYZ 空间 (应用硬件 ForwardMatrix)
        float X = p.ccm[0] * r + p.ccm[1] * g + p.ccm[2] * b;
        float Y = p.ccm[3] * r + p.ccm[4] * g + p.ccm[5] * b;
        float Z = p.ccm[6] * r + p.ccm[7] * g + p.ccm[8] * b;

        // B. 从 CIE XYZ 转换到 Linear sRGB (标准 D65 转换矩阵)
        rr =  3.2404542f * X - 1.5371385f * Y - 0.4985314f * Z;
        gg = -0.9692660f * X + 1.8760108f * Y + 0.0415560f * Z;
        bb =  0.0556434f * X - 0.2040259f * Y + 1.0572252f * Z;
    } else {
        // 兜底：如果没有矩阵，使用简单的经验矩阵 (Sensor-to-sRGB 直接转换)
        rr =  1.60f * r - 0.50f * g - 0.10f * b;
        gg = -0.20f * r + 1.50f * g - 0.30f * b;
        bb = -0.10f * r - 0.60f * g + 1.70f * b;
    }

    // 3. 饱和度初步控制 (防止硬件矩阵过冲)
    rr = std::max(0.0f, rr); gg = std::max(0.0f, gg); bb = std::max(0.0f, bb);

    // 4. 应用 Tonemap (ACES Filmic)
    rr = tonemap(rr);
    gg = tonemap(gg);
    bb = tonemap(bb);

    // 5. 增强型高光保护与去色
    float peak = std::max({rr, gg, bb});
    if (peak > 0.70f) {
        // 在接近高光时，强行降低饱和度并向中性色收敛
        float blend = std::min(1.0f, (peak - 0.70f) / 0.28f);
        float gray = 0.2126f * rr + 0.7152f * gg + 0.0722f * bb;
        rr = rr * (1.0f - blend) + gray * blend;
        gg = gg * (1.0f - blend) + gray * blend;
        bb = bb * (1.0f - blend) + gray * blend;
        // 再次向峰值收敛以确保纯白
        rr = rr + (peak - rr) * blend * 0.5f;
        gg = gg + (peak - gg) * blend * 0.5f;
        bb = bb + (peak - bb) * blend * 0.5f;
    }

    // 6. 最终色彩微调 (1.05x 饱和度)
    float luma = 0.2126f * rr + 0.7152f * gg + 0.0722f * bb;
    float sat = 1.05f;
    rr = luma + sat * (rr - luma);
    gg = luma + sat * (gg - luma);
    bb = luma + sat * (bb - luma);

    return 0xFF000000 | (to_srgb(rr) << 16) | (to_srgb(gg) << 8) | to_srgb(bb);
}

// Wrapper to match previous calls if any
#define rgb_to_pixel(r,g,b) rgb_to_pixel_impl(r,g,b,p)

#endif // WCAM_DEMOSAIC_COMMON_H
