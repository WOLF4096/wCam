#include "demosaic_common.h"
#include <vector>
#include <algorithm>

void demosaic_bilinear(const DemosaicParams& p, void* pixels) {
    const int width = p.width;
    const int height = p.height;
    const int size = width * height;

    std::vector<float> wb_raw(size);

    #pragma omp parallel for schedule(dynamic, 16)
    for (int y = 0; y < height; ++y) {
        for (int x = 0; x < width; ++x) {
            wb_raw[y * width + x] = get_pixel_wb(p, x, y);
        }
    }

    auto get_wb_fast = [&](int x, int y) {
        int cx = (x < 0) ? 0 : (x >= width ? width - 1 : x);
        int cy = (y < 0) ? 0 : (y >= height ? height - 1 : y);
        return wb_raw[cy * width + cx];
    };

    #pragma omp parallel for schedule(dynamic, 16)
    for (int y = 0; y < height; ++y) {
        int out_row = y * width;
        for (int x = 0; x < width; ++x) {
            float r, g, b;
            int channel = get_channel_type(x, y, p.bayerPattern);

            if (channel == 0) { // R
                r = wb_raw[out_row + x];
                g = (get_wb_fast(x-1, y) + get_wb_fast(x+1, y) + get_wb_fast(x, y-1) + get_wb_fast(x, y+1)) * 0.25f;
                b = (get_wb_fast(x-1, y-1) + get_wb_fast(x+1, y-1) + get_wb_fast(x-1, y+1) + get_wb_fast(x+1, y+1)) * 0.25f;
            } else if (channel == 3) { // B
                b = wb_raw[out_row + x];
                g = (get_wb_fast(x-1, y) + get_wb_fast(x+1, y) + get_wb_fast(x, y-1) + get_wb_fast(x, y+1)) * 0.25f;
                r = (get_wb_fast(x-1, y-1) + get_wb_fast(x+1, y-1) + get_wb_fast(x-1, y+1) + get_wb_fast(x+1, y+1)) * 0.25f;
            } else { // G
                g = wb_raw[out_row + x];
                if (channel == 1) { // Gr
                    r = (get_wb_fast(x-1, y) + get_wb_fast(x+1, y)) * 0.5f;
                    b = (get_wb_fast(x, y-1) + get_wb_fast(x, y+1)) * 0.5f;
                } else { // Gb
                    b = (get_wb_fast(x-1, y) + get_wb_fast(x+1, y)) * 0.5f;
                    r = (get_wb_fast(x, y-1) + get_wb_fast(x, y+1)) * 0.5f;
                }
            }
            write_pixel(p, pixels, out_row + x, r, g, b);
        }
    }
}
