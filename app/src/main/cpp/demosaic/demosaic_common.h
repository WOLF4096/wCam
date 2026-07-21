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
    float* ccm;
    float combinedCCM[9];
    bool hasCombinedCCM;
    float* shadingMap;
    int smWidth;
    int smHeight;
    int bayerPattern;
    int blackLevel;
    int whiteLevel;
    float normFactor;
    float lscStrength;
    float opticalCenterX;
    float opticalCenterY;
    float maxDistSq;
    bool highBitDepth;
};

extern float gamma_lut[1024];
extern bool lut_init_flag;

void init_lut();

inline float to_srgb_f(float v) {
    if (v <= 0.0031308f) return 12.92f * v;
    return 1.055f * pow(v, 1.0f / 2.4f) - 0.055f;
}

inline int to_srgb(float v) {
    if (v <= 0.0f) return 0;
    if (v >= 1.0f) return 255;
    int idx = (int)(v * 1023.0f);
    return (int)(gamma_lut[idx] * 255.0f);
}

inline int get_channel_type(int x, int y, int bayerPattern) {
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

inline float get_raw(const DemosaicParams& p, int x, int y) {
    int cur_x = (x < 0) ? 0 : (x >= p.width ? p.width - 1 : x);
    int cur_y = (y < 0) ? 0 : (y >= p.height ? p.height - 1 : y);
    uint64_t offset = (uint64_t)cur_y * (uint64_t)p.rowStride + (uint64_t)cur_x * (uint64_t)p.pixelStride;
    const uint8_t* ptr = p.rawData + offset;
    uint16_t val = *reinterpret_cast<const uint16_t*>(ptr);
    float val_f = (val > p.blackLevel) ? (float)(val - p.blackLevel) : 0.0f;

    if (p.shadingMap != nullptr && p.smWidth > 1 && p.smHeight > 1) {
        float gx = ((float)cur_x * (p.smWidth - 1)) / (p.width - 1);
        float gy = ((float)cur_y * (p.smHeight - 1)) / (p.height - 1);
        int x0 = (int)gx; int y0 = (int)gy;
        int x1 = (x0 < p.smWidth - 1) ? x0 + 1 : x0;
        int y1 = (y0 < p.smHeight - 1) ? y0 + 1 : y0;
        float dx = gx - x0; float dy = gy - y0;
        int c = get_channel_type(cur_x, cur_y, p.bayerPattern);
        int sm_row_stride = p.smWidth * 4;
        float* sm_row0 = &p.shadingMap[y0 * sm_row_stride];
        float* sm_row1 = &p.shadingMap[y1 * sm_row_stride];
        float g00 = sm_row0[x0 * 4 + c]; float g10 = sm_row0[x1 * 4 + c];
        float g01 = sm_row1[x0 * 4 + c]; float g11 = sm_row1[x1 * 4 + c];
        float raw_gain = (1.0f - dx) * (1.0f - dy) * g00 + dx * (1.0f - dy) * g10 + (1.0f - dx) * dy * g01 + dx * dy * g11;
        val_f *= (1.0f + (raw_gain - 1.0f) * p.lscStrength);
    } else if (p.lscStrength > 0.0f) {
        float dx = (float)cur_x - p.opticalCenterX;
        float dy = (float)cur_y - p.opticalCenterY;
        float distSq = dx * dx + dy * dy;
        float r2 = distSq / p.maxDistSq;
        val_f *= (1.0f + p.lscStrength * (r2 + r2*r2 + r2*r2*r2));
    }
    return val_f * p.normFactor;
}

inline float get_raw_safe(const DemosaicParams& p, int x, int y, int targetChannel) {
    x = std::clamp(x, 0, p.width - 1);
    y = std::clamp(y, 0, p.height - 1);
    if (get_channel_type(x, y, p.bayerPattern) != targetChannel) {
        int nx = x + (x == 0 ? 1 : -1);
        if (nx >= 0 && nx < p.width && get_channel_type(nx, y, p.bayerPattern) == targetChannel) { x = nx; }
        else {
            int ny = y + (y == 0 ? 1 : -1);
            if (ny >= 0 && ny < p.height && get_channel_type(x, ny, p.bayerPattern) == targetChannel) { y = ny; }
            else if (nx >= 0 && nx < p.width && ny >= 0 && ny < p.height) { x = nx; y = ny; }
        }
    }
    return get_raw(p, x, y);
}

inline float get_pixel_wb(const DemosaicParams& p, int x, int y) {
    int targetChannel = get_channel_type(x, y, p.bayerPattern);
    return get_raw_safe(p, x, y, targetChannel) * p.wbGains[targetChannel];
}

inline float tonemap(float x) {
    if (x <= 0.0f) return 0.0f;
    float a = 2.51f; float b = 0.03f; float c = 2.43f; float d = 0.59f; float e = 0.14f;
    float res = (x * (a * x + b)) / (x * (c * x + d) + e);
    return std::clamp(res, 0.0f, 1.0f);
}

inline uint16_t float_to_half(float f) {
    uint32_t i = *(uint32_t*)&f;
    uint32_t s = (i >> 16) & 0x8000;
    int32_t e = ((i >> 23) & 0xff) - 127;
    uint32_t m = i & 0x7fffff;

    if (e <= -15) {
        if (e < -24) return (uint16_t)s;
        m |= 0x800000;
        int32_t t = 14 - e;
        m >>= t;
        return (uint16_t)(s | m);
    } else if (e >= 16) {
        // Prevent Infinity/NaN by clamping to max half-float value (0x7bff)
        return (uint16_t)(s | 0x7bff);
    }

    // Normal case: safely shift and handle precision
    return (uint16_t)(s | ((e + 15) << 10) | (m >> 13));
}

inline uint32_t rgb_to_pixel_8bit(float r, float g, float b, const DemosaicParams& p) {
    float exposure = 1.0f; r *= exposure; g *= exposure; b *= exposure;
    float rr, gg, bb;
    if (p.hasCombinedCCM) {
        rr = p.combinedCCM[0] * r + p.combinedCCM[1] * g + p.combinedCCM[2] * b;
        gg = p.combinedCCM[3] * r + p.combinedCCM[4] * g + p.combinedCCM[5] * b;
        bb = p.combinedCCM[6] * r + p.combinedCCM[7] * g + p.combinedCCM[8] * b;
    } else {
        rr = 1.65f * r - 0.55f * g - 0.10f * b;
        gg = -0.25f * r + 1.55f * g - 0.30f * b;
        bb = -0.05f * r - 0.65f * g + 1.70f * b;
    }
    rr = tonemap(std::max(0.0f, rr)); gg = tonemap(std::max(0.0f, gg)); bb = tonemap(std::max(0.0f, bb));
    float peak = std::max({rr, gg, bb});
    if (peak > 0.88f) {
        float blend = std::min(1.0f, (peak - 0.88f) / 0.12f);
        float gray = 0.2126f * rr + 0.7152f * gg + 0.0722f * bb;
        rr = rr * (1.0f - blend) + gray * blend; gg = gg * (1.0f - blend) + gray * blend; bb = bb * (1.0f - blend) + gray * blend;
        rr = rr + (peak - rr) * blend * 0.8f; gg = gg + (peak - gg) * blend * 0.8f; bb = bb + (peak - bb) * blend * 0.8f;
    }
    float luma = 0.2126f * rr + 0.7152f * gg + 0.0722f * bb;
    float sat = 1.15f; rr = luma + sat * (rr - luma); gg = luma + sat * (gg - luma); bb = luma + sat * (bb - luma);
    return 0xFF000000 | (to_srgb(bb) << 16) | (to_srgb(gg) << 8) | to_srgb(rr);
}

inline uint64_t rgb_to_pixel_16bit(float r, float g, float b, const DemosaicParams& p) {
    float exposure = 1.0f; r *= exposure; g *= exposure; b *= exposure;
    float rr, gg, bb;
    if (p.hasCombinedCCM) {
        rr = p.combinedCCM[0] * r + p.combinedCCM[1] * g + p.combinedCCM[2] * b;
        gg = p.combinedCCM[3] * r + p.combinedCCM[4] * g + p.combinedCCM[5] * b;
        bb = p.combinedCCM[6] * r + p.combinedCCM[7] * g + p.combinedCCM[8] * b;
    } else {
        rr = 1.65f * r - 0.55f * g - 0.10f * b;
        gg = -0.25f * r + 1.55f * g - 0.30f * b;
        bb = -0.05f * r - 0.65f * g + 1.70f * b;
    }

    rr = tonemap(std::max(0.0f, rr)); gg = tonemap(std::max(0.0f, gg)); bb = tonemap(std::max(0.0f, bb));
    float peak = std::max({rr, gg, bb});
    if (peak > 0.88f) {
        float blend = std::min(1.0f, (peak - 0.88f) / 0.12f);
        float gray = 0.2126f * rr + 0.7152f * gg + 0.0722f * bb;
        rr = rr * (1.0f - blend) + gray * blend; gg = gg * (1.0f - blend) + gray * blend; bb = bb * (1.0f - blend) + gray * blend;
        rr = rr + (peak - rr) * blend * 0.8f; gg = gg + (peak - gg) * blend * 0.8f; bb = bb + (peak - bb) * blend * 0.8f;
    }
    float luma = 0.2126f * rr + 0.7152f * gg + 0.0722f * bb;
    float sat = 1.15f; rr = luma + sat * (rr - luma); gg = luma + sat * (gg - luma); bb = luma + sat * (bb - luma);

    // Apply sRGB Gamma and clamp to safe floating point range
    // 1.0f is full white, but half-float can technically go much higher.
    // We clamp to a safe range to ensure the encoder never sees an "out of range" value.
    float sR = to_srgb_f(std::clamp(rr, 0.0f, 60000.0f));
    float sG = to_srgb_f(std::clamp(gg, 0.0f, 60000.0f));
    float sB = to_srgb_f(std::clamp(bb, 0.0f, 60000.0f));

    return (uint64_t)float_to_half(sR) | ((uint64_t)float_to_half(sG) << 16) | ((uint64_t)float_to_half(sB) << 32) | ((uint64_t)float_to_half(1.0f) << 48);
}

inline void write_pixel(const DemosaicParams& p, void* pixels, int index, float r, float g, float b) {
    if (p.highBitDepth) {
        ((uint64_t*)pixels)[index] = rgb_to_pixel_16bit(r, g, b, p);
    } else {
        ((uint32_t*)pixels)[index] = rgb_to_pixel_8bit(r, g, b, p);
    }
}

#endif // WCAM_DEMOSAIC_COMMON_H
