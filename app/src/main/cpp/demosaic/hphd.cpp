#include "demosaic_common.h"
#include <algorithm>
#include <cmath>

// HPHD (High Performance Homogeneity-Directed)
// Uses a fast, single-pass directional selection to maximize speed while maintaining HD benefits
void demosaic_hphd(const DemosaicParams& p, void* pixels) {
    const int width = p.width;
    const int height = p.height;

    #pragma omp parallel for schedule(dynamic, 16)
    for (int y = 0; y < height; ++y) {
        for (int x = 0; x < width; ++x) {
            float r, g, b;
            int channel = get_channel_type(x, y, p.bayerPattern);

            // Fast Green Interpolation
            if (channel == 1 || channel == 2) {
                g = get_pixel_wb(p, x, y);
            } else {
                float dh = std::abs(get_pixel_wb(p, x-1, y) - get_pixel_wb(p, x+1, y));
                float dv = std::abs(get_pixel_wb(p, x, y-1) - get_pixel_wb(p, x, y+1));
                if (dh < dv) g = (get_pixel_wb(p, x-1, y) + get_pixel_wb(p, x+1, y)) * 0.5f;
                else g = (get_pixel_wb(p, x, y-1) + get_pixel_wb(p, x, y+1)) * 0.5f;
            }

            // Fast R/B Interpolation (Bilinear on differences)
            auto get_val = [&](int ix, int iy) { return get_pixel_wb(p, ix, iy); };

            if (channel == 0) { // R
                r = get_val(x, y);
                g = g; // already set
                b = (get_val(x-1, y-1) + get_val(x+1, y-1) + get_val(x-1, y+1) + get_val(x+1, y+1)) * 0.25f;
            } else if (channel == 3) { // B
                b = get_val(x, y);
                r = (get_val(x-1, y-1) + get_val(x+1, y-1) + get_val(x-1, y+1) + get_val(x+1, y+1)) * 0.25f;
            } else if (channel == 1) { // Gr
                r = (get_val(x-1, y) + get_val(x+1, y)) * 0.5f;
                b = (get_val(x, y-1) + get_val(x, y+1)) * 0.5f;
            } else { // Gb
                b = (get_val(x-1, y) + get_val(x+1, y)) * 0.5f;
                r = (get_val(x, y-1) + get_val(x, y+1)) * 0.5f;
            }

            write_pixel(p, pixels, y * width + x, r, g, b);
        }
    }
}
