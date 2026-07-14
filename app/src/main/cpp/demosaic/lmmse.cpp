#include "demosaic_common.h"
#include <vector>
#include <thread>
#include <future>
#include <algorithm>
#include <cstring>
#include <omp.h>

/**
 * Hyper Optimized LMMSE Demosaicing (v4)
 * Goal: Sub-300ms for 12MP on high-end SoC
 */
void demosaic_lmmse(const DemosaicParams& p, int* pixels) {
    const int width = p.width;
    const int height = p.height;
    const int size = width * height;

    float wb[4];
    std::memcpy(wb, p.wbGains, 4 * sizeof(float));

    std::vector<float> wb_raw(size);
    std::vector<float> full_g(size);

    // 1. WB Correction
    #pragma omp parallel for schedule(dynamic, 16)
    for (int y = 0; y < height; ++y) {
        const uint8_t* row_ptr = p.rawData + (uint64_t)y * p.rowStride;
        float* dst_row = &wb_raw[y * width];
        int y_mod = y & 1;
        int pS = p.pixelStride;
        float nF = p.normFactor;
        float bL = (float)p.blackLevel;

        for (int x = 0; x < width; ++x) {
            int ix = (x & 1);
            int c;
            switch (p.bayerPattern) {
                case RGGB: c = (y_mod << 1) | ix; break;
                case GRBG: c = y_mod ? (ix ? 2 : 3) : (ix ? 0 : 1); break;
                case GBRG: c = y_mod ? (ix ? 1 : 0) : (ix ? 3 : 2); break;
                case BGGR: c = y_mod ? (ix ? 0 : 1) : (ix ? 2 : 3); break;
                default: c = (y_mod << 1) | ix;
            }
            uint16_t val;
            std::memcpy(&val, row_ptr + (uint64_t)x * pS, 2);
            dst_row[x] = std::max(0.0f, (float)val - bL) * nF * wb[c];
        }
    }

    // 2. Green Interpolation
    #pragma omp parallel for schedule(dynamic, 16)
    for (int y = 0; y < height; ++y) {
        if (y < 2 || y >= height - 2) {
            std::memcpy(&full_g[y * width], &wb_raw[y * width], width * sizeof(float));
            continue;
        }
        const float* r_m2 = &wb_raw[(y - 2) * width];
        const float* r_m1 = &wb_raw[(y - 1) * width];
        const float* cur  = &wb_raw[y * width];
        const float* r_p1 = &wb_raw[(y + 1) * width];
        const float* r_p2 = &wb_raw[(y + 2) * width];
        float* g_out = &full_g[y * width];
        int y_mod = y & 1;

        for (int x = 2; x < width - 2; ++x) {
            int ix = (x & 1);
            int c;
            switch (p.bayerPattern) {
                case RGGB: c = (y_mod << 1) | ix; break;
                case GRBG: c = y_mod ? (ix ? 2 : 3) : (ix ? 0 : 1); break;
                case GBRG: c = y_mod ? (ix ? 1 : 0) : (ix ? 3 : 2); break;
                case BGGR: c = y_mod ? (ix ? 0 : 1) : (ix ? 2 : 3); break;
                default: c = (y_mod << 1) | ix;
            }

            if (c == 1 || c == 2) {
                g_out[x] = cur[x];
            } else {
                float v_grad = std::abs(r_m2[x] - r_p2[x]) + std::abs(cur[x] - r_m1[x]) + std::abs(cur[x] - r_p1[x]);
                float h_grad = std::abs(cur[x - 2] - cur[x + 2]) + std::abs(cur[x] - cur[x - 1]) + std::abs(cur[x] - cur[x + 1]);
                if (h_grad < v_grad) g_out[x] = (cur[x - 1] + cur[x + 1]) * 0.5f;
                else if (v_grad < h_grad) g_out[x] = (r_m1[x] + r_p1[x]) * 0.5f;
                else g_out[x] = (cur[x - 1] + cur[x + 1] + r_m1[x] + r_p1[x]) * 0.25f;
            }
        }
    }

    // 3. Final Assembly
    #pragma omp parallel for schedule(dynamic, 16)
    for (int y = 0; y < height; ++y) {
        int off = y * width;
        int off_m1 = (y > 0 ? y - 1 : 0) * width;
        int off_p1 = (y < height - 1 ? y + 1 : y) * width;
        int y_mod = y & 1;

        for (int x = 0; x < width; ++x) {
            int ix = (x & 1);
            int c;
            switch (p.bayerPattern) {
                case RGGB: c = (y_mod << 1) | ix; break;
                case GRBG: c = y_mod ? (ix ? 2 : 3) : (ix ? 0 : 1); break;
                case GBRG: c = y_mod ? (ix ? 1 : 0) : (ix ? 3 : 2); break;
                case BGGR: c = y_mod ? (ix ? 0 : 1) : (ix ? 2 : 3); break;
                default: c = (y_mod << 1) | ix;
            }

            float r, g, b;
            g = full_g[off + x];
            int xm1 = (x > 0 ? x - 1 : 0);
            int xp1 = (x < width - 1 ? x + 1 : x);

            if (c == 0) { // R
                r = wb_raw[off + x];
                b = g + ((wb_raw[off_m1 + xm1] - full_g[off_m1 + xm1]) + (wb_raw[off_m1 + xp1] - full_g[off_m1 + xp1]) +
                         (wb_raw[off_p1 + xm1] - full_g[off_p1 + xm1]) + (wb_raw[off_p1 + xp1] - full_g[off_p1 + xp1])) * 0.25f;
            } else if (c == 3) { // B
                b = wb_raw[off + x];
                r = g + ((wb_raw[off_m1 + xm1] - full_g[off_m1 + xm1]) + (wb_raw[off_m1 + xp1] - full_g[off_m1 + xp1]) +
                         (wb_raw[off_p1 + xm1] - full_g[off_p1 + xm1]) + (wb_raw[off_p1 + xp1] - full_g[off_p1 + xp1])) * 0.25f;
            } else if (c == 1) { // Gr
                r = g + ((wb_raw[off + xm1] - full_g[off + xm1]) + (wb_raw[off + xp1] - full_g[off + xp1])) * 0.5f;
                b = g + ((wb_raw[off_m1 + x] - full_g[off_m1 + x]) + (wb_raw[off_p1 + x] - full_g[off_p1 + x])) * 0.5f;
            } else { // Gb
                b = g + ((wb_raw[off + xm1] - full_g[off + xm1]) + (wb_raw[off + xp1] - full_g[off + xp1])) * 0.5f;
                r = g + ((wb_raw[off_m1 + x] - full_g[off_m1 + x]) + (wb_raw[off_p1 + x] - full_g[off_p1 + x])) * 0.5f;
            }
            pixels[off + x] = rgb_to_pixel(r, g, b);
        }
    }
}
