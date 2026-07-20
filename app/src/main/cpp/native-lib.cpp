#include <jni.h>
#include <string>
#include <vector>
#include <android/log.h>
#include <thread>
#include <memory>
#include "demosaic/demosaic.h"

#define LOG_TAG "wCam_Native"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

extern "C" JNIEXPORT void JNICALL
Java_com_example_wcam_camera_DemosaicEngine_nativeProcess(
        JNIEnv* env, jobject thiz, jobject buffer, jint width, jint height,
        jint rowStride, jint pixelStride, jobject outputBuffer,
        jstring algorithm, jfloatArray wbGainsArray, jfloatArray ccmArray,
        jfloatArray shadingMapArray, jint smWidth, jint smHeight,
        jint bayerPattern, jint blackLevel, jint whiteLevel, jfloat lscStrength,
        jint cropLeft, jint cropTop, jint cropWidth, jint cropHeight, jint rotation,
        jboolean highBitDepth) {

    init_lut();
    uint8_t* rawData = (uint8_t*)env->GetDirectBufferAddress(buffer);
    void* pixelsOut = env->GetDirectBufferAddress(outputBuffer);
    jfloat* wb = env->GetFloatArrayElements(wbGainsArray, NULL);
    // Balance Gr/Gb gains to eliminate high-frequency checkerboard artifacts
    if (wb != nullptr) {
        float avgG = (wb[1] + wb[2]) * 0.5f;
        wb[1] = avgG;
        wb[2] = avgG;
    }
    jfloat* ccm = env->GetFloatArrayElements(ccmArray, NULL);
    jfloat* sm = shadingMapArray ? env->GetFloatArrayElements(shadingMapArray, NULL) : nullptr;

    if (rawData == nullptr || pixelsOut == nullptr || wb == nullptr || ccm == nullptr) {
        LOGE("Error: JNI memory allocation failed!");
        if (wb) env->ReleaseFloatArrayElements(wbGainsArray, wb, JNI_ABORT);
        if (ccm) env->ReleaseFloatArrayElements(ccmArray, ccm, JNI_ABORT);
        if (sm) env->ReleaseFloatArrayElements(shadingMapArray, sm, JNI_ABORT);
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
    p.highBitDepth = highBitDepth;

    float diff = (float)(whiteLevel - blackLevel);
    p.normFactor = (diff > 0) ? (1.0f / diff) : 1.0f;

    p.hasCombinedCCM = false;
    if (ccm != nullptr && (ccm[0] != 0.0f || ccm[4] != 0.0f)) {
        const float bradford[] = {
             0.9555766f, -0.0230393f,  0.0631636f,
            -0.0282895f,  1.0099416f,  0.0210077f,
             0.0122982f, -0.0204830f,  1.3299098f
        };
        const float xyz2p3[] = {
             2.4934969f, -0.9313836f, -0.4027107f,
            -0.8294890f,  1.7626641f,  0.0236247f,
             0.0358458f, -0.0761724f,  0.9568845f
        };
        float mTemp[9];
        for (int i = 0; i < 3; ++i) {
            for (int j = 0; j < 3; ++j) {
                mTemp[i * 3 + j] = xyz2p3[i * 3 + 0] * bradford[0 * 3 + j] +
                                   xyz2p3[i * 3 + 1] * bradford[1 * 3 + j] +
                                   xyz2p3[i * 3 + 2] * bradford[2 * 3 + j];
            }
        }
        for (int i = 0; i < 3; ++i) {
            for (int j = 0; j < 3; ++j) {
                p.combinedCCM[i * 3 + j] = mTemp[i * 3 + 0] * ccm[0 * 3 + j] +
                                           mTemp[i * 3 + 1] * ccm[1 * 3 + j] +
                                           mTemp[i * 3 + 2] * ccm[2 * 3 + j];
            }
        }
        p.hasCombinedCCM = true;
    }

    auto get_rotated_idx = [&](int x, int y, int w, int h, int rot, int& outX, int& outY) {
        if (rot == 90) { outX = h - 1 - y; outY = x; }
        else if (rot == 180) { outX = w - 1 - x; outY = h - 1 - y; }
        else if (rot == 270) { outX = y; outY = w - 1 - x; }
        else { outX = x; outY = y; }
    };

    int rotW = (rotation % 180 == 0) ? cropWidth : cropHeight;
    int rotH = (rotation % 180 == 0) ? cropHeight : cropWidth;

    try {
        if (algo == "RgbCh" || algo == "Mono" || algo == "None") {
            #pragma omp parallel for schedule(dynamic, 16)
            for (int y = 0; y < cropHeight; ++y) {
                for (int x = 0; x < cropWidth; ++x) {
                    int sx = cropLeft + x;
                    int sy = cropTop + y;
                    float val = get_raw(p, sx, sy);
                    int channel = get_channel_type(sx, sy, p.bayerPattern);

                    float r = 0, g = 0, b = 0;
                    if (algo == "Mono") {
                        r = g = b = val * wb[channel];
                    } else {
                        if (channel == 0) r = val * wb[0];
                        else if (channel == 1 || channel == 2) g = val * wb[channel];
                        else b = val * wb[3];
                    }

                    int tx, ty;
                    get_rotated_idx(x, y, cropWidth, cropHeight, rotation, tx, ty);
                    write_pixel(p, pixelsOut, ty * rotW + tx, r, g, b);
                }
            }
        } else if (algo == "Bin4" || algo == "Bin9" || algo == "Bin16" || algo == "Bright4") {
            int step = (algo == "Bin4") ? 2 : (algo == "Bin9" ? 3 : 4);
            int outW = cropWidth / step;
            int outH = cropHeight / step;
            int rotOutW = (rotation % 180 == 0) ? outW : outH;

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
                    float rLin, gLin, bLin;
                    if (algo == "Bright4") {
                        // Pixel Summation: Sum 4R, Sum 8G/2, Sum 4B
                        rLin = sumR;
                        gLin = sumG * 0.5f;
                        bLin = sumB;
                    } else {
                        // Pixel Binning: Average of available pixels
                        rLin = (countR > 0) ? (sumR / countR) : 0;
                        gLin = (countG > 0) ? (sumG / countG) : 0;
                        bLin = (countB > 0) ? (sumB / countB) : 0;
                    }

                    int tx, ty;
                    get_rotated_idx(x, y, outW, outH, rotation, tx, ty);
                    write_pixel(p, pixelsOut, ty * rotOutW + tx, rLin, gLin, bLin);
                }
            }
        } else {
            // Elimination of intermediate 8-bit buffer for True 12-bit
            int pixel_size = highBitDepth ? 8 : 4;
            auto temp_ptr = std::unique_ptr<uint8_t[]>(new uint8_t[(uint64_t)width * height * pixel_size]);
            void* temp = (void*)temp_ptr.get();

            if (algo == "Fast") { demosaic_bilinear(p, temp); }
            else if (algo == "AHD") { demosaic_ahd(p, temp); }
            else if (algo == "EAHD") { demosaic_eahd(p, temp); }
            else if (algo == "HPHD") { demosaic_hphd(p, temp); }
            else if (algo == "VNG4") { demosaic_vng(p, temp); }
            else if (algo == "RCD") { demosaic_rcd(p, temp); }
            else if (algo == "AMaZE") { demosaic_amaze(p, temp); }
            else if (algo == "LMMSE") { demosaic_lmmse(p, temp); }
            else if (algo == "DCB") { demosaic_dcb(p, temp); }
            else if (algo == "IGV") { demosaic_igv(p, temp); }
            else { demosaic_bilinear(p, temp); }

            #pragma omp parallel for schedule(dynamic, 16)
            for (int y = 0; y < cropHeight; ++y) {
                for (int x = 0; x < cropWidth; ++x) {
                    int tx, ty;
                    get_rotated_idx(x, y, cropWidth, cropHeight, rotation, tx, ty);
                    uint64_t src_off = (uint64_t)(cropTop + y) * width + (cropLeft + x);
                    uint64_t dst_off = (uint64_t)ty * rotW + tx;
                    if (highBitDepth) {
                        ((uint64_t*)pixelsOut)[dst_off] = ((uint64_t*)temp)[src_off];
                    } else {
                        ((uint32_t*)pixelsOut)[dst_off] = ((uint32_t*)temp)[src_off];
                    }
                }
            }
        }
    } catch (...) { LOGE("Unknown native exception!"); }

    env->ReleaseFloatArrayElements(wbGainsArray, wb, JNI_ABORT);
    env->ReleaseFloatArrayElements(ccmArray, ccm, JNI_ABORT);
    if (sm) env->ReleaseFloatArrayElements(shadingMapArray, sm, JNI_ABORT);
}
