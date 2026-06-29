#include <jni.h>
#include <string>
#include <vector>
#include <android/log.h>
#include <cmath>
#include <algorithm>

#define LOG_TAG "wCam_Native"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)

// Simple Tone Mapping & Gamma
inline float srgb_gamma(float v) {
    return v <= 0.0031308f ? 12.92f * v : 1.055f * pow(v, 1.0f / 2.4f) - 0.055f;
}

extern "C" JNIEXPORT void JNICALL
Java_com_example_wcam_camera_DemosaicEngine_nativeProcess(
        JNIEnv* env,
        jobject /* this */,
        jobject buffer,
        jint width,
        jint height,
        jint rowStride,
        jint pixelStride,
        jintArray outputPixels,
        jstring algorithm) {

    uint8_t* rawData = (uint8_t*)env->GetDirectBufferAddress(buffer);
    jint* pixels = env->GetIntArrayElements(outputPixels, NULL);

    // Assume RGGB Bayer Pattern
    // R G R G
    // G B G B

    auto getRaw = [&](int x, int y) -> float {
        x = std::clamp(x, 0, (int)width - 1);
        y = std::clamp(y, 0, (int)height - 1);
        int offset = (y * rowStride) + (x * pixelStride);
        uint16_t val = *(uint16_t*)(rawData + offset);
        return (float)(val & 0xFFFF) / 1023.0f; // Assume 10-bit for normalization
    };

    // Very simple Color Matrix (Example for a generic sensor)
    const float matrix[3][3] = {
        { 1.5f, -0.4f, -0.1f },
        { -0.2f, 1.4f, -0.2f },
        { -0.1f, -0.3f, 1.6f }
    };

    for (int y = 0; y < height; ++y) {
        for (int x = 0; x < width; ++x) {
            float r, g, b;

            bool isEvenRow = (y % 2 == 0);
            bool isEvenCol = (x % 2 == 0);

            if (isEvenRow && isEvenCol) { // Red pixel
                r = getRaw(x, y);
                g = (getRaw(x-1, y) + getRaw(x+1, y) + getRaw(x, y-1) + getRaw(x, y+1)) / 4.0f;
                b = (getRaw(x-1, y-1) + getRaw(x+1, y-1) + getRaw(x-1, y+1) + getRaw(x+1, y+1)) / 4.0f;
            } else if (!isEvenRow && !isEvenCol) { // Blue pixel
                b = getRaw(x, y);
                g = (getRaw(x-1, y) + getRaw(x+1, y) + getRaw(x, y-1) + getRaw(x, y+1)) / 4.0f;
                r = (getRaw(x-1, y-1) + getRaw(x+1, y-1) + getRaw(x-1, y+1) + getRaw(x+1, y+1)) / 4.0f;
            } else if (isEvenRow && !isEvenCol) { // Green (next to Red)
                g = getRaw(x, y);
                r = (getRaw(x-1, y) + getRaw(x+1, y)) / 2.0f;
                b = (getRaw(x, y-1) + getRaw(x, y+1)) / 2.0f;
            } else { // Green (next to Blue)
                g = getRaw(x, y);
                b = (getRaw(x-1, y) + getRaw(x+1, y)) / 2.0f;
                r = (getRaw(x, y-1) + getRaw(x, y+1)) / 2.0f;
            }

            // Apply Color Matrix
            float rr = matrix[0][0] * r + matrix[0][1] * g + matrix[0][2] * b;
            float gg = matrix[1][0] * r + matrix[1][1] * g + matrix[1][2] * b;
            float bb = matrix[2][0] * r + matrix[2][1] * g + matrix[2][2] * b;

            // Simple Tone Mapping (Reinhard)
            rr = rr / (rr + 0.2f);
            gg = gg / (gg + 0.2f);
            bb = bb / (bb + 0.2f);

            // Gamma correction
            int finalR = std::clamp((int)(srgb_gamma(rr) * 255.0f), 0, 255);
            int finalG = std::clamp((int)(srgb_gamma(gg) * 255.0f), 0, 255);
            int finalB = std::clamp((int)(srgb_gamma(bb) * 255.0f), 0, 255);

            pixels[y * width + x] = 0xFF000000 | (finalR << 16) | (finalG << 8) | finalB;
        }
    }

    env->ReleaseIntArrayElements(outputPixels, pixels, 0);
}
