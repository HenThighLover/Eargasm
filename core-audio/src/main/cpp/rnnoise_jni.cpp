#include <jni.h>
#include "rnnoise.h"

extern "C" {

JNIEXPORT jlong JNICALL
Java_app_openhearing_core_audio_dsp_RnNoiseDenoiser_nativeCreate(JNIEnv*, jobject) {
    return reinterpret_cast<jlong>(rnnoise_create(nullptr));
}

JNIEXPORT void JNICALL
Java_app_openhearing_core_audio_dsp_RnNoiseDenoiser_nativeDestroy(JNIEnv*, jobject, jlong h) {
    if (h != 0) rnnoise_destroy(reinterpret_cast<DenoiseState*>(h));
}

// in/out are exactly 480 floats scaled to +-32768 (RNNoise's expected range).
JNIEXPORT void JNICALL
Java_app_openhearing_core_audio_dsp_RnNoiseDenoiser_nativeProcess(
        JNIEnv* env, jobject, jlong h, jfloatArray in, jfloatArray out) {
    auto* st = reinterpret_cast<DenoiseState*>(h);
    jfloat* pin  = static_cast<jfloat*>(env->GetPrimitiveArrayCritical(in, nullptr));
    jfloat* pout = static_cast<jfloat*>(env->GetPrimitiveArrayCritical(out, nullptr));
    rnnoise_process_frame(st, pout, pin);
    env->ReleasePrimitiveArrayCritical(out, pout, 0);
    env->ReleasePrimitiveArrayCritical(in, pin, JNI_ABORT);
}

}