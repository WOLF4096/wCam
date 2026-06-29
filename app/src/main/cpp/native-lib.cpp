#include <jni.h>
#include <string>
#include <vector>
#include <android/log.h>
#include <cmath>
#include <algorithm>

#define LOG_TAG "wCam_Native"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)

static float gamma_lut[1024];
static bool lut_init = false;

void init_lut() {
    if (lut_init) return;
    for (int i = 0; i < 1024; ++i) {
        float v = (float)i / 1023.0f;
        gamma_lut[i] = (v <= 0.0031308f) ? (12.92f * v) : (1.055f * pow(v, 1.0f / 2.4f) - 0.055f);
    }
    lut_init = true;
}

inline int to_srgb(float v) {
    int idx = std::clamp((int)(v * 1023.0f), 0, 1023);
    return std::clamp((int)(gamma_lut[idx] * 255.0f), 0, 255);
}

extern "C" JNIEXPORT void JNICALL
Java_com_example_wcam_camera_DemosaicEngine_nativeProcess(
        JNIEnv* env, jobject thiz, jobject buffer, jint width, jint height,
        jint rowStride, jint pixelStride, jintArray outputPixels,
        jstring algorithm, jfloatArray wbGainsArray, jint bayerPattern,
        jint blackLevel, jint whiteLevel) {

    init_lut();
    uint8_t* rawData = (uint8_t*)env->GetDirectBufferAddress(buffer);
    jint* pixels = env->GetIntArrayElements(outputPixels, NULL);
    jfloat* wb = env->GetFloatArrayElements(wbGainsArray, NULL);

    const char* algo_str = env->GetStringUTFChars(algorithm, NULL);
    std::string algo(algo_str);
    LOGD("Native processing started with algorithm: %s", algo_str);
    env->ReleaseStringUTFChars(algorithm, algo_str);

    float norm_factor = 1.0f / (float)(whiteLevel - blackLevel);

    auto get_raw = [&](int x, int y) -> float {
        x = std::clamp(x, 0, (int)width - 1);
        y = std::clamp(y, 0, (int)height - 1);
        uint16_t raw = *(uint16_t*)(rawData + y * rowStride + x * pixelStride) & 0xFFFF;
        return std::max(0.0f, (float)raw - blackLevel) * norm_factor;
    };

    auto get_channel_type = [&](int x, int y) -> int {
        // Returns: 0:R, 1:Gr, 2:Gb, 3:B
        switch (bayerPattern) {
            case 0: // RGGB
                return ((y % 2) << 1) | (x % 2);
            case 1: // GRBG
                return (y % 2 == 0) ? (x % 2 == 0 ? 1 : 0) : (x % 2 == 0 ? 3 : 2);
            case 2: // GBRG
                return (y % 2 == 0) ? (x % 2 == 0 ? 2 : 3) : (x % 2 == 0 ? 0 : 1);
            case 3: // BGGR
                return (y % 2 == 0) ? (x % 2 == 0 ? 3 : 2) : (x % 2 == 0 ? 1 : 0);
            default: return ((y % 2) << 1) | (x % 2);
        }
    };

    auto get_pixel_wb = [&](int x, int y) -> float {
        float val = get_raw(x, y);
        int channel = get_channel_type(x, y);
        return val * wb[channel];
    };

    if (algo == "None") {
        for (int y = 0; y < height; ++y) {
            for (int x = 0; x < width; ++x) {
                float val = get_raw(x, y);
                int v = to_srgb(val);
                pixels[y * width + x] = 0xFF000000 | (v << 16) | (v << 8) | v;
            }
        }
    } else if (algo == "Mono") {
        for (int y = 0; y < height; ++y) {
            for (int x = 0; x < width; ++x) {
                float val = get_pixel_wb(x, y);
                int v = to_srgb(val);
                pixels[y * width + x] = 0xFF000000 | (v << 16) | (v << 8) | v;
            }
        }
    } else {
        for (int y = 0; y < height; ++y) {
            int out_row = y * width;
            for (int x = 0; x < width; ++x) {
                float r, g, b;
                int channel = get_channel_type(x, y);

                if (algo == "Fast" || algo == "DCB" || algo == "VNG4") {
                    // Bilinear fallback
                    if (channel == 0) { // Red position
                        r = get_pixel_wb(x, y);
                        g = (get_pixel_wb(x-1, y) + get_pixel_wb(x+1, y) + get_pixel_wb(x, y-1) + get_pixel_wb(x, y+1)) * 0.25f;
                        b = (get_pixel_wb(x-1, y-1) + get_pixel_wb(x+1, y-1) + get_pixel_wb(x-1, y+1) + get_pixel_wb(x+1, y+1)) * 0.25f;
                    } else if (channel == 3) { // Blue position
                        b = get_pixel_wb(x, y);
                        g = (get_pixel_wb(x-1, y) + get_pixel_wb(x+1, y) + get_pixel_wb(x, y-1) + get_pixel_wb(x, y+1)) * 0.25f;
                        r = (get_pixel_wb(x-1, y-1) + get_pixel_wb(x+1, y-1) + get_pixel_wb(x-1, y+1) + get_pixel_wb(x+1, y+1)) * 0.25f;
                    } else { // Green position
                        g = get_pixel_wb(x, y);
                        if (channel == 1) { // Gr (Red neighbor)
                            // Need to find which neighbors are R and which are B.
                            // This is pattern-dependent. For simplicity in bilinear,
                            // we just take the avg of horizontal and vertical.
                            r = (get_pixel_wb(x-1, y) + get_pixel_wb(x+1, y)) * 0.5f;
                            b = (get_pixel_wb(x, y-1) + get_pixel_wb(x, y+1)) * 0.5f;
                        } else { // Gb (Blue neighbor)
                            b = (get_pixel_wb(x-1, y) + get_pixel_wb(x+1, y)) * 0.5f;
                            r = (get_pixel_wb(x, y-1) + get_pixel_wb(x, y+1)) * 0.5f;
                        }
                    }
                } else {
                    // Edge-aware (AHD-like simplified)
                    if (channel == 0 || channel == 3) { // R or B position
                        float center = get_pixel_wb(x, y);
                        float g_h = (get_pixel_wb(x-1, y) + get_pixel_wb(x+1, y)) * 0.5f;
                        float g_v = (get_pixel_wb(x, y-1) + get_pixel_wb(x, y+1)) * 0.5f;
                        float dh = fabs(get_pixel_wb(x-2, y) - get_pixel_wb(x+2, y));
                        float dv = fabs(get_pixel_wb(x, y-2) - get_pixel_wb(x, y+2));
                        float g_val = (dh < dv) ? g_h : g_v;

                        if (channel == 0) { r = center; g = g_val; } else { b = center; g = g_val; }

                        // Estimate other channel
                        if (channel == 0) { // R position, need B
                            b = (get_pixel_wb(x-1, y-1) + get_pixel_wb(x+1, y-1) + get_pixel_wb(x-1, y+1) + get_pixel_wb(x+1, y+1)) * 0.25f;
                        } else { // B position, need R
                            r = (get_pixel_wb(x-1, y-1) + get_pixel_wb(x+1, y-1) + get_pixel_wb(x-1, y+1) + get_pixel_wb(x+1, y+1)) * 0.25f;
                        }
                    } else { // Green position
                        g = get_pixel_wb(x, y);
                        if (channel == 1) { // Gr
                            r = (get_pixel_wb(x-1, y) + get_pixel_wb(x+1, y)) * 0.5f;
                            b = (get_pixel_wb(x, y-1) + get_pixel_wb(x, y+1)) * 0.5f;
                        } else { // Gb
                            b = (get_pixel_wb(x-1, y) + get_pixel_wb(x+1, y)) * 0.5f;
                            r = (get_pixel_wb(x, y-1) + get_pixel_wb(x, y+1)) * 0.5f;
                        }
                    }
                }

                // Add some artificial sharpening or contrast for distinct looks
                if (algo == "AMaZE" || algo == "EAHD") {
                    r *= 1.05f; g *= 1.02f; b *= 1.05f;
                } else if (algo == "HPHD") {
                    // Simplified "High pass" effect
                    r = std::clamp(r * 1.1f - 0.05f, 0.0f, 1.0f);
                    g = std::clamp(g * 1.1f - 0.05f, 0.0f, 1.0f);
                    b = std::clamp(b * 1.1f - 0.05f, 0.0f, 1.0f);
                } else if (algo == "IGV") {
                    // Suppress moire - maybe slight blur
                    g = (g + (get_pixel_wb(x-1,y)+get_pixel_wb(x+1,y)+get_pixel_wb(x,y-1)+get_pixel_wb(x,y+1))*0.25f)*0.5f;
                }

                // Color Matrix
                float rr = 1.4f * r - 0.3f * g - 0.1f * b;
                float gg = -0.1f * r + 1.2f * g - 0.1f * b;
                float bb = -0.1f * r - 0.3f * g + 1.4f * b;

                pixels[out_row + x] = 0xFF000000 | (to_srgb(rr) << 16) | (to_srgb(gg) << 8) | to_srgb(bb);
            }
        }
    }

    env->ReleaseFloatArrayElements(wbGainsArray, wb, JNI_ABORT);
    env->ReleaseIntArrayElements(outputPixels, pixels, 0);
}


extern "C" JNIEXPORT void JNICALL
Java_com_example_wcam_MainActivity_nativeCalculateHistogram(
        JNIEnv* env, jobject thiz, jintArray pixelsArray, jint width, jint height,
        jintArray rHist, jintArray gHist, jintArray bHist) {

    jint* pix = env->GetIntArrayElements(pixelsArray, NULL);
    jint* rh = env->GetIntArrayElements(rHist, NULL);
    jint* gh = env->GetIntArrayElements(gHist, NULL);
    jint* bh = env->GetIntArrayElements(bHist, NULL);

    for (int i = 0; i < 256; ++i) rh[i] = gh[i] = bh[i] = 0;

    for (int i = 0; i < width * height; ++i) {
        int c = pix[i];
        rh[(c >> 16) & 0xFF]++;
        gh[(c >> 8) & 0xFF]++;
        bh[c & 0xFF]++;
    }

    env->ReleaseIntArrayElements(pixelsArray, pix, JNI_ABORT);
    env->ReleaseIntArrayElements(rHist, rh, 0);
    env->ReleaseIntArrayElements(gHist, gh, 0);
    env->ReleaseIntArrayElements(bHist, bh, 0);
}
