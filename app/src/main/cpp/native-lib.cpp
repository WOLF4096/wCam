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
        JNIEnv* env, jobject, jobject buffer, jint width, jint height,
        jint rowStride, jint pixelStride, jintArray outputPixels,
        jstring algorithm, jfloatArray wbGainsArray, jint bayerPattern,
        jint blackLevel, jint whiteLevel) {

    init_lut();
    uint8_t* rawData = (uint8_t*)env->GetDirectBufferAddress(buffer);
    jint* pixels = env->GetIntArrayElements(outputPixels, NULL);
    jfloat* wb = env->GetFloatArrayElements(wbGainsArray, NULL);

    const char* algo_str = env->GetStringUTFChars(algorithm, NULL);
    bool high_quality = (strcmp(algo_str, "High Quality") == 0);

    float norm_factor = 1.0f / (float)(whiteLevel - blackLevel);

    auto get_pixel_wb = [&](int x, int y) -> float {
        x = std::clamp(x, 0, (int)width - 1);
        y = std::clamp(y, 0, (int)height - 1);
        uint16_t raw = *(uint16_t*)(rawData + y * rowStride + x * pixelStride) & 0xFFFF;
        float val = std::max(0.0f, (float)raw - blackLevel) * norm_factor;

        // RGGB Pattern: (0,0):R, (0,1):Gr, (1,0):Gb, (1,1):B
        int type = ((y % 2) << 1) | (x % 2);
        return val * wb[type];
    };

    for (int y = 0; y < height; ++y) {
        int out_row = y * width;
        for (int x = 0; x < width; ++x) {
            float r, g, b;
            int type = ((y % 2) << 1) | (x % 2);

            if (type == 0) { // Red position
                r = get_pixel_wb(x, y);
                if (high_quality) {
                    float g_h = (get_pixel_wb(x-1, y) + get_pixel_wb(x+1, y)) * 0.5f;
                    float g_v = (get_pixel_wb(x, y-1) + get_pixel_wb(x, y+1)) * 0.5f;
                    g = (fabs(get_pixel_wb(x-2, y) - get_pixel_wb(x+2, y)) < fabs(get_pixel_wb(x, y-2) - get_pixel_wb(x, y+2))) ? g_h : g_v;
                } else {
                    g = (get_pixel_wb(x-1, y) + get_pixel_wb(x+1, y) + get_pixel_wb(x, y-1) + get_pixel_wb(x, y+1)) * 0.25f;
                }
                b = (get_pixel_wb(x-1, y-1) + get_pixel_wb(x+1, y-1) + get_pixel_wb(x-1, y+1) + get_pixel_wb(x+1, y+1)) * 0.25f;
            } else if (type == 3) { // Blue position
                b = get_pixel_wb(x, y);
                if (high_quality) {
                    float g_h = (get_pixel_wb(x-1, y) + get_pixel_wb(x+1, y)) * 0.5f;
                    float g_v = (get_pixel_wb(x, y-1) + get_pixel_wb(x, y+1)) * 0.5f;
                    g = (fabs(get_pixel_wb(x-2, y) - get_pixel_wb(x+2, y)) < fabs(get_pixel_wb(x, y-2) - get_pixel_wb(x, y+2))) ? g_h : g_v;
                } else {
                    g = (get_pixel_wb(x-1, y) + get_pixel_wb(x+1, y) + get_pixel_wb(x, y-1) + get_pixel_wb(x, y+1)) * 0.25f;
                }
                r = (get_pixel_wb(x-1, y-1) + get_pixel_wb(x+1, y-1) + get_pixel_wb(x-1, y+1) + get_pixel_wb(x+1, y+1)) * 0.25f;
            } else { // Green position
                g = get_pixel_wb(x, y);
                if (type == 1) { // Gr (Red neighbor)
                    r = (get_pixel_wb(x-1, y) + get_pixel_wb(x+1, y)) * 0.5f;
                    b = (get_pixel_wb(x, y-1) + get_pixel_wb(x, y+1)) * 0.5f;
                } else { // Gb (Blue neighbor)
                    b = (get_pixel_wb(x-1, y) + get_pixel_wb(x+1, y)) * 0.5f;
                    r = (get_pixel_wb(x, y-1) + get_pixel_wb(x, y+1)) * 0.5f;
                }
            }

            // Simple Standard Color Matrix
            float rr = 1.4f * r - 0.3f * g - 0.1f * b;
            float gg = -0.1f * r + 1.2f * g - 0.1f * b;
            float bb = -0.1f * r - 0.3f * g + 1.4f * b;

            pixels[out_row + x] = 0xFF000000 | (to_srgb(rr) << 16) | (to_srgb(gg) << 8) | to_srgb(bb);
        }
    }

    env->ReleaseStringUTFChars(algorithm, algo_str);
    env->ReleaseFloatArrayElements(wbGainsArray, wb, JNI_ABORT);
    env->ReleaseIntArrayElements(outputPixels, pixels, 0);
}

extern "C" JNIEXPORT void JNICALL
Java_com_example_wcam_MainActivity_nativeCalculateHistogram(
        JNIEnv* env, jobject, jintArray pixelsArray, jint width, jint height,
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
