#include "demosaic_common.h"
#include <vector>
#include <algorithm>
#include <cmath>

void demosaic_dcb(const DemosaicParams& p, int* pixels) {
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

    #pragma omp parallel for schedule(dynamic, 16)
    for (int y = 0; y < height; ++y) {
        for (int x = 0; x < width; ++x) {
            int channel = get_channel_type(x, y, p.bayerPattern);
            if (channel == 1 || channel == 2) {
                full_g[y * width + x] = wb_raw[y * width + x];
            } else {
                float g_h = (get_wb_fast(x - 1, y) + get_wb_fast(x + 1, y)) * 0.5f;
                float g_v = (get_wb_fast(x, y - 1) + get_wb_fast(x, y + 1)) * 0.5f;
                float diff_h = std::abs(get_wb_fast(x - 1, y) - get_wb_fast(x + 1, y));
                float diff_v = std::abs(get_wb_fast(x, y - 1) - get_wb_fast(x, y + 1));
                full_g[y * width + x] = (diff_h < diff_v) ? g_h : g_v;
            }
        }
    }

    #pragma omp parallel for schedule(dynamic, 16)
    for (int y = 0; y < height; ++y) {
        int out_row = y * width;
        for (int x = 0; x < width; ++x) {
            float r, g, b;
            g = full_g[out_row + x];
            int channel = get_channel_type(x, y, p.bayerPattern);

            auto get_cdiff = [&](int ix, int iy) {
                int cx = (ix < 0) ? 0 : (ix >= width ? width - 1 : ix);
                int cy = (iy < 0) ? 0 : (iy >= height ? height - 1 : iy);
                int ic = get_channel_type(cx, cy, p.bayerPattern);
                if (ic == 0 || ic == 3) return wb_raw[cy * width + cx] - full_g[cy * width + cx];
                return 0.0f;
            };

            if (channel == 0) { // R
                r = wb_raw[out_row + x];
                b = g + (get_cdiff(x - 1, y - 1) + get_cdiff(x + 1, y - 1) + get_cdiff(x - 1, y + 1) + get_cdiff(x + 1, y + 1)) * 0.25f;
            } else if (channel == 3) { // B
                b = wb_raw[out_row + x];
                r = g + (get_cdiff(x - 1, y - 1) + get_cdiff(x + 1, y - 1) + get_cdiff(x - 1, y + 1) + get_cdiff(x + 1, y + 1)) * 0.25f;
            } else {
                if (channel == 1) {
                    r = g + (get_cdiff(x - 1, y) + get_cdiff(x + 1, y)) * 0.5f;
                    b = g + (get_cdiff(x, y - 1) + get_cdiff(x, y + 1)) * 0.5f;
                } else {
                    b = g + (get_cdiff(x - 1, y) + get_cdiff(x + 1, y)) * 0.5f;
                    r = g + (get_cdiff(x, y - 1) + get_cdiff(x, y + 1)) * 0.5f;
                }
            }
            pixels[out_row + x] = rgb_to_pixel(r, g, b);
        }
    }
}
