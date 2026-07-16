#include <jni.h>
#include <string>
#include <vector>
#include <android/log.h>
#include <thread>
#include "demosaic/demosaic.h"

#define LOG_TAG "wCam_Native"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

extern "C" JNIEXPORT void JNICALL
Java_com_example_wcam_camera_DemosaicEngine_nativeProcess(
        JNIEnv* env, jobject thiz, jobject buffer, jint width, jint height,
        jint rowStride, jint pixelStride, jintArray outputPixels,
        jstring algorithm, jfloatArray wbGainsArray, jfloatArray ccmArray,
        jfloatArray shadingMapArray, jint smWidth, jint smHeight,
        jint bayerPattern, jint blackLevel, jint whiteLevel, jfloat lscStrength) {

    init_lut();
    uint8_t* rawData = (uint8_t*)env->GetDirectBufferAddress(buffer);
    jlong rawDataSize = env->GetDirectBufferCapacity(buffer);

    jint* pixels = env->GetIntArrayElements(outputPixels, NULL);
    jfloat* wb = env->GetFloatArrayElements(wbGainsArray, NULL);
    jfloat* ccm = env->GetFloatArrayElements(ccmArray, NULL);
    jfloat* sm = shadingMapArray ? env->GetFloatArrayElements(shadingMapArray, NULL) : nullptr;

    if (rawData == nullptr || pixels == nullptr || wb == nullptr || ccm == nullptr) {
        LOGE("Error: JNI memory allocation failed!");
        if (wb) env->ReleaseFloatArrayElements(wbGainsArray, wb, JNI_ABORT);
        if (ccm) env->ReleaseFloatArrayElements(ccmArray, ccm, JNI_ABORT);
        if (sm) env->ReleaseFloatArrayElements(shadingMapArray, sm, JNI_ABORT);
        if (pixels) env->ReleaseIntArrayElements(outputPixels, pixels, 0);
        return;
    }

    const char* algo_str = env->GetStringUTFChars(algorithm, NULL);
    std::string algo(algo_str ? algo_str : "Fast");
    if (algo_str) env->ReleaseStringUTFChars(algorithm, algo_str);

    LOGD("Native processing started with algorithm: %s, size: %lld", algo.c_str(), rawDataSize);

    DemosaicParams p;
    p.rawData = rawData;
    p.width = width;
    p.height = height;
    p.rowStride = rowStride;
    p.pixelStride = pixelStride;
    p.wbGains = wb;
    p.ccm = ccm;
    p.shadingMap = sm;
    p.smWidth = smWidth;
    p.smHeight = smHeight;
    p.bayerPattern = bayerPattern;
    p.blackLevel = blackLevel;
    p.whiteLevel = whiteLevel;
    p.lscStrength = lscStrength;
    p.opticalCenterX = (float)width / 2.0f;
    p.opticalCenterY = (float)height / 2.0f;
    p.maxDistSq = p.opticalCenterX * p.opticalCenterX + p.opticalCenterY * p.opticalCenterY;

    float diff = (float)(whiteLevel - blackLevel);
    p.normFactor = (diff > 0) ? (1.0f / diff) : 1.0f;

    LOGD("Demosaic parameters: W=%d, H=%d, RS=%d, PS=%d, rawData=%p", width, height, rowStride, pixelStride, rawData);

    if (rawData == nullptr) {
        LOGE("Error: rawData pointer is null!");
        env->ReleaseFloatArrayElements(wbGainsArray, wb, JNI_ABORT);
        env->ReleaseFloatArrayElements(ccmArray, ccm, JNI_ABORT);
        if (sm) env->ReleaseFloatArrayElements(shadingMapArray, sm, JNI_ABORT);
        env->ReleaseIntArrayElements(outputPixels, pixels, 0);
        return;
    }

    try {
        if (algo == "RgbCh") {
            // 目标呈现布局 (固定且不受旋转影响)：
            // [ Mixed | G Gray ]
            // [ R Gray | B Gray ]
            int halfW = width / 2;
            int halfH = height / 2;

            #pragma omp parallel for schedule(dynamic, 16)
            for (int y = 0; y < halfH; ++y) {
                for (int x = 0; x < halfW; ++x) {
                    // 1. 获取 2x2 原始像素块
                    int sx = x * 2;
                    int sy = y * 2;

                    float v00 = get_raw(p, sx, sy);
                    float v01 = get_raw(p, sx + 1, sy);
                    float v10 = get_raw(p, sx, sy + 1);
                    float v11 = get_raw(p, sx + 1, sy + 1);

                    float rLin, gLin, bLin;
                    if (p.bayerPattern == RGGB) {
                        rLin = v00 * wb[0]; gLin = (v01 * wb[1] + v10 * wb[2]) * 0.5f; bLin = v11 * wb[3];
                    } else if (p.bayerPattern == GRBG) {
                        gLin = (v00 * wb[1] + v11 * wb[2]) * 0.5f; rLin = v01 * wb[0]; bLin = v10 * wb[3];
                    } else if (p.bayerPattern == GBRG) {
                        gLin = (v00 * wb[2] + v11 * wb[1]) * 0.5f; bLin = v01 * wb[3]; rLin = v10 * wb[0];
                    } else { // BGGR
                        bLin = v00 * wb[3]; gLin = (v01 * wb[2] + v10 * wb[1]) * 0.5f; rLin = v11 * wb[0];
                    }

                    // 2. 核心色彩映射
                    uint32_t mixed = rgb_to_pixel(rLin, gLin, bLin);

                    int r8 = (mixed >> 16) & 0xFF;
                    int g8 = (mixed >> 8) & 0xFF;
                    int b8 = mixed & 0xFF;

                    uint32_t rGray = 0xFF000000 | (r8 << 16) | (r8 << 8) | r8;
                    uint32_t gGray = 0xFF000000 | (g8 << 16) | (g8 << 8) | g8;
                    uint32_t bGray = 0xFF000000 | (b8 << 16) | (b8 << 8) | b8;

                    // 3. 填充像素 (严格按照指定的坐标布局)
                    // 左上: Mixed
                    pixels[y * width + x] = mixed;
                    // 右上: G Gray
                    pixels[y * width + (x + halfW)] = gGray;
                    // 左下: R Gray
                    pixels[(y + halfH) * width + x] = rGray;
                    // 右下: B Gray
                    pixels[(y + halfH) * width + (x + halfW)] = bGray;
                }
            }
        } else if (algo == "None") {
            #pragma omp parallel for schedule(dynamic, 16)
            for (int y = 0; y < height; ++y) {
                for (int x = 0; x < width; ++x) {
                    float val = get_raw(p, x, y);
                    int v = to_srgb(val);
                    int channel = get_channel_type(x, y, bayerPattern);
                    if (channel == 0) pixels[y * width + x] = 0xFF000000 | (v << 16);
                    else if (channel == 1 || channel == 2) pixels[y * width + x] = 0xFF000000 | (v << 8);
                    else pixels[y * width + x] = 0xFF000000 | v;
                }
            }
        } else if (algo == "Mono") {
            #pragma omp parallel for schedule(dynamic, 16)
            for (int y = 0; y < height; ++y) {
                for (int x = 0; x < width; ++x) {
                    float val = get_raw(p, x, y);
                    int v = to_srgb(val);
                    pixels[y * width + x] = 0xFF000000 | (v << 16) | (v << 8) | v;
                }
            }
        } else if (algo == "Fast") {
            demosaic_bilinear(p, pixels);
        } else if (algo == "AHD" || algo == "EAHD" || algo == "HPHD") {
            demosaic_ahd(p, pixels);
        } else if (algo == "VNG4") {
            demosaic_vng(p, pixels);
        } else if (algo == "RCD") {
            demosaic_rcd(p, pixels);
        } else if (algo == "AMaZE") {
            demosaic_amaze(p, pixels);
        } else if (algo == "LMMSE") {
            demosaic_lmmse(p, pixels);
        } else if (algo == "DCB") {
            demosaic_dcb(p, pixels);
        } else if (algo == "IGV") {
            demosaic_ahd(p, pixels);
        } else {
            demosaic_bilinear(p, pixels); // Default fallback
        }
    } catch (const std::exception& e) {
        LOGE("Native exception: %s", e.what());
    } catch (...) {
        LOGE("Unknown native exception occurred!");
    }

    env->ReleaseFloatArrayElements(wbGainsArray, wb, JNI_ABORT);
    env->ReleaseFloatArrayElements(ccmArray, ccm, JNI_ABORT);
    if (sm) env->ReleaseFloatArrayElements(shadingMapArray, sm, JNI_ABORT);
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
