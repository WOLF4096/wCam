#include "demosaic_common.h"
#include <vector>
#include <algorithm>
#include <cmath>

// RCD (Ratio-Conditioned Demosaicing)
// Improved version with directional G and ratio-based R/B
void demosaic_rcd(const DemosaicParams& p, void* pixels) {
    const int width = p.width;
    const int height = p.height;
    const int size = width * height;

    std::vector<float> full_g(size);

    // Step 1: Improved Directional Green
    #pragma omp parallel for schedule(dynamic, 16)
    for (int y = 0; y < height; ++y) {
        for (int x = 0; x < width; ++x) {
            int channel = get_channel_type(x, y, p.bayerPattern);
            if (channel == 1 || channel == 2) {
                full_g[y * width + x] = get_pixel_wb(p, x, y);
            } else {
                float v1 = get_pixel_wb(p, x, y-1);
                float v2 = get_pixel_wb(p, x, y+1);
                float h1 = get_pixel_wb(p, x-1, y);
                float h2 = get_pixel_wb(p, x+1, y);

                float dv = std::abs(v1 - v2) + std::abs(2.0f * get_pixel_wb(p, x, y) - get_pixel_wb(p, x, y-2) - get_pixel_wb(p, x, y+2));
                float dh = std::abs(h1 - h2) + std::abs(2.0f * get_pixel_wb(p, x, y) - get_pixel_wb(p, x-2, y) - get_pixel_wb(p, x+2, y));

                if (dv < dh) full_g[y * width + x] = (v1 + v2) * 0.5f;
                else full_g[y * width + x] = (h1 + h2) * 0.5f;
            }
        }
    }

    // Step 2: Ratio-based interpolation for R and B
    #pragma omp parallel for schedule(dynamic, 16)
    for (int y = 0; y < height; ++y) {
        for (int x = 0; x < width; ++x) {
            float r, g, b;
            g = full_g[y * width + x];
            int channel = get_channel_type(x, y, p.bayerPattern);

            auto get_ratio = [&](int ix, int iy) {
                int cx = std::clamp(ix, 0, width - 1);
                int cy = std::clamp(iy, 0, height - 1);
                float raw = get_pixel_wb(p, cx, cy);
                float gr = full_g[cy * width + cx];
                return raw / (gr + 1e-4f);
            };

            if (channel == 0) { // R
                r = get_pixel_wb(p, x, y);
                float rb = (get_ratio(x-1, y-1) + get_ratio(x+1, y-1) + get_ratio(x-1, y+1) + get_ratio(x+1, y+1)) * 0.25f;
                b = g * rb;
            } else if (channel == 3) { // B
                b = get_pixel_wb(p, x, y);
                float rr = (get_ratio(x-1, y-1) + get_ratio(x+1, y-1) + get_ratio(x-1, y+1) + get_ratio(x+1, y+1)) * 0.25f;
                r = g * rr;
            } else if (channel == 1) { // Gr
                r = g * (get_ratio(x-1, y) + get_ratio(x+1, y)) * 0.5f;
                b = g * (get_ratio(x, y-1) + get_ratio(x, y+1)) * 0.5f;
            } else { // Gb
                b = g * (get_ratio(x-1, y) + get_ratio(x+1, y)) * 0.5f;
                r = g * (get_ratio(x, y-1) + get_ratio(x, y+1)) * 0.5f;
            }
            write_pixel(p, pixels, y * width + x, r, g, b);
        }
    }
}
