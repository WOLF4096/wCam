#include "demosaic_common.h"
#include <vector>
#include <algorithm>
#include <cmath>

void demosaic_rcd(const DemosaicParams& p, int* pixels) {
    const int width = p.width;
    const int height = p.height;
    const int size = width * height;

    std::vector<float> wb_raw(size);
    std::vector<float> full_g(size);

    // Step 0: Pre-apply LSC and WB
    #pragma omp parallel for schedule(dynamic, 16)
    for (int y = 0; y < height; ++y) {
        for (int x = 0; x < width; ++x) {
            wb_raw[y * width + x] = get_pixel_wb(p, x, y);
        }
    }

    auto get_wb_fast = [&](int x, int y) {
        int cx = (x < 0) ? 0 : (x >= width ? width - 1 : x);
        int cy = (y < 0) ? 0 : (y >= height ? height - 1 : y);
        return wb_raw[cy * width + cx];
    };

    // Step 1: Interpolate Green channel using ratios
    #pragma omp parallel for schedule(dynamic, 16)
    for (int y = 0; y < height; ++y) {
        for (int x = 0; x < width; ++x) {
            int channel = get_channel_type(x, y, p.bayerPattern);
            if (channel == 1 || channel == 2) {
                full_g[y * width + x] = wb_raw[y * width + x];
            } else {
                float g_h = (get_wb_fast(x - 1, y) + get_wb_fast(x + 1, y)) * 0.5f;
                float g_v = (get_wb_fast(x, y - 1) + get_wb_fast(x, y + 1)) * 0.5f;

                float dh = std::abs(get_wb_fast(x - 2, y) - get_wb_fast(x + 2, y));
                float dv = std::abs(get_wb_fast(x, y - 2) - get_wb_fast(x, y + 2));

                full_g[y * width + x] = (dh < dv) ? g_h : g_v;
            }
        }
    }

    // Step 2: Interpolate R and B using ratios
    #pragma omp parallel for schedule(dynamic, 16)
    for (int y = 0; y < height; ++y) {
        int out_row = y * width;
        for (int x = 0; x < width; ++x) {
            float r, g, b;
            g = full_g[out_row + x];
            int channel = get_channel_type(x, y, p.bayerPattern);

            auto get_ratio_r = [&](int ix, int iy) {
                int cx = (ix < 0) ? 0 : (ix >= width ? width - 1 : ix);
                int cy = (iy < 0) ? 0 : (iy >= height ? height - 1 : iy);
                float val = wb_raw[cy * width + cx];
                float g_val = full_g[cy * width + cx];
                return val / (g_val + 1e-6f);
            };

            if (channel == 0) { // R
                r = wb_raw[out_row + x];
                float ratio_b = (get_ratio_r(x-1, y-1) + get_ratio_r(x+1, y-1) + get_ratio_r(x-1, y+1) + get_ratio_r(x+1, y+1)) * 0.25f;
                b = ratio_b * g;
            } else if (channel == 3) { // B
                b = wb_raw[out_row + x];
                float ratio_r = (get_ratio_r(x-1, y-1) + get_ratio_r(x+1, y-1) + get_ratio_r(x-1, y+1) + get_ratio_r(x+1, y+1)) * 0.25f;
                r = ratio_r * g;
            } else {
                if (channel == 1) { // Gr
                    r = (get_ratio_r(x-1, y) + get_ratio_r(x+1, y)) * 0.5f * g;
                    b = (get_ratio_r(x, y-1) + get_ratio_r(x, y+1)) * 0.5f * g;
                } else { // Gb
                    b = (get_ratio_r(x-1, y) + get_ratio_r(x+1, y)) * 0.5f * g;
                    r = (get_ratio_r(x, y-1) + get_ratio_r(x, y+1)) * 0.5f * g;
                }
            }
            pixels[out_row + x] = rgb_to_pixel(r, g, b);
        }
    }
}
