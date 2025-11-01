#include <jni.h>

#include <cstdint>
#include <mutex>
#include <vector>

#ifdef __ANDROID__
#include <android/log.h>
#include <sys/mman.h>
#include <unistd.h>
#if __ANDROID_API__ >= 26
#include <android/sharedmem.h>
#endif
#else
#define __android_log_print(...) ((void)0)
#define ANDROID_LOG_WARN 5
#endif

#include "echidna/api.h"
#include "state/shared_state.h"

namespace {

constexpr const char *kLogTag = "EchidnaAudioBridge";

constexpr int kEncodingPcmDefault = 1;
constexpr int kEncodingPcm16Bit = 2;
constexpr int kEncodingPcm8Bit = 3;
constexpr int kEncodingPcmFloat = 4;
constexpr int kEncodingPcm24BitPacked = 20;
constexpr int kEncodingPcm32Bit = 21;
constexpr int kEncodingPcm24Bit = 22;

constexpr float kInt8Scale = 1.0f / 128.0f;
constexpr float kInt16Scale = 1.0f / 32768.0f;
constexpr float kInt24Scale = 1.0f / 8388608.0f;
constexpr float kInt32Scale = 1.0f / 2147483648.0f;

class FloatSharedBuffer {
  public:
    template <typename Converter>
    bool withWritable(size_t samples,
                      Converter &&converter,
                      size_t frames,
                      jint sample_rate,
                      jint channel_count) {
        std::scoped_lock lock(mutex_);
        if (!ensure(samples)) {
            return false;
        }
        converter(data_, samples);
        const auto status = echidna_process_block(
                data_, nullptr, static_cast<uint32_t>(frames),
                static_cast<uint32_t>(sample_rate),
                static_cast<uint32_t>(channel_count));
        return status != ECHIDNA_STATUS_ERROR;
    }

    bool copyAndForward(const float *input,
                        size_t frames,
                        jint sample_rate,
                        jint channel_count) {
        if (!input || frames == 0 || channel_count <= 0) {
            return false;
        }
        const auto status = echidna_process_block(
                input, nullptr, static_cast<uint32_t>(frames),
                static_cast<uint32_t>(sample_rate),
                static_cast<uint32_t>(channel_count));
        return status != ECHIDNA_STATUS_ERROR;
    }

  private:
    bool ensure(size_t samples) {
        if (samples == 0) {
            return false;
        }
        if (samples <= capacity_samples_) {
            return true;
        }
        release();
#if defined(__ANDROID__) && __ANDROID_API__ >= 26
        const size_t bytes = samples * sizeof(float);
        fd_ = ASharedMemory_create("echidna_audio_bridge", bytes);
        if (fd_ >= 0) {
            void *mapping = mmap(nullptr, bytes, PROT_READ | PROT_WRITE, MAP_SHARED, fd_, 0);
            if (mapping != MAP_FAILED) {
                data_ = static_cast<float *>(mapping);
                map_size_ = bytes;
                capacity_samples_ = samples;
                return true;
            }
            __android_log_print(ANDROID_LOG_WARN, kLogTag, "mmap failed, falling back to heap");
            close(fd_);
            fd_ = -1;
        }
#endif
        storage_.resize(samples);
        data_ = storage_.data();
        capacity_samples_ = storage_.size();
        return capacity_samples_ >= samples;
    }

    void release() {
#if defined(__ANDROID__) && __ANDROID_API__ >= 26
        if (data_ && map_size_ > 0 && fd_ >= 0) {
            munmap(data_, map_size_);
        }
        if (fd_ >= 0) {
            close(fd_);
        }
        fd_ = -1;
        map_size_ = 0;
#endif
        storage_.clear();
        data_ = nullptr;
        capacity_samples_ = 0;
    }

