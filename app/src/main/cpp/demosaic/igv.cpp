#include "demosaic_common.h"
#include <vector>
#include <algorithm>
#include <cmath>

// IGV (Improved Gradient Vertical)
// Optimized for sensors with vertical structure or to suppress horizontal artifacts
void demosaic_igv(const DemosaicParams& p, void* pixels) {
    const int width = p.width;
    const int height = p.height;
    const int size = width * height;

    std::vector<float> full_g(size);

    // Pass 1: IGV Green Interpolation
    #pragma omp parallel for schedule(dynamic, 16)
    for (int y = 0; y < height; ++y) {
        for (int x = 0; x < width; ++x) {
            int channel = get_channel_type(x, y, p.bayerPattern);
            if (channel == 1 || channel == 2) {
                full_g[y * width + x] = get_pixel_wb(p, x, y);
            } else {
                float dv = std::abs(get_pixel_wb(p, x, y-1) - get_pixel_wb(p, x, y+1));
                float dh = std::abs(get_pixel_wb(p, x-1, y) - get_pixel_wb(p, x+1, y));

                // Weight vertical 2x more than horizontal
                if (dv < dh * 2.0f) full_g[y * width + x] = (get_pixel_wb(p, x, y-1) + get_pixel_wb(p, x, y+1)) * 0.5f;
                else full_g[y * width + x] = (get_pixel_wb(p, x-1, y) + get_pixel_wb(p, x+1, y)) * 0.5f;
            }
        }
    }

    // Pass 2: Color Difference Interpolation
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

            if (channel == 0) { r = get_pixel_wb(p, x, y); b = g + (get_diff(x-1, y-1) + get_diff(x+1, y-1) + get_diff(x-1, y+1) + get_diff(x+1, y+1)) * 0.25f; }
            else if (channel == 3) { b = get_pixel_wb(p, x, y); r = g + (get_diff(x-1, y-1) + get_diff(x+1, y-1) + get_diff(x-1, y+1) + get_diff(x+1, y+1)) * 0.25f; }
            else if (channel == 1) { r = g + (get_diff(x-1, y) + get_diff(x+1, y)) * 0.5f; b = g + (get_diff(x, y-1) + get_diff(x, y+1)) * 0.5f; }
            else { b = g + (get_diff(x-1, y) + get_diff(x+1, y)) * 0.5f; r = g + (get_diff(x, y-1) + get_diff(x, y+1)) * 0.5f; }

            write_pixel(p, pixels, y * width + x, r, g, b);
        }
    }
}
