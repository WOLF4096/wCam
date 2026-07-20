#include "demosaic_common.h"
#include <vector>
#include <algorithm>
#include <cmath>

// DCB (Double-Color-Bilateral) Demosaicing
void demosaic_dcb(const DemosaicParams& p, void* pixels) {
    const int width = p.width;
    const int height = p.height;
    const int size = width * height;

    std::vector<float> full_g(size);

    // Pass 1: Adaptive Green
    #pragma omp parallel for schedule(dynamic, 16)
    for (int y = 0; y < height; ++y) {
        for (int x = 0; x < width; ++x) {
            int channel = get_channel_type(x, y, p.bayerPattern);
            if (channel == 1 || channel == 2) {
                full_g[y * width + x] = get_pixel_wb(p, x, y);
            } else {
                float h = std::abs(get_pixel_wb(p, x-1, y) - get_pixel_wb(p, x+1, y));
                float v = std::abs(get_pixel_wb(p, x, y-1) - get_pixel_wb(p, x, y+1));
                if (h < v) full_g[y * width + x] = (get_pixel_wb(p, x-1, y) + get_pixel_wb(p, x+1, y)) * 0.5f;
                else full_g[y * width + x] = (get_pixel_wb(p, x, y-1) + get_pixel_wb(p, x, y+1)) * 0.5f;
            }
        }
    }

    // Pass 2: Bilateral refinement for R and B
    #pragma omp parallel for schedule(dynamic, 16)
    for (int y = 0; y < height; ++y) {
        for (int x = 0; x < width; ++x) {
            float r, g, b;
            g = full_g[y * width + x];
            int channel = get_channel_type(x, y, p.bayerPattern);

            auto get_bilateral_diff = [&](int ix, int iy, int target_c) {
                float sum_w = 0, sum_d = 0;
                for (int dy = -1; dy <= 1; ++dy) {
                    for (int dx = -1; dx <= 1; ++dx) {
                        int nx = std::clamp(ix + dx, 0, width - 1);
                        int ny = std::clamp(iy + dy, 0, height - 1);
                        if (get_channel_type(nx, ny, p.bayerPattern) == target_c) {
                            float w = 1.0f / (1.0f + std::abs(full_g[ny*width + nx] - g));
                            sum_w += w;
                            sum_d += w * (get_pixel_wb(p, nx, ny) - full_g[ny*width + nx]);
                        }
                    }
                }
                return (sum_w > 0) ? (sum_d / sum_w) : 0.0f;
            };

            if (channel == 0) { r = get_pixel_wb(p, x, y); b = g + get_bilateral_diff(x, y, 3); }
            else if (channel == 3) { b = get_pixel_wb(p, x, y); r = g + get_bilateral_diff(x, y, 0); }
            else {
                r = g + get_bilateral_diff(x, y, 0);
                b = g + get_bilateral_diff(x, y, 3);
            }
            write_pixel(p, pixels, y * width + x, r, g, b);
        }
    }
}
