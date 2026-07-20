#include "demosaic_common.h"
#include <vector>
#include <algorithm>
#include <cmath>

// EAHD (Enhanced AHD) implementation with larger homogeneity window
void demosaic_eahd(const DemosaicParams& p, void* pixels) {
    const int width = p.width;
    const int height = p.height;
    const int size = width * height;

    std::vector<float> gh_full(size);
    std::vector<float> gv_full(size);
    std::vector<float> lab_h(size * 3);
    std::vector<float> lab_v(size * 3);

    // Step 1: Directional Interpolation for G
    #pragma omp parallel for schedule(dynamic, 16)
    for (int y = 0; y < height; ++y) {
        for (int x = 0; x < width; ++x) {
            int channel = get_channel_type(x, y, p.bayerPattern);
            if (channel == 1 || channel == 2) {
                gh_full[y * width + x] = gv_full[y * width + x] = get_pixel_wb(p, x, y);
            } else {
                gh_full[y * width + x] = (get_pixel_wb(p, x - 1, y) + get_pixel_wb(p, x + 1, y)) * 0.5f +
                                         (2.0f * get_pixel_wb(p, x, y) - get_pixel_wb(p, x - 2, y) - get_pixel_wb(p, x + 2, y)) * 0.25f;
                gv_full[y * width + x] = (get_pixel_wb(p, x, y - 1) + get_pixel_wb(p, x, y + 1)) * 0.5f +
                                         (2.0f * get_pixel_wb(p, x, y) - get_pixel_wb(p, x, y - 2) - get_pixel_wb(p, x, y + 2)) * 0.25f;
            }
        }
    }

    // Step 2: Directional Homogeneity Analysis (Simplified)
    std::vector<float> full_g(size);
    #pragma omp parallel for schedule(dynamic, 16)
    for (int y = 0; y < height; ++y) {
        for (int x = 0; x < width; ++x) {
            float sum_h = 0, sum_v = 0;
            // Analyze a 3x3 window for homogeneity
            for (int dy = -1; dy <= 1; ++dy) {
                for (int dx = -1; dx <= 1; ++dx) {
                    int nx = std::clamp(x + dx, 2, width - 3);
                    int ny = std::clamp(y + dy, 2, height - 3);
                    sum_h += std::abs(gh_full[ny * width + nx] - gh_full[ny * width + nx - 1]) +
                             std::abs(gh_full[ny * width + nx] - gh_full[ny * width + nx + 1]);
                    sum_v += std::abs(gv_full[ny * width + nx] - gv_full[(ny - 1) * width + nx]) +
                             std::abs(gv_full[ny * width + nx] - gv_full[(ny + 1) * width + nx]);
                }
            }
            full_g[y * width + x] = (sum_h < sum_v) ? gh_full[y * width + x] : gv_full[y * width + x];
        }
    }

    // Step 3: Color difference interpolation (same as AHD)
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
