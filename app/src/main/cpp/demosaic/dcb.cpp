#include "demosaic_common.h"

// DCB (Directional Color Binning)
// Effective at avoiding false colors.

void demosaic_dcb(const DemosaicParams& p, int* pixels) {
    // Simplified DCB concept: Calculate directional color differences and average them.

    std::vector<float> full_g(p.width * p.height);
    for (int y = 0; y < p.height; ++y) {
        for (int x = 0; x < p.width; ++x) {
            int channel = get_channel_type(x, y, p.bayerPattern);
            if (channel == 1 || channel == 2) {
                full_g[y * p.width + x] = get_pixel_wb(p, x, y);
            } else {
                // Directional averaging
                float g_h = (get_pixel_wb(p, x-1, y) + get_pixel_wb(p, x+1, y)) * 0.5f;
                float g_v = (get_pixel_wb(p, x, y-1) + get_pixel_wb(p, x, y+1)) * 0.5f;
                float diff_h = std::abs(get_pixel_wb(p, x-1, y) - get_pixel_wb(p, x+1, y));
                float diff_v = std::abs(get_pixel_wb(p, x, y-1) - get_pixel_wb(p, x, y+1));
                full_g[y * p.width + x] = (diff_h < diff_v) ? g_h : g_v;
            }
        }
    }

    for (int y = 0; y < p.height; ++y) {
        int out_row = y * p.width;
        for (int x = 0; x < p.width; ++x) {
            float r, g, b;
            g = full_g[y * p.width + x];
            int channel = get_channel_type(x, y, p.bayerPattern);

            // Use color differences (R-G, B-G) for interpolation
            auto get_cdiff = [&](int ix, int iy) {
                ix = std::clamp(ix, 0, p.width - 1);
                iy = std::clamp(iy, 0, p.height - 1);
                int ic = get_channel_type(ix, iy, p.bayerPattern);
                if (ic == 0 || ic == 3) return get_pixel_wb(p, ix, iy) - full_g[iy * p.width + ix];
                return 0.0f;
            };

            if (channel == 0) { // R
                r = get_pixel_wb(p, x, y);
                b = g + (get_cdiff(x-1, y-1) + get_cdiff(x+1, y-1) + get_cdiff(x-1, y+1) + get_cdiff(x+1, y+1)) * 0.25f;
            } else if (channel == 3) { // B
                b = get_pixel_wb(p, x, y);
                r = g + (get_cdiff(x-1, y-1) + get_cdiff(x+1, y-1) + get_cdiff(x-1, y+1) + get_cdiff(x+1, y+1)) * 0.25f;
            } else {
                if (channel == 1) {
                    r = g + (get_cdiff(x-1, y) + get_cdiff(x+1, y)) * 0.5f;
                    b = g + (get_cdiff(x, y-1) + get_cdiff(x, y+1)) * 0.5f;
                } else {
                    b = g + (get_cdiff(x-1, y) + get_cdiff(x+1, y)) * 0.5f;
                    r = g + (get_cdiff(x, y-1) + get_cdiff(x, y+1)) * 0.5f;
                }
            }
            pixels[out_row + x] = rgb_to_pixel(r, g, b);
        }
    }
}
