#include "demosaic_common.h"
#include <vector>
#include <algorithm>
#include <cmath>

// Standard AHD implementation
void demosaic_ahd(const DemosaicParams& p, void* pixels) {
    const int width = p.width;
    const int height = p.height;
    const int size = width * height;

    std::vector<float> full_g(size);

    // Step 1: Directional Green Interpolation
    #pragma omp parallel for schedule(dynamic, 16)
    for (int y = 0; y < height; ++y) {
        for (int x = 0; x < width; ++x) {
            int channel = get_channel_type(x, y, p.bayerPattern);
            if (channel == 1 || channel == 2) {
                full_g[y * width + x] = get_pixel_wb(p, x, y);
            } else {
                // Horizontal and Vertical candidates
                float gh = (get_pixel_wb(p, x - 1, y) + get_pixel_wb(p, x + 1, y)) * 0.5f +
                           (2.0f * get_pixel_wb(p, x, y) - get_pixel_wb(p, x - 2, y) - get_pixel_wb(p, x + 2, y)) * 0.25f;
                float gv = (get_pixel_wb(p, x, y - 1) + get_pixel_wb(p, x, y + 1)) * 0.5f +
                           (2.0f * get_pixel_wb(p, x, y) - get_pixel_wb(p, x, y - 2) - get_pixel_wb(p, x, y + 2)) * 0.25f;

                // Simple directional selection based on local gradients
                float dh = std::abs(get_pixel_wb(p, x - 2, y) - get_pixel_wb(p, x, y)) +
                           std::abs(get_pixel_wb(p, x + 2, y) - get_pixel_wb(p, x, y));
                float dv = std::abs(get_pixel_wb(p, x, y - 2) - get_pixel_wb(p, x, y)) +
                           std::abs(get_pixel_wb(p, x, y + 2) - get_pixel_wb(p, x, y));

                full_g[y * width + x] = (dh < dv) ? gh : gv;
            }
        }
    }

    // Step 2: Interpolate R and B using G
    #pragma omp parallel for schedule(dynamic, 16)
    for (int y = 0; y < height; ++y) {
        for (int x = 0; x < width; ++x) {
            float r, g, b;
            g = full_g[y * width + x];
            int channel = get_channel_type(x, y, p.bayerPattern);

            auto get_diff = [&](int ix, int iy, int c) {
                return get_pixel_wb(p, ix, iy) - full_g[iy * width + ix];
            };

            if (channel == 0) { // R
                r = get_pixel_wb(p, x, y);
                float db = (get_diff(x - 1, y - 1, 3) + get_diff(x + 1, y - 1, 3) +
                            get_diff(x - 1, y + 1, 3) + get_diff(x + 1, y + 1, 3)) * 0.25f;
                b = g + db;
            } else if (channel == 3) { // B
                b = get_pixel_wb(p, x, y);
                float dr = (get_diff(x - 1, y - 1, 0) + get_diff(x + 1, y - 1, 0) +
                            get_diff(x - 1, y + 1, 0) + get_diff(x + 1, y + 1, 0)) * 0.25f;
                r = g + dr;
            } else if (channel == 1) { // Gr
                r = g + (get_diff(x - 1, y, 0) + get_diff(x + 1, y, 0)) * 0.5f;
                b = g + (get_diff(x, y - 1, 3) + get_diff(x, y + 1, 3)) * 0.5f;
            } else { // Gb
                b = g + (get_diff(x - 1, y, 3) + get_diff(x + 1, y, 3)) * 0.5f;
                r = g + (get_diff(x, y - 1, 0) + get_diff(x, y + 1, 0)) * 0.5f;
            }
            write_pixel(p, pixels, y * width + x, r, g, b);
        }
    }
}
