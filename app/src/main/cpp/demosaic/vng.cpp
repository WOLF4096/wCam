#include "demosaic_common.h"
#include <vector>
#include <algorithm>
#include <cmath>

void demosaic_vng(const DemosaicParams& p, int* pixels) {
    const int width = p.width;
    const int height = p.height;
    const int size = width * height;

    std::vector<float> wb_raw(size);

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
        int out_row = y * width;
        for (int x = 0; x < width; ++x) {
            int channel = get_channel_type(x, y, p.bayerPattern);
            float r, g, b;

            if (channel == 0 || channel == 3) {
                float center = wb_raw[out_row + x];
                float g_h = (get_wb_fast(x - 1, y) + get_wb_fast(x + 1, y)) * 0.5f;
                float g_v = (get_wb_fast(x, y - 1) + get_wb_fast(x, y + 1)) * 0.5f;
                float dh = std::abs(get_wb_fast(x - 2, y) - get_wb_fast(x + 2, y));
                float dv = std::abs(get_wb_fast(x, y - 2) - get_wb_fast(x, y + 2));
                g = (dh < dv) ? g_h : g_v;

                if (channel == 0) {
                    r = center;
                    b = (get_wb_fast(x - 1, y - 1) + get_wb_fast(x + 1, y - 1) +
                         get_wb_fast(x - 1, y + 1) + get_wb_fast(x + 1, y + 1)) * 0.25f;
                } else {
                    b = center;
                    r = (get_wb_fast(x - 1, y - 1) + get_wb_fast(x + 1, y - 1) +
                         get_wb_fast(x - 1, y + 1) + get_wb_fast(x + 1, y + 1)) * 0.25f;
                }
            } else {
                g = wb_raw[out_row + x];
                if (channel == 1) { // Gr
                    r = (get_wb_fast(x - 1, y) + get_wb_fast(x + 1, y)) * 0.5f;
                    b = (get_wb_fast(x, y - 1) + get_wb_fast(x, y + 1)) * 0.5f;
                } else { // Gb
                    b = (get_wb_fast(x - 1, y) + get_wb_fast(x + 1, y)) * 0.5f;
                    r = (get_wb_fast(x, y - 1) + get_wb_fast(x, y + 1)) * 0.5f;
                }
            }

            pixels[out_row + x] = rgb_to_pixel(r, g, b);
        }
    }
}
