#include "demosaic_common.h"
#include <vector>
#include <algorithm>
#include <cmath>

// AMaZE (Aliasing Minimization and Zipper Elimination)
// Highly simplified version focusing on the core directional selection logic
void demosaic_amaze(const DemosaicParams& p, void* pixels) {
    const int width = p.width;
    const int height = p.height;
    const int size = width * height;

    std::vector<float> full_g(size);

    // Step 1: Multi-directional Gradient Estimation
    #pragma omp parallel for schedule(dynamic, 16)
    for (int y = 0; y < height; ++y) {
        for (int x = 0; x < width; ++x) {
            int channel = get_channel_type(x, y, p.bayerPattern);
            if (channel == 1 || channel == 2) {
                full_g[y * width + x] = get_pixel_wb(p, x, y);
            } else {
                // AMaZE core: Calculate gradients in 4 directions
                float h = std::abs(get_pixel_wb(p, x-2, y) + get_pixel_wb(p, x+2, y) - 2.0f * get_pixel_wb(p, x, y));
                float v = std::abs(get_pixel_wb(p, x, y-2) + get_pixel_wb(p, x, y+2) - 2.0f * get_pixel_wb(p, x, y));

                // Directional Green candidates
                float gh = (get_pixel_wb(p, x-1, y) + get_pixel_wb(p, x+1, y)) * 0.5f;
                float gv = (get_pixel_wb(p, x, y-1) + get_pixel_wb(p, x, y+1)) * 0.5f;

                if (h < v) full_g[y * width + x] = gh;
                else full_g[y * width + x] = gv;
            }
        }
    }

    // Step 2: Refine Green to eliminate "zipper" artifacts
    // In AMaZE, this involves a second pass to smooth the Green channel along edges
    std::vector<float> refined_g = full_g;
    #pragma omp parallel for schedule(dynamic, 16)
    for (int y = 2; y < height - 2; ++y) {
        for (int x = 2; x < width - 2; ++x) {
            int channel = get_channel_type(x, y, p.bayerPattern);
            if (channel == 0 || channel == 3) {
                float avg = (full_g[(y-1)*width + x] + full_g[(y+1)*width + x] + full_g[y*width + x-1] + full_g[y*width + x+1]) * 0.25f;
                refined_g[y*width + x] = 0.5f * full_g[y*width + x] + 0.5f * avg;
            }
        }
    }

    // Step 3: Interpolate R and B using the refined Green
    #pragma omp parallel for schedule(dynamic, 16)
    for (int y = 0; y < height; ++y) {
        for (int x = 0; x < width; ++x) {
            float r, g, b;
            g = refined_g[y * width + x];
            int channel = get_channel_type(x, y, p.bayerPattern);

            auto get_diff = [&](int ix, int iy) {
                int cx = std::clamp(ix, 0, width - 1);
                int cy = std::clamp(iy, 0, height - 1);
                return get_pixel_wb(p, cx, cy) - refined_g[cy * width + cx];
            };

            if (channel == 0) { r = get_pixel_wb(p, x, y); b = g + (get_diff(x-1, y-1) + get_diff(x+1, y-1) + get_diff(x-1, y+1) + get_diff(x+1, y+1)) * 0.25f; }
            else if (channel == 3) { b = get_pixel_wb(p, x, y); r = g + (get_diff(x-1, y-1) + get_diff(x+1, y-1) + get_diff(x-1, y+1) + get_diff(x+1, y+1)) * 0.25f; }
            else if (channel == 1) { r = g + (get_diff(x-1, y) + get_diff(x+1, y)) * 0.5f; b = g + (get_diff(x, y-1) + get_diff(x, y+1)) * 0.5f; }
            else { b = g + (get_diff(x-1, y) + get_diff(x+1, y)) * 0.5f; r = g + (get_diff(x, y-1) + get_diff(x, y+1)) * 0.5f; }

            write_pixel(p, pixels, y * width + x, r, g, b);
        }
    }
}
