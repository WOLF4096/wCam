#include "demosaic_common.h"

void demosaic_ahd(const DemosaicParams& p, int* pixels) {
    for (int y = 0; y < p.height; ++y) {
        int out_row = y * p.width;
        for (int x = 0; x < p.width; ++x) {
            float r, g, b;
            int channel = get_channel_type(x, y, p.bayerPattern);

            if (channel == 0 || channel == 3) { // R or B center
                float center = get_pixel_wb(p, x, y);

                // Adaptive Green interpolation based on gradients
                float g_h = (get_pixel_wb(p, x-1, y) + get_pixel_wb(p, x+1, y)) * 0.5f;
                float g_v = (get_pixel_wb(p, x, y-1) + get_pixel_wb(p, x, y+1)) * 0.5f;

                float dh = std::abs(get_pixel_wb(p, x-2, y) - get_pixel_wb(p, x+2, y)) +
                           std::abs(get_pixel_wb(p, x, y) - get_pixel_wb(p, x-1, y)) +
                           std::abs(get_pixel_wb(p, x, y) - get_pixel_wb(p, x+1, y));
                float dv = std::abs(get_pixel_wb(p, x, y-2) - get_pixel_wb(p, x, y+2)) +
                           std::abs(get_pixel_wb(p, x, y) - get_pixel_wb(p, x, y-1)) +
                           std::abs(get_pixel_wb(p, x, y) - get_pixel_wb(p, x, y+1));

                g = (dh < dv) ? g_h : g_v;

                if (channel == 0) {
                    r = center;
                    b = (get_pixel_wb(p, x-1, y-1) + get_pixel_wb(p, x+1, y-1) +
                         get_pixel_wb(p, x-1, y+1) + get_pixel_wb(p, x+1, y+1)) * 0.25f;
                } else {
                    b = center;
                    r = (get_pixel_wb(p, x-1, y-1) + get_pixel_wb(p, x+1, y-1) +
                         get_pixel_wb(p, x-1, y+1) + get_pixel_wb(p, x+1, y+1)) * 0.25f;
                }
            } else { // G center
                g = get_pixel_wb(p, x, y);
                if (channel == 1) { // Gr
                    r = (get_pixel_wb(p, x-1, y) + get_pixel_wb(p, x+1, y)) * 0.5f;
                    b = (get_pixel_wb(p, x, y-1) + get_pixel_wb(p, x, y+1)) * 0.5f;
                } else { // Gb
                    b = (get_pixel_wb(p, x-1, y) + get_pixel_wb(p, x+1, y)) * 0.5f;
                    r = (get_pixel_wb(p, x, y-1) + get_pixel_wb(p, x, y+1)) * 0.5f;
                }
            }
            pixels[out_row + x] = rgb_to_pixel(r, g, b);
        }
    }
}
