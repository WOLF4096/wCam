#include "demosaic_common.h"
#include <vector>

/**
 * Improved Demosaicing Algorithm
 * Using Directional Green Interpolation and Color Difference Smoothing.
 * This provides much better edge quality and fewer artifacts than bilinear.
 */
void demosaic_lmmse(const DemosaicParams& p, int* pixels) {
    int width = p.width;
    int height = p.height;
    std::vector<float> full_g(width * height);

    // 1. Directional Green Interpolation
    for (int y = 0; y < height; ++y) {
        for (int x = 0; x < width; ++x) {
            int channel = get_channel_type(x, y, p.bayerPattern);
            if (channel == 1 || channel == 2) { // Green
                full_g[y * width + x] = get_pixel_wb(p, x, y);
            } else { // R or B center
                // Calculate horizontal and vertical gradients
                float dh = std::abs(get_pixel_wb(p, x - 2, y) - get_pixel_wb(p, x + 2, y)) +
                           std::abs(get_pixel_wb(p, x, y) - get_pixel_wb(p, x - 1, y)) + // This is G-G if we had G, but we use raw
                           std::abs(get_pixel_wb(p, x, y) - get_pixel_wb(p, x + 1, y));

                float dv = std::abs(get_pixel_wb(p, x, y - 2) - get_pixel_wb(p, x, y + 2)) +
                           std::abs(get_pixel_wb(p, x, y) - get_pixel_wb(p, x, y - 1)) +
                           std::abs(get_pixel_wb(p, x, y) - get_pixel_wb(p, x, y + 1));

                if (dh < dv) {
                    full_g[y * width + x] = (get_pixel_wb(p, x - 1, y) + get_pixel_wb(p, x + 1, y)) * 0.5f;
                } else if (dv < dh) {
                    full_g[y * width + x] = (get_pixel_wb(p, x, y - 1) + get_pixel_wb(p, x, y + 1)) * 0.5f;
                } else {
                    full_g[y * width + x] = (get_pixel_wb(p, x - 1, y) + get_pixel_wb(p, x + 1, y) +
                                            get_pixel_wb(p, x, y - 1) + get_pixel_wb(p, x, y + 1)) * 0.25f;
                }
            }
        }
    }

    // 2. Interpolate R and B using Color Differences (R-G, B-G)
    // This reduces color artifacts by assuming color differences vary slowly.
    for (int y = 0; y < height; ++y) {
        int out_row = y * width;
        for (int x = 0; x < width; ++x) {
            float r, g, b;
            g = full_g[y * width + x];
            int channel = get_channel_type(x, y, p.bayerPattern);

            if (channel == 0) { // R center
                r = get_pixel_wb(p, x, y);
                // B is at corners (x-1, y-1), etc. Interpolate B-G.
                float bg_sum = (get_pixel_wb(p, x-1, y-1) - full_g[(y-1)*width + (x-1)]) +
                               (get_pixel_wb(p, x+1, y-1) - full_g[(y-1)*width + (x+1)]) +
                               (get_pixel_wb(p, x-1, y+1) - full_g[(y+1)*width + (x-1)]) +
                               (get_pixel_wb(p, x+1, y+1) - full_g[(y+1)*width + (x+1)]);
                b = g + bg_sum * 0.25f;
            } else if (channel == 3) { // B center
                b = get_pixel_wb(p, x, y);
                // R is at corners. Interpolate R-G.
                float rg_sum = (get_pixel_wb(p, x-1, y-1) - full_g[(y-1)*width + (x-1)]) +
                               (get_pixel_wb(p, x+1, y-1) - full_g[(y-1)*width + (x+1)]) +
                               (get_pixel_wb(p, x-1, y+1) - full_g[(y+1)*width + (x-1)]) +
                               (get_pixel_wb(p, x+1, y+1) - full_g[(y+1)*width + (x+1)]);
                r = g + rg_sum * 0.25f;
            } else if (channel == 1) { // Gr (R on left/right, B on top/bottom)
                float rg_sum = (get_pixel_wb(p, x-1, y) - full_g[y*width + (x-1)]) +
                               (get_pixel_wb(p, x+1, y) - full_g[y*width + (x+1)]);
                r = g + rg_sum * 0.5f;

                float bg_sum = (get_pixel_wb(p, x, y-1) - full_g[(y-1)*width + x]) +
                               (get_pixel_wb(p, x, y+1) - full_g[(y+1)*width + x]);
                b = g + bg_sum * 0.5f;
            } else { // Gb (B on left/right, R on top/bottom)
                float bg_sum = (get_pixel_wb(p, x-1, y) - full_g[y*width + (x-1)]) +
                               (get_pixel_wb(p, x+1, y) - full_g[y*width + (x+1)]);
                b = g + bg_sum * 0.5f;

                float rg_sum = (get_pixel_wb(p, x, y-1) - full_g[(y-1)*width + x]) +
                               (get_pixel_wb(p, x, y+1) - full_g[(y+1)*width + x]);
                r = g + rg_sum * 0.5f;
            }

            pixels[out_row + x] = rgb_to_pixel(r, g, b);
        }
    }
}
