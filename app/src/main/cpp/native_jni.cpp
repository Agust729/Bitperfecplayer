#include <jni.h>
#include <memory>
#include <string>
#include "usb_audio_driver.h"

namespace {
std::unique_ptr<bitperfect::UsbAudioDriver> gDriver;
}

extern "C" {

JNIEXPORT jboolean JNICALL
Java_com_bitperfect_player_audio_DirectUsbDacEngine_nativeOpen(
        JNIEnv* env,
        jobject /* thiz */,
        jint fd,
        jbyteArray rawDescriptors,
        jboolean isHighSpeed) {
    if (!gDriver) {
        gDriver = std::make_unique<bitperfect::UsbAudioDriver>();
    }
    jsize len = env->GetArrayLength(rawDescriptors);
    jbyte* bytes = env->GetByteArrayElements(rawDescriptors, nullptr);
    bool ok = gDriver->open(fd, reinterpret_cast<const uint8_t*>(bytes), static_cast<size_t>(len), isHighSpeed);
    env->ReleaseByteArrayElements(rawDescriptors, bytes, JNI_ABORT);
    return ok ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_com_bitperfect_player_audio_DirectUsbDacEngine_nativeConfigure(
        JNIEnv* /* env */,
        jobject /* thiz */,
        jint sampleRate,
        jint bitDepth,
        jint channels) {
    if (!gDriver) return JNI_FALSE;
    return gDriver->configure(
        static_cast<uint32_t>(sampleRate),
        static_cast<uint8_t>(bitDepth),
        static_cast<uint8_t>(channels)
    ) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_com_bitperfect_player_audio_DirectUsbDacEngine_nativeStart(
        JNIEnv* /* env */,
        jobject /* thiz */) {
    if (!gDriver) return JNI_FALSE;
    return gDriver->start() ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jint JNICALL
Java_com_bitperfect_player_audio_DirectUsbDacEngine_nativeWritePcm(
        JNIEnv* env,
        jobject /* thiz */,
        jbyteArray pcmData,
        jint offset,
        jint length) {
    if (!gDriver || pcmData == nullptr || length <= 0) return 0;
    jbyte* ptr = env->GetByteArrayElements(pcmData, nullptr);
    size_t written = gDriver->writePcm(
        reinterpret_cast<const uint8_t*>(ptr + offset),
        static_cast<size_t>(length)
    );
    env->ReleaseByteArrayElements(pcmData, ptr, JNI_ABORT);
    return static_cast<jint>(written);
}

JNIEXPORT void JNICALL
Java_com_bitperfect_player_audio_DirectUsbDacEngine_nativeStop(
        JNIEnv* /* env */,
        jobject /* thiz */) {
    if (gDriver) gDriver->stop();
}

JNIEXPORT void JNICALL
Java_com_bitperfect_player_audio_DirectUsbDacEngine_nativeClose(
        JNIEnv* /* env */,
        jobject /* thiz */) {
    if (gDriver) gDriver->close();
}

JNIEXPORT jint JNICALL
Java_com_bitperfect_player_audio_DirectUsbDacEngine_nativeGetBufferFillPercent(
        JNIEnv* /* env */,
        jobject /* thiz */) {
    return gDriver ? gDriver->getBufferFillPercent() : 0;
}

JNIEXPORT jstring JNICALL
Java_com_bitperfect_player_audio_DirectUsbDacEngine_nativeGetDeviceSummary(
        JNIEnv* env,
        jobject /* thiz */) {
    std::string summary = gDriver ? gDriver->getDeviceSummary() : "No conectado";
    return env->NewStringUTF(summary.c_str());
}

} // extern "C"
