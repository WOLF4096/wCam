#include "demosaic_common.h"
#include <vector>
#include <algorithm>
#include <cmath>

// LMMSE (Linear Minimum Mean Square Error) Demosaicing
void demosaic_lmmse(const DemosaicParams& p, void* pixels) {
    const int width = p.width;
    const int height = p.height;
    const int size = width * height;

    std::vector<float> full_g(size);

    // Pass 1: Initial Directional G (similar to AHD for robustness)
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

    // Pass 2: LMMSE Refinement (Simplified: use local variance to weight)
    #pragma omp parallel for schedule(dynamic, 16)
    for (int y = 0; y < height; ++y) {
        for (int x = 0; x < width; ++x) {
            float r, g, b;
            g = full_g[y * width + x];
            int channel = get_channel_type(x, y, p.bayerPattern);

            // Estimate local variance and apply MMSE-like weight
            auto get_mmse_val = [&](int ix, int iy, int target_c) {
                float sum = 0, sum_sq = 0;
                int count = 0;
                for (int dy = -2; dy <= 2; ++dy) {
                    for (int dx = -2; dx <= 2; ++dx) {
                        int nx = std::clamp(ix + dx, 0, width - 1);
                        int ny = std::clamp(iy + dy, 0, height - 1);
                        if (get_channel_type(nx, ny, p.bayerPattern) == target_c) {
                            float val = get_pixel_wb(p, nx, ny) - full_g[ny*width + nx];
                            sum += val; sum_sq += val * val; count++;
                        }
                    }
                }
                float mean = (count > 0) ? (sum / (float)count) : 0;
                float var = (count > 0) ? (sum_sq / (float)count - mean * mean) : 0;

                // MMSE weighting: use local variance to preserve edges while smoothing noise
                // k = var_signal / (var_signal + var_noise)
                float noise_var = 0.001f; // Estimated noise floor
                float k = var / (var + noise_var + 1e-6f);
                float diff = get_pixel_wb(p, ix, iy) - full_g[iy * width + ix];

                return g + (mean + k * (diff - mean));
            };

            if (channel == 0) { r = get_pixel_wb(p, x, y); b = get_mmse_val(x, y, 3); }
            else if (channel == 3) { b = get_pixel_wb(p, x, y); r = get_mmse_val(x, y, 0); }
            else {
                r = get_mmse_val(x, y, 0);
                b = get_mmse_val(x, y, 3);
            }
            write_pixel(p, pixels, y * width + x, r, g, b);
        }
    }
}
