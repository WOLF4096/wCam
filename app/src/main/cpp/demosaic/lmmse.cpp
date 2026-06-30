#include "demosaic_common.h"
#include <vector>
#include <thread>
#include <future>
#include <algorithm>
#include <cstring>

/**
 * Hyper Optimized LMMSE Demosaicing (v4)
 * Goal: Sub-300ms for 12MP on high-end SoC
 */
void demosaic_lmmse(const DemosaicParams& p, int* pixels) {
    const int width = p.width;
    const int height = p.height;
    const int size = width * height;

    // Cache WB gains locally to avoid potential JNI array overhead
    float wb[4];
    std::memcpy(wb, p.wbGains, 4 * sizeof(float));

    // Intermediate buffers
    std::vector<float> wb_raw(size);
    std::vector<float> full_g(size);

    unsigned int num_threads = std::thread::hardware_concurrency();
    if (num_threads == 0) num_threads = 4;
    int rows_per_thread = height / num_threads;

    // 1. Precompute WB + Green Interpolation (Merged where possible or optimized)
    auto precompute_and_green = [&](int start_y, int end_y) {
        // First Pass: Precompute WB (Very fast, mostly memory bound)
        for (int y = start_y; y < end_y; ++y) {
            const uint8_t* row_ptr = p.rawData + (uint64_t)y * p.rowStride;
            float* dst_row = &wb_raw[y * width];

            // Pre-calculate row-based channel indices
            int c0, c1;
            int y_mod = y & 1;
            switch (p.bayerPattern) {
                case RGGB: c0 = (y_mod << 1);     c1 = c0 | 1; break;
                case GRBG: c0 = y_mod ? 3 : 1;    c1 = y_mod ? 2 : 0; break;
                case GBRG: c0 = y_mod ? 0 : 2;    c1 = y_mod ? 1 : 3; break;
                case BGGR: c0 = y_mod ? 2 : 3;    c1 = y_mod ? 1 : 0; break; // Simplified for demo, actual logic depends on pattern
                default:   c0 = (y_mod << 1);     c1 = c0 | 1;
            }
            // Re-derive c0, c1 correctly for all patterns
            if (p.bayerPattern == GRBG) { c0 = y_mod ? 3 : 1; c1 = y_mod ? 2 : 0; }
            else if (p.bayerPattern == GBRG) { c0 = y_mod ? 0 : 2; c1 = y_mod ? 1 : 3; }
            else if (p.bayerPattern == BGGR) { c0 = y_mod ? 2 : 3; c1 = y_mod ? 1 : 0; } // These need verification vs common.h

            // Re-using the logic from common.h for correctness but outside x loop
            auto get_c = [&](int x, int y) {
                int ix = (x & 1), iy = (y & 1);
                switch (p.bayerPattern) {
                    case RGGB: return (iy << 1) | ix;
                    case GRBG: return iy ? (ix ? 2 : 3) : (ix ? 0 : 1);
                    case GBRG: return iy ? (ix ? 1 : 0) : (ix ? 3 : 2);
                    case BGGR: return iy ? (ix ? 0 : 1) : (ix ? 2 : 3);
                    default: return (iy << 1) | ix;
                }
            };
            c0 = get_c(0, y);
            c1 = get_c(1, y);

            float g0 = wb[c0], g1 = wb[c1];
            float nF = p.normFactor;
            float bL = (float)p.blackLevel;
            int pS = p.pixelStride;

            for (int x = 0; x < width - 1; x += 2) {
                uint16_t v0, v1;
                const uint8_t* p0 = row_ptr + (uint64_t)x * pS;
                std::memcpy(&v0, p0, 2);
                std::memcpy(&v1, p0 + pS, 2);

                dst_row[x]     = std::max(0.0f, (float)v0 - bL) * nF * g0;
                dst_row[x + 1] = std::max(0.0f, (float)v1 - bL) * nF * g1;
            }
        }
    };

    std::vector<std::future<void>> futures;
    for (unsigned int i = 0; i < num_threads; ++i) {
        int start = i * rows_per_thread;
        int end = (i == num_threads - 1) ? height : (i + 1) * rows_per_thread;
        futures.push_back(std::async(std::launch::async, precompute_and_green, start, end));
    }
    for (auto& f : futures) f.get();
    futures.clear();

    // 2. Directional Green Interpolation
    auto interpolate_green = [&](int start_y, int end_y) {
        for (int y = start_y; y < end_y; ++y) {
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
                // Check if this is a Green pixel
                int c = (p.bayerPattern == RGGB) ? ((y_mod << 1) | (x & 1)) :
                        (p.bayerPattern == GRBG) ? (y_mod ? ((x & 1) ? 2 : 3) : ((x & 1) ? 0 : 1)) :
                        (p.bayerPattern == GBRG) ? (y_mod ? ((x & 1) ? 1 : 0) : ((x & 1) ? 3 : 2)) :
                        (y_mod ? ((x & 1) ? 0 : 1) : ((x & 1) ? 2 : 3)); // BGGR

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
    };

    for (unsigned int i = 0; i < num_threads; ++i) {
        int start = i * rows_per_thread;
        int end = (i == num_threads - 1) ? height : (i + 1) * rows_per_thread;
        futures.push_back(std::async(std::launch::async, interpolate_green, start, end));
    }
    for (auto& f : futures) f.get();
    futures.clear();

    // 3. Final Assembly (R/B and conversion)
    auto assemble_final = [&](int start_y, int end_y) {
        for (int y = start_y; y < end_y; ++y) {
            const int off = y * width;
            const int off_m1 = (y > 0 ? y - 1 : 0) * width;
            const int off_p1 = (y < height - 1 ? y + 1 : y) * width;

            const float* r_m1 = &wb_raw[off_m1];
            const float* cur  = &wb_raw[off];
            const float* r_p1 = &wb_raw[off_p1];

            const float* g_m1 = &full_g[off_m1];
            const float* g_cur = &full_g[off];
            const float* g_p1 = &full_g[off_p1];

            int y_mod = y & 1;
            for (int x = 0; x < width; ++x) {
                int c = (p.bayerPattern == RGGB) ? ((y_mod << 1) | (x & 1)) :
                        (p.bayerPattern == GRBG) ? (y_mod ? ((x & 1) ? 2 : 3) : ((x & 1) ? 0 : 1)) :
                        (p.bayerPattern == GBRG) ? (y_mod ? ((x & 1) ? 1 : 0) : ((x & 1) ? 3 : 2)) :
                        (y_mod ? ((x & 1) ? 0 : 1) : ((x & 1) ? 2 : 3)); // BGGR

                float r, g, b;
                g = g_cur[x];
                int xm1 = (x > 0 ? x - 1 : 0);
                int xp1 = (x < width - 1 ? x + 1 : x);

                if (c == 0) { // R
                    r = cur[x];
                    b = g + ((r_m1[xm1] - g_m1[xm1]) + (r_m1[xp1] - g_m1[xp1]) +
                             (r_p1[xm1] - g_p1[xm1]) + (r_p1[xp1] - g_p1[xp1])) * 0.25f;
                } else if (c == 3) { // B
                    b = cur[x];
                    r = g + ((r_m1[xm1] - g_m1[xm1]) + (r_m1[xp1] - g_m1[xp1]) +
                             (r_p1[xm1] - g_p1[xm1]) + (r_p1[xp1] - g_p1[xp1])) * 0.25f;
                } else if (c == 1) { // Gr
                    r = g + ((cur[xm1] - g_cur[xm1]) + (cur[xp1] - g_cur[xp1])) * 0.5f;
                    b = g + ((r_m1[x] - g_m1[x]) + (r_p1[x] - g_p1[x])) * 0.5f;
                } else { // Gb
                    b = g + ((cur[xm1] - g_cur[xm1]) + (cur[xp1] - g_cur[xp1])) * 0.5f;
                    r = g + ((r_m1[x] - g_m1[x]) + (r_p1[x] - g_p1[x])) * 0.5f;
                }
                pixels[off + x] = rgb_to_pixel(r, g, b);
            }
        }
    };

    for (unsigned int i = 0; i < num_threads; ++i) {
        int start = i * rows_per_thread;
        int end = (i == num_threads - 1) ? height : (i + 1) * rows_per_thread;
        futures.push_back(std::async(std::launch::async, assemble_final, start, end));
    }
    for (auto& f : futures) f.get();
}
