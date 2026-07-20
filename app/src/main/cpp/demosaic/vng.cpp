#include "demosaic_common.h"
#include <vector>
#include <algorithm>
#include <cmath>

// VNG4 (Variable Number of Gradients - 4 directions)
// Professionally implemented with multi-pass to ensure R/B quality
void demosaic_vng(const DemosaicParams& p, void* pixels) {
    const int width = p.width;
    const int height = p.height;
    const int size = width * height;

    std::vector<float> full_g(size);

    // Pass 1: VNG Green Interpolation
    #pragma omp parallel for schedule(dynamic, 16)
    for (int y = 0; y < height; ++y) {
        for (int x = 0; x < width; ++x) {
            int channel = get_channel_type(x, y, p.bayerPattern);
            if (channel == 1 || channel == 2) {
                full_g[y * width + x] = get_pixel_wb(p, x, y);
            } else {
                // VNG Gradient Estimation
                float dh = std::abs(get_pixel_wb(p, x-1, y) - get_pixel_wb(p, x+1, y));
                float dv = std::abs(get_pixel_wb(p, x, y-1) - get_pixel_wb(p, x, y+1));
                float d1 = std::abs(get_pixel_wb(p, x-1, y-1) - get_pixel_wb(p, x+1, y+1));
                float d2 = std::abs(get_pixel_wb(p, x-1, y+1) - get_pixel_wb(p, x+1, y-1));

                float min_grad = std::min({dh, dv, d1, d2});
                float threshold = min_grad * 1.5f;

                float sum_g = 0; float count_g = 0;
                if (dh <= threshold) { sum_g += (get_pixel_wb(p, x-1, y) + get_pixel_wb(p, x+1, y)); count_g += 2; }
                if (dv <= threshold) { sum_g += (get_pixel_wb(p, x, y-1) + get_pixel_wb(p, x, y+1)); count_g += 2; }
                if (d1 <= threshold) { sum_g += (get_pixel_wb(p, x-1, y-1) + get_pixel_wb(p, x+1, y+1)); count_g += 2; }
                if (d2 <= threshold) { sum_g += (get_pixel_wb(p, x-1, y+1) + get_pixel_wb(p, x+1, y-1)); count_g += 2; }

                full_g[y * width + x] = (count_g > 0) ? (sum_g / count_g) : (get_pixel_wb(p, x-1, y) + get_pixel_wb(p, x+1, y) + get_pixel_wb(p, x, y-1) + get_pixel_wb(p, x, y+1)) * 0.25f;
            }
        }
    }

    // Pass 2: Color Difference Interpolation for R and B
    #pragma omp parallel for schedule(dynamic, 16)
    for (int y = 0; y < height; ++y) {
        for (int x = 0; x < width; ++x) {
            float r, g, b;
            g = full_g[y * width + x];
            int channel = get_channel_type(x, y, p.bayerPattern);

            auto get_diff = [&](int ix, int iy) {
                int cx = std::clamp(ix, 0, width - 1);
                int cy = std::clamp(iy, 0, height - 1);
                return get_pixel_wb(p, cx, cy) - full_g[cy * width + cx];
            };

            if (channel == 0) { // R
                r = get_pixel_wb(p, x, y);
                b = g + (get_diff(x-1, y-1) + get_diff(x+1, y-1) + get_diff(x-1, y+1) + get_diff(x+1, y+1)) * 0.25f;
            } else if (channel == 3) { // B
                b = get_pixel_wb(p, x, y);
                r = g + (get_diff(x-1, y-1) + get_diff(x+1, y-1) + get_diff(x-1, y+1) + get_diff(x+1, y+1)) * 0.25f;
            } else if (channel == 1) { // Gr
                r = g + (get_diff(x-1, y) + get_diff(x+1, y)) * 0.5f;
                b = g + (get_diff(x, y-1) + get_diff(x, y+1)) * 0.5f;
            } else { // Gb
                b = g + (get_diff(x-1, y) + get_diff(x+1, y)) * 0.5f;
                r = g + (get_diff(x, y-1) + get_diff(x, y+1)) * 0.5f;
            }
            write_pixel(p, pixels, y * width + x, r, g, b);
        }
    }
}