    std::mutex mutex_;
    float *data_ = nullptr;
    size_t capacity_samples_ = 0;
#if defined(__ANDROID__) && __ANDROID_API__ >= 26
    int fd_ = -1;
    size_t map_size_ = 0;
#endif
    std::vector<float> storage_;
};

FloatSharedBuffer gFloatScratch;

size_t FramesFromSamples(size_t samples, jint channel_count) {
    if (channel_count <= 0) {
        return 0;
    }
    return samples / static_cast<size_t>(channel_count);
}

bool ProcessFloatSamples(const float *data,
                         size_t frames,
                         jint sample_rate,
                         jint channel_count) {
    return gFloatScratch.copyAndForward(data, frames, sample_rate, channel_count);
}

bool ProcessPcm16(const int16_t *data,
                  size_t samples,
                  jint sample_rate,
                  jint channel_count) {
    const size_t frames = FramesFromSamples(samples, channel_count);
    if (frames == 0) {
        return false;
    }
    return gFloatScratch.withWritable(
            samples,
            [data](float *dest, size_t count) {
                for (size_t i = 0; i < count; ++i) {
                    dest[i] = static_cast<float>(data[i]) * kInt16Scale;
                }
            },
            frames,
            sample_rate,
            channel_count);
}

bool ProcessPcm8(const uint8_t *data,
                 size_t samples,
                 jint sample_rate,
                 jint channel_count) {
    const size_t frames = FramesFromSamples(samples, channel_count);
    if (frames == 0) {
        return false;
    }
    return gFloatScratch.withWritable(
            samples,
            [data](float *dest, size_t count) {
                for (size_t i = 0; i < count; ++i) {
                    dest[i] = static_cast<float>(static_cast<int8_t>(data[i])) * kInt8Scale;
                }
            },
            frames,
            sample_rate,
            channel_count);
}

bool ProcessPcm24Packed(const uint8_t *data,
                        size_t bytes,
                        jint sample_rate,
                        jint channel_count) {
    const size_t samples = bytes / 3;
    const size_t frames = FramesFromSamples(samples, channel_count);
    if (frames == 0) {
        return false;
    }
    return gFloatScratch.withWritable(
            samples,
            [data](float *dest, size_t count) {
                for (size_t i = 0; i < count; ++i) {
                    const size_t index = i * 3;
                    const int32_t value = (static_cast<int32_t>(static_cast<int8_t>(data[index + 2])) << 16) |
                                          (static_cast<int32_t>(static_cast<uint8_t>(data[index + 1])) << 8) |
                                          static_cast<int32_t>(static_cast<uint8_t>(data[index]));
                    dest[i] = static_cast<float>(value) * kInt24Scale;
                }
            },
            frames,
            sample_rate,
            channel_count);
}

bool ProcessPcm32(const int32_t *data,
                  size_t samples,
                  jint sample_rate,
                  jint channel_count) {
    const size_t frames = FramesFromSamples(samples, channel_count);
    if (frames == 0) {
        return false;
    }
    return gFloatScratch.withWritable(
            samples,
            [data](float *dest, size_t count) {
                for (size_t i = 0; i < count; ++i) {
                    dest[i] = static_cast<float>(data[i]) * kInt32Scale;
                }
            },
            frames,
            sample_rate,
            channel_count);
}

bool ProcessByteSpan(const uint8_t *data,
                     size_t length,
                     jint encoding,
                     jint sample_rate,
                     jint channel_count) {
    switch (encoding) {
        case kEncodingPcmDefault:
        case kEncodingPcm16Bit:
            return ProcessPcm16(reinterpret_cast<const int16_t *>(data),
                                length / sizeof(int16_t),
                                sample_rate,
                                channel_count);
        case kEncodingPcm8Bit:
            return ProcessPcm8(data, length, sample_rate, channel_count);
        case kEncodingPcmFloat: {
            const size_t samples = length / sizeof(float);
            const size_t frames = FramesFromSamples(samples, channel_count);
            return ProcessFloatSamples(reinterpret_cast<const float *>(data),
                                       frames,
                                       sample_rate,
                                       channel_count);
        }
        case kEncodingPcm24BitPacked:
            return ProcessPcm24Packed(data, length, sample_rate, channel_count);
        case kEncodingPcm32Bit:
        case kEncodingPcm24Bit:
            return ProcessPcm32(reinterpret_cast<const int32_t *>(data),
                                length / sizeof(int32_t),
                                sample_rate,
                                channel_count);
        default:
            __android_log_print(ANDROID_LOG_WARN, kLogTag, "Unsupported encoding %d", encoding);
            return false;
    }
}

}  // namespace

