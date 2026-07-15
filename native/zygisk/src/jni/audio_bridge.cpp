#include <jni.h>

/**
 * @file audio_bridge.cpp
 * @brief JNI bridge that transforms the exact requested Java audio region in
 * place. A false return guarantees that the caller-visible region was not
 * committed by this bridge.
 */

#include <cstddef>
#include <cstdint>
#include <limits>
#include <mutex>
#include <new>
#include <vector>

#ifdef __ANDROID__
#include <android/log.h>
#else
#define __android_log_print(...) ((void)0)
#define ANDROID_LOG_WARN 5
#endif

#include "audio/pcm_buffer_processor.h"
#include "jni/native_bridge_runtime.h"

namespace
{
    constexpr const char *kLogTag = "EchidnaAudioBridge";
    constexpr size_t kMaxProcessBufferBytes = 8 * 1024 * 1024;
    constexpr jint kMaxChannelCount = 8;

    bool ByteCountForUnits(jint units, size_t bytes_per_unit, size_t *out)
    {
        if (units <= 0 || bytes_per_unit == 0 || !out)
        {
            return false;
        }
        const size_t count = static_cast<size_t>(units);
        if (count > std::numeric_limits<size_t>::max() / bytes_per_unit)
        {
            return false;
        }
        *out = count * bytes_per_unit;
        return true;
    }

    bool IsValidRange(jsize container_length, jint offset, jint length)
    {
        return container_length >= 0 && offset >= 0 && length > 0 &&
               static_cast<int64_t>(offset) + static_cast<int64_t>(length) <=
                   static_cast<int64_t>(container_length);
    }

    bool IsValidAudioRequest(size_t byte_count, jint sample_rate, jint channel_count)
    {
        return byte_count > 0 && byte_count <= kMaxProcessBufferBytes && sample_rate > 0 &&
               channel_count > 0 && channel_count <= kMaxChannelCount;
    }

    bool ValidateArrayRequest(JNIEnv *env,
                              jarray array,
                              jint offset,
                              jint length,
                              size_t bytes_per_unit,
                              jint sample_rate,
                              jint channel_count,
                              size_t *byte_count)
    {
        if (!env || !array ||
            !IsValidRange(env->GetArrayLength(array), offset, length) ||
            !ByteCountForUnits(length, bytes_per_unit, byte_count))
        {
            return false;
        }
        return IsValidAudioRequest(*byte_count, sample_rate, channel_count);
    }

    bool ValidateDirectBufferRequest(JNIEnv *env,
                                     jobject buffer,
                                     jint position,
                                     jint length,
                                     jint sample_rate,
                                     jint channel_count)
    {
        if (!env || !buffer || position < 0 || length <= 0)
        {
            return false;
        }
        const jlong capacity = env->GetDirectBufferCapacity(buffer);
        return capacity >= 0 &&
               static_cast<int64_t>(position) + static_cast<int64_t>(length) <= capacity &&
               IsValidAudioRequest(static_cast<size_t>(length), sample_rate, channel_count);
    }

    class AudioScratch
    {
    public:
        bool process(void *buffer,
                     size_t byte_count,
                     echidna::audio::PcmFormat format,
                     jint sample_rate,
                     jint channel_count)
        {
            echidna::audio::BufferLayout layout;
            if (!echidna::audio::ResolveBufferLayout(byte_count,
                                                     format,
                                                     static_cast<uint32_t>(channel_count),
                                                     &layout))
            {
                return false;
            }

            std::lock_guard<std::mutex> lock(mutex_);
            try
            {
                input_.resize(layout.samples);
                output_.resize(layout.samples);
            }
            catch (const std::bad_alloc &)
            {
                __android_log_print(ANDROID_LOG_WARN, kLogTag, "audio scratch allocation failed");
                return false;
            }

            return echidna::audio::ProcessPcmBufferInPlace(
                       buffer,
                       byte_count,
                       format,
                       static_cast<uint32_t>(sample_rate),
                       static_cast<uint32_t>(channel_count),
                       input_.data(),
                       output_.data(),
                       input_.size(),
                       &echidna::jni::ProcessRuntimeBlock) ==
                   echidna::audio::BufferProcessResult::kProcessed;
        }

    private:
        std::mutex mutex_;
        std::vector<float> input_;
        std::vector<float> output_;
    };

    AudioScratch gAudioScratch;

    bool ProcessEncodedBytes(void *buffer,
                             size_t byte_count,
                             jint encoding,
                             jint sample_rate,
                             jint channel_count)
    {
        echidna::audio::PcmFormat format;
        if (!echidna::audio::PcmFormatFromAndroidEncoding(encoding, &format))
        {
            __android_log_print(ANDROID_LOG_WARN,
                                kLogTag,
                                "unsupported PCM encoding %d",
                                encoding);
            return false;
        }
        return gAudioScratch.process(buffer, byte_count, format, sample_rate, channel_count);
    }
} // namespace

