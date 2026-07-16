#include <jni.h>
#include <vector>

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
