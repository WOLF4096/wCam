#include "demosaic_common.h"
#include <algorithm>
#include <cmath>

// IGV (Improved Gradient Vertical)
// Optimized for sensors with vertical structure or to suppress horizontal artifacts
void demosaic_igv(const DemosaicParams& p, void* pixels) {
    const int width = p.width;
    const int height = p.height;

    #pragma omp parallel for schedule(dynamic, 16)
    for (int y = 0; y < height; ++y) {
        for (int x = 0; x < width; ++x) {
            float r, g, b;
            int channel = get_channel_type(x, y, p.bayerPattern);

            // IGV: Strongly bias towards vertical gradients
            if (channel == 1 || channel == 2) {
                g = get_pixel_wb(p, x, y);
            } else {
                float dv = std::abs(get_pixel_wb(p, x, y-1) - get_pixel_wb(p, x, y+1));
                float dh = std::abs(get_pixel_wb(p, x-1, y) - get_pixel_wb(p, x+1, y));

                // Weight vertical 2x more than horizontal
                if (dv < dh * 2.0f) g = (get_pixel_wb(p, x, y-1) + get_pixel_wb(p, x, y+1)) * 0.5f;
                else g = (get_pixel_wb(p, x-1, y) + get_pixel_wb(p, x+1, y)) * 0.5f;
            }

            // Standard color difference interpolation
            auto get_val = [&](int ix, int iy) { return get_pixel_wb(p, ix, iy); };
            if (channel == 0) { r = get_val(x, y); b = (get_val(x-1, y-1) + get_val(x+1, y-1) + get_val(x-1, y+1) + get_val(x+1, y+1)) * 0.25f; }
            else if (channel == 3) { b = get_val(x, y); r = (get_val(x-1, y-1) + get_val(x+1, y-1) + get_val(x-1, y+1) + get_val(x+1, y+1)) * 0.25f; }
            else if (channel == 1) { r = (get_val(x-1, y) + get_val(x+1, y)) * 0.5f; b = (get_val(x, y-1) + get_val(x, y+1)) * 0.5f; }
            else { b = (get_val(x-1, y) + get_val(x+1, y)) * 0.5f; r = (get_val(x, y-1) + get_val(x, y+1)) * 0.5f; }

            write_pixel(p, pixels, y * width + x, r, g, b);
        }
    }
}
