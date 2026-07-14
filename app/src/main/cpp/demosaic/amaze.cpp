#include "demosaic_common.h"

// AMaZE (Aliasing Minimization and Zipper Elimination)
// This is a highly simplified version of AMaZE concept, focusing on directionality and artifact reduction.

void demosaic_amaze(const DemosaicParams& p, int* pixels) {
    std::vector<float> full_g(p.width * p.height);

    // Step 1: Directional Green interpolation with more context
    #pragma omp parallel for schedule(dynamic, 16)
    for (int y = 0; y < p.height; ++y) {
        for (int x = 0; x < p.width; ++x) {
            int channel = get_channel_type(x, y, p.bayerPattern);
            if (channel == 1 || channel == 2) {
                full_g[y * p.width + x] = get_pixel_wb(p, x, y);
            } else {
                // Look at a 5x5 area for directional gradients
                float dh = std::abs(get_pixel_wb(p, x-2, y) + get_pixel_wb(p, x+2, y) - 2 * get_pixel_wb(p, x, y));
                float dv = std::abs(get_pixel_wb(p, x, y-2) + get_pixel_wb(p, x, y+2) - 2 * get_pixel_wb(p, x, y));

                // Also consider the "homogeneity" or "aliasing"
                // ... (complex logic omitted for performance but simplified gradient used)

                if (dh < dv) {
                    full_g[y * p.width + x] = (get_pixel_wb(p, x-1, y) + get_pixel_wb(p, x+1, y)) * 0.5f;
                } else {
                    full_g[y * p.width + x] = (get_pixel_wb(p, x, y-1) + get_pixel_wb(p, x, y+1)) * 0.5f;
                }
            }
        }
    }

    // Step 2: Refine Green based on color differences (Zipper elimination concept)
    // In full AMaZE, this involves multiple passes and median filters on color differences.

    // Step 3: Interpolate R and B using Green as guide
    for (int y = 0; y < p.height; ++y) {
        int out_row = y * p.width;
        for (int x = 0; x < p.width; ++x) {
            float r, g, b;
            g = full_g[y * p.width + x];
            int channel = get_channel_type(x, y, p.bayerPattern);

            auto get_diff = [&](int ix, int iy) {
                ix = std::clamp(ix, 0, p.width - 1);
                iy = std::clamp(iy, 0, p.height - 1);
                int ic = get_channel_type(ix, iy, p.bayerPattern);
                float center_val = (ic == 0 || ic == 3) ? get_pixel_wb(p, ix, iy) : 0.0f;
                return center_val - full_g[iy * p.width + ix];
            };

            if (channel == 0) { // R
                r = get_pixel_wb(p, x, y);
                float diff_b = (get_diff(x-1, y-1) + get_diff(x+1, y-1) + get_diff(x-1, y+1) + get_diff(x+1, y+1)) * 0.25f;
                b = g + diff_b;
            } else if (channel == 3) { // B
                b = get_pixel_wb(p, x, y);
                float diff_r = (get_diff(x-1, y-1) + get_diff(x+1, y-1) + get_diff(x-1, y+1) + get_diff(x+1, y+1)) * 0.25f;
                r = g + diff_r;
            } else {
                if (channel == 1) { // Gr
                    r = g + (get_diff(x-1, y) + get_diff(x+1, y)) * 0.5f;
                    b = g + (get_diff(x, y-1) + get_diff(x, y+1)) * 0.5f;
                } else { // Gb
                    b = g + (get_diff(x-1, y) + get_diff(x+1, y)) * 0.5f;
                    r = g + (get_diff(x, y-1) + get_diff(x, y+1)) * 0.5f;
                }
            }
            pixels[out_row + x] = rgb_to_pixel(r, g, b);
        }
    }
}