extern "C" JNIEXPORT jboolean JNICALL
Java_com_echidna_lsposed_core_NativeBridge_nativeInitialise(JNIEnv *, jclass)
{
    return echidna::jni::InitialiseRuntime() ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_echidna_lsposed_core_NativeBridge_nativeIsEngineReady(JNIEnv *, jclass)
{
    return echidna::jni::IsRuntimeReady() ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT void JNICALL
Java_com_echidna_lsposed_core_NativeBridge_nativeSetBypass(JNIEnv *, jclass, jboolean bypass)
{
    echidna::jni::SetRuntimeBypass(bypass == JNI_TRUE);
}

extern "C" JNIEXPORT void JNICALL
Java_com_echidna_lsposed_core_NativeBridge_nativeSetProfile(JNIEnv *env,
                                                            jclass,
                                                            jstring profile)
{
    if (!env || !profile)
    {
        return;
    }
    const char *chars = env->GetStringUTFChars(profile, nullptr);
    if (!chars)
    {
        return;
    }
    const size_t length = static_cast<size_t>(env->GetStringUTFLength(profile));
    (void)echidna::jni::SetRuntimeProfile(chars, length);
    env->ReleaseStringUTFChars(profile, chars);
}

extern "C" JNIEXPORT jint JNICALL
Java_com_echidna_lsposed_core_NativeBridge_nativeGetStatus(JNIEnv *, jclass)
{
    return static_cast<jint>(echidna::jni::RuntimeStatus());
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_echidna_lsposed_core_NativeBridge_nativeProcessByteArray(JNIEnv *env,
                                                                  jclass,
                                                                  jbyteArray array,
                                                                  jint offset,
                                                                  jint length,
                                                                  jint encoding,
                                                                  jint sample_rate,
                                                                  jint channel_count)
{
    size_t byte_count = 0;
    if (!ValidateArrayRequest(env,
                              array,
                              offset,
                              length,
                              1,
                              sample_rate,
                              channel_count,
                              &byte_count))
    {
        return JNI_FALSE;
    }
    try
    {
        std::vector<jbyte> copy(static_cast<size_t>(length));
        env->GetByteArrayRegion(array, offset, length, copy.data());
        if (env->ExceptionCheck() ||
            !ProcessEncodedBytes(copy.data(),
                                 byte_count,
                                 encoding,
                                 sample_rate,
                                 channel_count))
        {
            return JNI_FALSE;
        }
        env->SetByteArrayRegion(array, offset, length, copy.data());
        return env->ExceptionCheck() ? JNI_FALSE : JNI_TRUE;
    }
    catch (...)
    {
        return JNI_FALSE;
    }
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_echidna_lsposed_core_NativeBridge_nativeProcessShortArray(JNIEnv *env,
                                                                   jclass,
                                                                   jshortArray array,
                                                                   jint offset,
                                                                   jint length,
                                                                   jint sample_rate,
                                                                   jint channel_count)
{
    size_t byte_count = 0;
    if (!ValidateArrayRequest(env,
                              array,
                              offset,
                              length,
                              sizeof(jshort),
                              sample_rate,
                              channel_count,
                              &byte_count))
    {
        return JNI_FALSE;
    }
    try
    {
        std::vector<jshort> copy(static_cast<size_t>(length));
        env->GetShortArrayRegion(array, offset, length, copy.data());
        if (env->ExceptionCheck() ||
            !gAudioScratch.process(copy.data(),
                                   byte_count,
                                   echidna::audio::PcmFormat::kSigned16,
                                   sample_rate,
                                   channel_count))
        {
            return JNI_FALSE;
        }
        env->SetShortArrayRegion(array, offset, length, copy.data());
        return env->ExceptionCheck() ? JNI_FALSE : JNI_TRUE;
    }
    catch (...)
    {
        return JNI_FALSE;
    }
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_echidna_lsposed_core_NativeBridge_nativeProcessFloatArray(JNIEnv *env,
                                                                   jclass,
                                                                   jfloatArray array,
                                                                   jint offset,
                                                                   jint length,
                                                                   jint sample_rate,
                                                                   jint channel_count)
{
    size_t byte_count = 0;
    if (!ValidateArrayRequest(env,
                              array,
                              offset,
                              length,
                              sizeof(jfloat),
                              sample_rate,
                              channel_count,
                              &byte_count))
    {
        return JNI_FALSE;
    }
    try
    {
        std::vector<jfloat> copy(static_cast<size_t>(length));
        env->GetFloatArrayRegion(array, offset, length, copy.data());
        if (env->ExceptionCheck() ||
            !gAudioScratch.process(copy.data(),
                                   byte_count,
                                   echidna::audio::PcmFormat::kFloat32,
                                   sample_rate,
                                   channel_count))
        {
            return JNI_FALSE;
        }
        env->SetFloatArrayRegion(array, offset, length, copy.data());
        return env->ExceptionCheck() ? JNI_FALSE : JNI_TRUE;
    }
    catch (...)
    {
        return JNI_FALSE;
    }
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_echidna_lsposed_core_NativeBridge_nativeProcessByteBuffer(JNIEnv *env,
                                                                   jclass,
                                                                   jobject buffer,
                                                                   jint position,
                                                                   jint length,
                                                                   jint encoding,
                                                                   jint sample_rate,
                                                                   jint channel_count)
{
    if (!ValidateDirectBufferRequest(env,
                                     buffer,
                                     position,
                                     length,
                                     sample_rate,
                                     channel_count))
    {
        return JNI_FALSE;
    }
    auto *base = static_cast<uint8_t *>(env->GetDirectBufferAddress(buffer));
    if (!base)
    {
        return JNI_FALSE;
    }
    return ProcessEncodedBytes(base + position,
                               static_cast<size_t>(length),
                               encoding,
                               sample_rate,
                               channel_count)
               ? JNI_TRUE
               : JNI_FALSE;
}