extern "C" JNIEXPORT jboolean JNICALL
Java_com_echidna_lsposed_core_NativeBridge_nativeInitialise(JNIEnv *, jclass) {
    auto &state = echidna::state::SharedState::instance();
    state.refreshFromSharedMemory();
    state.setStatus(echidna::state::InternalStatus::kWaitingForAttach);
    return JNI_TRUE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_echidna_lsposed_core_NativeBridge_nativeIsEngineReady(JNIEnv *, jclass) {
    return echidna::state::SharedState::instance().hooksEnabled() ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT void JNICALL
Java_com_echidna_lsposed_core_NativeBridge_nativeSetBypass(JNIEnv *, jclass, jboolean bypass) {
    auto &state = echidna::state::SharedState::instance();
    state.setStatus(bypass ? echidna::state::InternalStatus::kDisabled
                           : echidna::state::InternalStatus::kWaitingForAttach);
}

extern "C" JNIEXPORT void JNICALL
Java_com_echidna_lsposed_core_NativeBridge_nativeSetProfile(JNIEnv *env, jclass, jstring profile) {
    if (!profile) {
        return;
    }
    const char *chars = env->GetStringUTFChars(profile, nullptr);
    if (!chars) {
        return;
    }
    echidna_set_profile(chars);
    env->ReleaseStringUTFChars(profile, chars);
}

extern "C" JNIEXPORT jint JNICALL
Java_com_echidna_lsposed_core_NativeBridge_nativeGetStatus(JNIEnv *, jclass) {
    return static_cast<jint>(echidna_get_status());
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_echidna_lsposed_core_NativeBridge_nativeProcessByteArray(JNIEnv *env,
                                                                  jclass,
                                                                  jbyteArray array,
                                                                  jint offset,
                                                                  jint length,
                                                                  jint encoding,
                                                                  jint sample_rate,
                                                                  jint channel_count) {
    if (!array || length <= 0) {
        return JNI_FALSE;
    }
    jbyte *raw = static_cast<jbyte *>(env->GetPrimitiveArrayCritical(array, nullptr));
    if (raw) {
        const bool ok = ProcessByteSpan(
                reinterpret_cast<const uint8_t *>(raw + offset),
                static_cast<size_t>(length),
                encoding,
                sample_rate,
                channel_count);
        env->ReleasePrimitiveArrayCritical(array, raw, JNI_ABORT);
        return ok ? JNI_TRUE : JNI_FALSE;
    }
    std::vector<uint8_t> buffer(static_cast<size_t>(length));
    env->GetByteArrayRegion(array, offset, length,
                            reinterpret_cast<jbyte *>(buffer.data()));
    const bool ok = ProcessByteSpan(buffer.data(), buffer.size(), encoding, sample_rate, channel_count);
    return ok ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_echidna_lsposed_core_NativeBridge_nativeProcessShortArray(JNIEnv *env,
                                                                   jclass,
                                                                   jshortArray array,
                                                                   jint offset,
                                                                   jint length,
                                                                   jint sample_rate,
                                                                   jint channel_count) {
    if (!array || length <= 0) {
        return JNI_FALSE;
    }
    jshort *raw = static_cast<jshort *>(env->GetPrimitiveArrayCritical(array, nullptr));
    if (raw) {
        const bool ok = ProcessPcm16(reinterpret_cast<const int16_t *>(raw + offset),
                                     static_cast<size_t>(length),
                                     sample_rate,
                                     channel_count);
        env->ReleasePrimitiveArrayCritical(array, raw, JNI_ABORT);
        return ok ? JNI_TRUE : JNI_FALSE;
    }
    std::vector<jshort> buffer(static_cast<size_t>(length));
    env->GetShortArrayRegion(array, offset, length, buffer.data());
    const bool ok = ProcessPcm16(reinterpret_cast<const int16_t *>(buffer.data()),
                                 buffer.size(),
                                 sample_rate,
                                 channel_count);
    return ok ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_echidna_lsposed_core_NativeBridge_nativeProcessFloatArray(JNIEnv *env,
                                                                   jclass,
                                                                   jfloatArray array,
                                                                   jint offset,
                                                                   jint length,
                                                                   jint sample_rate,
                                                                   jint channel_count) {
    if (!array || length <= 0) {
        return JNI_FALSE;
    }
    jfloat *raw = static_cast<jfloat *>(env->GetPrimitiveArrayCritical(array, nullptr));
    const size_t frames = FramesFromSamples(static_cast<size_t>(length), channel_count);
    if (raw) {
        const bool ok = ProcessFloatSamples(raw + offset,
                                            frames,
                                            sample_rate,
                                            channel_count);
        env->ReleasePrimitiveArrayCritical(array, raw, JNI_ABORT);
        return ok ? JNI_TRUE : JNI_FALSE;
    }
    std::vector<jfloat> buffer(static_cast<size_t>(length));
    env->GetFloatArrayRegion(array, offset, length, buffer.data());
    const bool ok = ProcessFloatSamples(buffer.data(), frames, sample_rate, channel_count);
    return ok ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_echidna_lsposed_core_NativeBridge_nativeProcessByteBuffer(JNIEnv *env,
                                                                   jclass,
                                                                   jobject buffer,
                                                                   jint position,
                                                                   jint length,
                                                                   jint encoding,
                                                                   jint sample_rate,
                                                                   jint channel_count) {
    if (!buffer || length <= 0) {
        return JNI_FALSE;
    }
    void *base = env->GetDirectBufferAddress(buffer);
    if (!base) {
        return JNI_FALSE;
    }
    auto *start = static_cast<uint8_t *>(base) + position;
    const bool ok = ProcessByteSpan(start,
                                    static_cast<size_t>(length),
                                    encoding,
                                    sample_rate,
                                    channel_count);
    return ok ? JNI_TRUE : JNI_FALSE;
}

