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
        jint bayerPattern, jint blackLevel, jint whiteLevel, jfloat lscStrength,
        jint cropLeft, jint cropTop, jint cropWidth, jint cropHeight, jint rotation) {

    init_lut();
    uint8_t* rawData = (uint8_t*)env->GetDirectBufferAddress(buffer);
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

    auto get_rotated_idx = [&](int x, int y, int w, int h, int rot, int& outX, int& outY) {
        if (rot == 90) { outX = h - 1 - y; outY = x; }
        else if (rot == 180) { outX = w - 1 - x; outY = h - 1 - y; }
        else if (rot == 270) { outX = y; outY = w - 1 - x; }
        else { outX = x; outY = y; }
    };

    int rotW = (rotation % 180 == 0) ? cropWidth : cropHeight;
    int rotH = (rotation % 180 == 0) ? cropHeight : cropWidth;

    try {
        if (algo == "Bin4" || algo == "Bin9" || algo == "Bin16") {
            int step = (algo == "Bin4") ? 2 : (algo == "Bin9" ? 3 : 4);
            int outW = cropWidth / step;
            int outH = cropHeight / step;
            int rotOutW = (rotation % 180 == 0) ? outW : outH;
            int rotOutH = (rotation % 180 == 0) ? outH : outW;

            #pragma omp parallel for schedule(dynamic, 16)
            for (int y = 0; y < outH; ++y) {
                for (int x = 0; x < outW; ++x) {
                    float sumR = 0, sumG = 0, sumB = 0;
                    int countR = 0, countG = 0, countB = 0;

                    for (int dy = 0; dy < step; ++dy) {
                        for (int dx = 0; dx < step; ++dx) {
                            int sx = cropLeft + x * step + dx;
                            int sy = cropTop + y * step + dy;
                            float val = get_raw(p, sx, sy);
                            int channel = get_channel_type(sx, sy, p.bayerPattern);

                            if (channel == 0) { sumR += val * wb[0]; countR++; }
                            else if (channel == 1 || channel == 2) { sumG += val * ((channel == 1) ? wb[1] : wb[2]); countG++; }
                            else { sumB += val * wb[3]; countB++; }
                        }
                    }

                    float rLin = (countR > 0) ? (sumR / countR) : 0;
                    float gLin = (countG > 0) ? (sumG / countG) : 0;
                    float bLin = (countB > 0) ? (sumB / countB) : 0;

                    uint32_t mixed = rgb_to_pixel(rLin, gLin, bLin);
                    int tx, ty;
                    get_rotated_idx(x, y, outW, outH, rotation, tx, ty);
                    pixels[ty * rotOutW + tx] = mixed;
                }
            }
        } else if (algo == "RgbCh") {
            int gridW = rotW;
            int halfRotW = rotW / 2;
            int halfRotH = rotH / 2;
            int halfCropW = cropWidth / 2;
            int halfCropH = cropHeight / 2;

            #pragma omp parallel for schedule(dynamic, 16)
            for (int y = 0; y < cropHeight; y += 2) {
                for (int x = 0; x < cropWidth; x += 2) {
                    int sx = cropLeft + x;
                    int sy = cropTop + y;

                    // 直接采样 2x2 块以获取完整的 RGB 信息
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

                    uint32_t mixed = rgb_to_pixel(rLin, gLin, bLin);
                    int r8 = (mixed >> 16) & 0xFF, g8 = (mixed >> 8) & 0xFF, b8 = mixed & 0xFF;
                    uint32_t rGray = 0xFF000000 | (r8 << 16) | (r8 << 8) | r8;
                    uint32_t gGray = 0xFF000000 | (g8 << 16) | (g8 << 8) | g8;
                    uint32_t bGray = 0xFF000000 | (b8 << 16) | (b8 << 8) | b8;

                    int tx, ty;
                    // 对降采样后的坐标 (x/2, y/2) 应用旋转
                    get_rotated_idx(x / 2, y / 2, halfCropW, halfCropH, rotation, tx, ty);

                    pixels[ty * gridW + tx] = mixed;
                    pixels[ty * gridW + (tx + halfRotW)] = gGray;
                    pixels[(ty + halfRotH) * gridW + tx] = rGray;
                    pixels[(ty + halfRotH) * gridW + (tx + halfRotW)] = bGray;
                }
            }
        } else {
            // 对其他算法，先执行完整解拜耳到临时缓冲区，再进行裁剪旋转
            // 这种方式能保证边缘质量
            std::vector<uint32_t> temp(width * height);
            if (algo == "None") {
                #pragma omp parallel for schedule(dynamic, 16)
                for (int y = 0; y < height; ++y) {
                    for (int x = 0; x < width; ++x) {
                        float val = get_raw(p, x, y);
                        int v = to_srgb(val);
                        int channel = get_channel_type(x, y, bayerPattern);
                        if (channel == 0) temp[y * width + x] = 0xFF000000 | (v << 16);
                        else if (channel == 1 || channel == 2) temp[y * width + x] = 0xFF000000 | (v << 8);
                        else temp[y * width + x] = 0xFF000000 | v;
                    }
                }
            } else if (algo == "Mono") {
                #pragma omp parallel for schedule(dynamic, 16)
                for (int y = 0; y < height; ++y) {
                    for (int x = 0; x < width; ++x) {
                        float val = get_raw(p, x, y);
                        int v = to_srgb(val);
                        temp[y * width + x] = 0xFF000000 | (v << 16) | (v << 8) | v;
                    }
                }
            } else if (algo == "Fast") { demosaic_bilinear(p, (int*)temp.data()); }
            else if (algo == "AHD" || algo == "EAHD" || algo == "HPHD") { demosaic_ahd(p, (int*)temp.data()); }
            else if (algo == "VNG4") { demosaic_vng(p, (int*)temp.data()); }
            else if (algo == "RCD") { demosaic_rcd(p, (int*)temp.data()); }
            else if (algo == "AMaZE") { demosaic_amaze(p, (int*)temp.data()); }
            else if (algo == "LMMSE") { demosaic_lmmse(p, (int*)temp.data()); }
            else if (algo == "DCB") { demosaic_dcb(p, (int*)temp.data()); }
            else { demosaic_bilinear(p, (int*)temp.data()); }

            // 裁剪旋转到输出
            #pragma omp parallel for schedule(dynamic, 16)
            for (int y = 0; y < cropHeight; ++y) {
                for (int x = 0; x < cropWidth; ++x) {
                    int tx, ty;
                    get_rotated_idx(x, y, cropWidth, cropHeight, rotation, tx, ty);
                    pixels[ty * rotW + tx] = temp[(cropTop + y) * width + (cropLeft + x)];
                }
            }
        }
    } catch (...) { LOGE("Unknown native exception!"); }

    env->ReleaseFloatArrayElements(wbGainsArray, wb, JNI_ABORT);
    env->ReleaseFloatArrayElements(ccmArray, ccm, JNI_ABORT);
    if (sm) env->ReleaseFloatArrayElements(shadingMapArray, sm, JNI_ABORT);
    env->ReleaseIntArrayElements(outputPixels, pixels, 0);
}
