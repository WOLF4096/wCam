#include "demosaic_common.h"

void demosaic_bilinear(const DemosaicParams& p, int* pixels) {
    for (int y = 0; y < p.height; ++y) {
        int out_row = y * p.width;
        for (int x = 0; x < p.width; ++x) {
            float r, g, b;
            int channel = get_channel_type(x, y, p.bayerPattern);

            if (channel == 0) { // R
                r = get_pixel_wb(p, x, y);
                g = (get_pixel_wb(p, x-1, y) + get_pixel_wb(p, x+1, y) + get_pixel_wb(p, x, y-1) + get_pixel_wb(p, x, y+1)) * 0.25f;
                b = (get_pixel_wb(p, x-1, y-1) + get_pixel_wb(p, x+1, y-1) + get_pixel_wb(p, x-1, y+1) + get_pixel_wb(p, x+1, y+1)) * 0.25f;
            } else if (channel == 3) { // B
                b = get_pixel_wb(p, x, y);
                g = (get_pixel_wb(p, x-1, y) + get_pixel_wb(p, x+1, y) + get_pixel_wb(p, x, y-1) + get_pixel_wb(p, x, y+1)) * 0.25f;
                r = (get_pixel_wb(p, x-1, y-1) + get_pixel_wb(p, x+1, y-1) + get_pixel_wb(p, x-1, y+1) + get_pixel_wb(p, x+1, y+1)) * 0.25f;
            } else { // G
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
