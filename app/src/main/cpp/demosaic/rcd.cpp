#include "demosaic_common.h"

// RCD (Ratio-Corrected Demosaicing) - Implementation based on the concept of Luis Sanz Rodríguez
// Optimized for smoothness and reduced artifacts.

void demosaic_rcd(const DemosaicParams& p, int* pixels) {
    // Step 1: Interpolate Green channel using ratios
    std::vector<float> full_g(p.width * p.height);

    #pragma omp parallel for schedule(dynamic, 16)
    for (int y = 0; y < p.height; ++y) {
        for (int x = 0; x < p.width; ++x) {
            int channel = get_channel_type(x, y, p.bayerPattern);
            if (channel == 1 || channel == 2) {
                full_g[y * p.width + x] = get_pixel_wb(p, x, y);
            } else {
                // Adaptive interpolation using neighbors and ratios
                float g_h = (get_pixel_wb(p, x-1, y) + get_pixel_wb(p, x+1, y)) * 0.5f;
                float g_v = (get_pixel_wb(p, x, y-1) + get_pixel_wb(p, x, y+1)) * 0.5f;

                float dh = std::abs(get_pixel_wb(p, x-2, y) - get_pixel_wb(p, x+2, y));
                float dv = std::abs(get_pixel_wb(p, x, y-2) - get_pixel_wb(p, x, y+2));

                full_g[y * p.width + x] = (dh < dv) ? g_h : g_v;
            }
        }
    }

    // Step 2: Interpolate R and B using the full Green channel as a guide (Ratio-based)
    #pragma omp parallel for schedule(dynamic, 16)
    for (int y = 0; y < p.height; ++y) {
        int out_row = y * p.width;
        for (int x = 0; x < p.width; ++x) {
            float r, g, b;
            g = full_g[y * p.width + x];
            int channel = get_channel_type(x, y, p.bayerPattern);

            auto get_ratio_r = [&](int ix, int iy) {
                ix = std::clamp(ix, 0, p.width - 1);
                iy = std::clamp(iy, 0, p.height - 1);
                float val = get_pixel_wb(p, ix, iy);
                float g_val = full_g[iy * p.width + ix];
                return val / (g_val + 1e-6f);
            };

            if (channel == 0) { // R
                r = get_pixel_wb(p, x, y);
                float ratio_b = (get_ratio_r(x-1, y-1) + get_ratio_r(x+1, y-1) + get_ratio_r(x-1, y+1) + get_ratio_r(x+1, y+1)) * 0.25f;
                b = ratio_b * g;
            } else if (channel == 3) { // B
                b = get_pixel_wb(p, x, y);
                float ratio_r = (get_ratio_r(x-1, y-1) + get_ratio_r(x+1, y-1) + get_ratio_r(x-1, y+1) + get_ratio_r(x+1, y+1)) * 0.25f;
                r = ratio_r * g;
            } else {
                if (channel == 1) { // Gr
                    r = (get_ratio_r(x-1, y) + get_ratio_r(x+1, y)) * 0.5f * g;
                    b = (get_ratio_r(x, y-1) + get_ratio_r(x, y+1)) * 0.5f * g;
                } else { // Gb
                    b = (get_ratio_r(x-1, y) + get_ratio_r(x+1, y)) * 0.5f * g;
                    r = (get_ratio_r(x, y-1) + get_ratio_r(x, y+1)) * 0.5f * g;
                }
            }
            pixels[out_row + x] = rgb_to_pixel(r, g, b);
        }
    }
}
