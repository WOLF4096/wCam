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
    float combinedCCM[9]; // 新增：预乘后的 Sensor-to-sRGB 矩阵
    bool hasCombinedCCM;  // 新增：标记是否已计算
    float* shadingMap;
    int smWidth;
    int smHeight;
    int bayerPattern;
    int blackLevel;
    int whiteLevel;
    float normFactor;
    // LSC Parameters (旧多项式参数保留，用于兜底)
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

inline float get_raw(const DemosaicParams& p, int x, int y) {
    // Fast path for in-bounds
    int cur_x = (x < 0) ? 0 : (x >= p.width ? p.width - 1 : x);
    int cur_y = (y < 0) ? 0 : (y >= p.height ? p.height - 1 : y);

    uint64_t offset = (uint64_t)cur_y * (uint64_t)p.rowStride + (uint64_t)cur_x * (uint64_t)p.pixelStride;
    const uint8_t* ptr = p.rawData + offset;

    // Using simple pointer dereference instead of memcpy for speed,
    // assuming rawData is 2-byte aligned which is standard for Camera2 RAW
    uint16_t val = *reinterpret_cast<const uint16_t*>(ptr);
    float val_f = (val > p.blackLevel) ? (float)(val - p.blackLevel) : 0.0f;

    // --- 新版 LSC: 硬件 LensShadingMap 双线性插值 ---
    if (p.shadingMap != nullptr && p.smWidth > 1 && p.smHeight > 1) {
        // 计算在网格中的相对位置
        float gx = ((float)cur_x * (p.smWidth - 1)) / (p.width - 1);
        float gy = ((float)cur_y * (p.smHeight - 1)) / (p.height - 1);

        int x0 = (int)gx;
        int y0 = (int)gy;
        int x1 = (x0 < p.smWidth - 1) ? x0 + 1 : x0;
        int y1 = (y0 < p.smHeight - 1) ? y0 + 1 : y0;

        float dx = gx - x0;
        float dy = gy - y0;

        int c = get_channel_type(cur_x, cur_y, p.bayerPattern);
        int sm_row_stride = p.smWidth * 4;
        float* sm_row0 = &p.shadingMap[y0 * sm_row_stride];
        float* sm_row1 = &p.shadingMap[y1 * sm_row_stride];

        float g00 = sm_row0[x0 * 4 + c];
        float g10 = sm_row0[x1 * 4 + c];
        float g01 = sm_row1[x0 * 4 + c];
        float g11 = sm_row1[x1 * 4 + c];

        float raw_gain = (1.0f - dx) * (1.0f - dy) * g00 +
                         dx * (1.0f - dy) * g10 +
                         (1.0f - dx) * dy * g01 +
                         dx * dy * g11;

        float gain = 1.0f + (raw_gain - 1.0f) * p.lscStrength;
        val_f *= gain;
    }

    // --- 兜底 LSC: 旧版多项式模型 ---
    else if (p.lscStrength > 0.0f) {
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
    // 1. 基础增益微调
    float exposure = 1.06f;
    r *= exposure; g *= exposure; b *= exposure;

    // 2. 核心色彩转换 (使用预计算矩阵，减少 18 次乘法)
    float rr, gg, bb;
    if (p.hasCombinedCCM) {
        rr = p.combinedCCM[0] * r + p.combinedCCM[1] * g + p.combinedCCM[2] * b;
        gg = p.combinedCCM[3] * r + p.combinedCCM[4] * g + p.combinedCCM[5] * b;
        bb = p.combinedCCM[6] * r + p.combinedCCM[7] * g + p.combinedCCM[8] * b;
    } else {
        // 兜底：Sensor-to-sRGB 经验矩阵
        rr =  1.65f * r - 0.55f * g - 0.10f * b;
        gg = -0.25f * r + 1.55f * g - 0.30f * b;
        bb = -0.05f * r - 0.65f * g + 1.70f * b;
    }

    // 3. 负值剪裁
    rr = (rr > 0.0f) ? rr : 0.0f;
    gg = (gg > 0.0f) ? gg : 0.0f;
    bb = (bb > 0.0f) ? bb : 0.0f;

    // 4. 应用 Tonemap (ACES Filmic)
    rr = tonemap(rr);
    gg = tonemap(gg);
    bb = tonemap(bb);

    // 5. 动态高光保护 (修复“天空发灰”)
    float peak = std::max({rr, gg, bb});
    if (peak > 0.88f) { // 阈值从 0.70 提升至 0.88
        // 只对极亮区域进行去色，保持天空的蓝色纯度
        float blend = std::min(1.0f, (peak - 0.88f) / 0.12f);
        float gray = 0.2126f * rr + 0.7152f * gg + 0.0722f * bb;
        rr = rr * (1.0f - blend) + gray * blend;
        gg = gg * (1.0f - blend) + gray * blend;
        bb = bb * (1.0f - blend) + gray * blend;

        // 极亮处向纯白收敛
        rr = rr + (peak - rr) * blend * 0.8f;
        gg = gg + (peak - gg) * blend * 0.8f;
        bb = bb + (peak - bb) * blend * 0.8f;
    }

    // 6. 最终色彩鲜艳度调优 (修复“颜色平淡”)
    float luma = 0.2126f * rr + 0.7152f * gg + 0.0722f * bb;
    float sat = 1.12f; // 适度提升整体色彩活力
    rr = luma + sat * (rr - luma);
    gg = luma + sat * (gg - luma);
    bb = luma + sat * (bb - luma);

    return 0xFF000000 | (to_srgb(rr) << 16) | (to_srgb(gg) << 8) | to_srgb(bb);
}

// Wrapper to match previous calls if any
#define rgb_to_pixel(r,g,b) rgb_to_pixel_impl(r,g,b,p)

#endif // WCAM_DEMOSAIC_COMMON_H
