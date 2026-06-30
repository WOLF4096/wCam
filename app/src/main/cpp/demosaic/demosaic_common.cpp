#include "demosaic_common.h"

float gamma_lut[1024];
bool lut_init_flag = false;

void init_lut() {
    if (lut_init_flag) return;
    for (int i = 0; i < 1024; ++i) {
        float v = (float)i / 1023.0f;
        gamma_lut[i] = (v <= 0.0031308f) ? (12.92f * v) : (1.055f * pow(v, 1.0f / 2.4f) - 0.055f);
    }
    lut_init_flag = true;
}
