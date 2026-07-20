#include "demosaic_common.h"
#include <vector>
#include <algorithm>
#include <cmath>

// VNG4 (Variable Number of Gradients - 4 directions)
void demosaic_vng(const DemosaicParams& p, void* pixels) {
    const int width = p.width;
    const int height = p.height;

    #pragma omp parallel for schedule(dynamic, 16)
    for (int y = 0; y < height; ++y) {
        for (int x = 0; x < width; ++x) {
            float r, g, b;
            int channel = get_channel_type(x, y, p.bayerPattern);

            // Calculate gradients in 4 directions (H, V, D1, D2)
            float dh = std::abs(get_pixel_wb(p, x-1, y) - get_pixel_wb(p, x+1, y));
            float dv = std::abs(get_pixel_wb(p, x, y-1) - get_pixel_wb(p, x, y+1));
            float d1 = std::abs(get_pixel_wb(p, x-1, y-1) - get_pixel_wb(p, x+1, y+1));
            float d2 = std::abs(get_pixel_wb(p, x-1, y+1) - get_pixel_wb(p, x+1, y-1));

            float min_grad = std::min({dh, dv, d1, d2});
            float threshold = min_grad * 1.5f;

            float sum_g = 0; int count_g = 0;
            if (dh <= threshold) { sum_g += (get_pixel_wb(p, x-1, y) + get_pixel_wb(p, x+1, y)); count_g += 2; }
            if (dv <= threshold) { sum_g += (get_pixel_wb(p, x, y-1) + get_pixel_wb(p, x, y+1)); count_g += 2; }
            if (d1 <= threshold) { sum_g += (get_pixel_wb(p, x-1, y-1) + get_pixel_wb(p, x+1, y+1)); count_g += 2; }
            if (d2 <= threshold) { sum_g += (get_pixel_wb(p, x-1, y+1) + get_pixel_wb(p, x+1, y-1)); count_g += 2; }

            if (channel == 1 || channel == 2) {
                g = get_pixel_wb(p, x, y);
            } else {
                g = (count_g > 0) ? (sum_g / count_g) : (get_pixel_wb(p, x-1, y) + get_pixel_wb(p, x+1, y) + get_pixel_wb(p, x, y-1) + get_pixel_wb(p, x, y+1)) * 0.25f;
            }

            // Simple bilinear for R/B on VNG
            if (channel == 0) { r = get_pixel_wb(p, x, y); b = (get_pixel_wb(p, x-1, y-1) + get_pixel_wb(p, x+1, y-1) + get_pixel_wb(p, x-1, y+1) + get_pixel_wb(p, x+1, y+1)) * 0.25f; }
            else if (channel == 3) { b = get_pixel_wb(p, x, y); r = (get_pixel_wb(p, x-1, y-1) + get_pixel_wb(p, x+1, y-1) + get_pixel_wb(p, x-1, y+1) + get_pixel_wb(p, x+1, y+1)) * 0.25f; }
            else if (channel == 1) { r = (get_pixel_wb(p, x-1, y) + get_pixel_wb(p, x+1, y)) * 0.5f; b = (get_pixel_wb(p, x, y-1) + get_pixel_wb(p, x, y+1)) * 0.5f; }
            else { b = (get_pixel_wb(p, x-1, y) + get_pixel_wb(p, x+1, y)) * 0.5f; r = (get_pixel_wb(p, x, y-1) + get_pixel_wb(p, x, y+1)) * 0.5f; }

            write_pixel(p, pixels, y * width + x, r, g, b);
        }
    }
}
