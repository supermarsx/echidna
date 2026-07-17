// Echidna Lab in-process DSP bridge.
//
// This JNI bridge exposes the SELF-CONTAINED DSP engine (libech_dsp.so) to the
// app's "Lab" testbench. It is NOT the Zygisk interception path: it creates an
// independent `ech_dsp_engine_t` inside the app's own process and runs the app's
// OWN audio (recorded mic / generated test tone) through it. There is no root,
// no hook, and no other app's audio involved. It only proves the DSP transform
// itself works — nothing about interception.
//
// libech_dsp.so is packaged into this APK's jniLibs (see app/build.gradle.kts).
// We resolve the engine C ABI with dlopen/dlsym so the bridge still loads (and
// honestly reports "engine unavailable") on a "lite" build produced without the
// native artifacts, mirroring echidna_control_jni.cpp.

#include <jni.h>

#include <android/log.h>
#include <dlfcn.h>

#include <cstdint>
#include <mutex>

#include <echidna/dsp/api.h>

namespace
{
    constexpr const char *kLogTag = "EchidnaLabDsp";
    constexpr const char *kDspLibrary = "libech_dsp.so";

    struct DspSymbols
    {
        using CreateFn = ech_dsp_status_t (*)(uint32_t, uint32_t, ech_dsp_quality_mode_t,
                                              size_t, const char *, size_t, ech_dsp_engine_t **);
        using ProcessFn = ech_dsp_status_t (*)(ech_dsp_engine_t *, const float *, float *, size_t);
        using DestroyFn = void (*)(ech_dsp_engine_t *);
        using VersionFn = uint32_t (*)(void);

        void *handle{nullptr};
        CreateFn create{nullptr};
        ProcessFn process{nullptr};
        DestroyFn destroy{nullptr};
        VersionFn version{nullptr};
    };

    DspSymbols &Symbols()
    {
        static DspSymbols symbols;
        return symbols;
    }

    // Resolve the engine C ABI once. libech_dsp.so is packaged alongside this
    // bridge, so a bare soname dlopen resolves from the app's native lib dir.
    bool EnsureLoaded()
    {
        auto &symbols = Symbols();
        if (symbols.handle && symbols.create && symbols.process && symbols.destroy && symbols.version)
        {
            return true;
        }

        static std::mutex load_mutex;
        const std::lock_guard<std::mutex> lock(load_mutex);
        if (symbols.handle)
        {
            return symbols.create && symbols.process && symbols.destroy && symbols.version;
        }

        void *handle = dlopen(kDspLibrary, RTLD_NOW | RTLD_LOCAL);
        if (!handle)
        {
            __android_log_print(ANDROID_LOG_WARN, kLogTag,
                                "libech_dsp.so not present (lite build): %s", dlerror());
            return false;
        }

        auto create = reinterpret_cast<DspSymbols::CreateFn>(dlsym(handle, "ech_dsp_engine_create"));
        auto process = reinterpret_cast<DspSymbols::ProcessFn>(dlsym(handle, "ech_dsp_engine_process"));
        auto destroy = reinterpret_cast<DspSymbols::DestroyFn>(dlsym(handle, "ech_dsp_engine_destroy"));
        auto version = reinterpret_cast<DspSymbols::VersionFn>(dlsym(handle, "ech_dsp_api_get_version"));
        if (!create || !process || !destroy || !version)
        {
            __android_log_print(ANDROID_LOG_ERROR, kLogTag,
                                "libech_dsp.so missing engine symbols");
            dlclose(handle);
            return false;
        }

        symbols.handle = handle;
        symbols.create = create;
        symbols.process = process;
        symbols.destroy = destroy;
        symbols.version = version;
        return true;
    }

    ech_dsp_engine_t *AsEngine(jlong handle)
    {
        return reinterpret_cast<ech_dsp_engine_t *>(static_cast<uintptr_t>(handle));
    }
} // namespace

extern "C" JNIEXPORT jboolean JNICALL
Java_com_echidna_app_audio_EchidnaLabDsp_nativeAvailable(JNIEnv *, jclass)
{
    return EnsureLoaded() ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_echidna_app_audio_EchidnaLabDsp_nativeApiVersion(JNIEnv *, jclass)
{
    if (!EnsureLoaded())
    {
        return 0;
    }
    return static_cast<jlong>(Symbols().version());
}

// Builds one independent engine. Returns an opaque handle (>0) or 0 on failure.
// This is a lifecycle op (may allocate) — never called from the audio callback.
extern "C" JNIEXPORT jlong JNICALL
Java_com_echidna_app_audio_EchidnaLabDsp_nativeCreate(JNIEnv *env,
                                                      jclass,
                                                      jint sampleRate,
                                                      jint channels,
                                                      jint qualityMode,
                                                      jint maxFrames,
                                                      jstring presetJson)
{
    if (!EnsureLoaded() || sampleRate <= 0 || channels <= 0 || maxFrames <= 0)
    {
        return 0;
    }
    if (qualityMode < ECH_DSP_QUALITY_LOW_LATENCY || qualityMode > ECH_DSP_QUALITY_HIGH)
    {
        qualityMode = ECH_DSP_QUALITY_BALANCED;
    }

    const char *config = nullptr;
    size_t config_length = 0;
    if (presetJson)
    {
        config = env->GetStringUTFChars(presetJson, nullptr);
        if (config)
        {
            config_length = static_cast<size_t>(env->GetStringUTFLength(presetJson));
        }
    }

    ech_dsp_engine_t *engine = nullptr;
    ech_dsp_status_t status = ECH_DSP_STATUS_ERROR;
    try
    {
        status = Symbols().create(static_cast<uint32_t>(sampleRate),
                                  static_cast<uint32_t>(channels),
                                  static_cast<ech_dsp_quality_mode_t>(qualityMode),
                                  static_cast<size_t>(maxFrames),
                                  config,
                                  config_length,
                                  &engine);
    }
    catch (...)
    {
        status = ECH_DSP_STATUS_ERROR;
    }

    if (presetJson && config)
    {
        env->ReleaseStringUTFChars(presetJson, config);
    }

    if (status != ECH_DSP_STATUS_OK || engine == nullptr)
    {
        __android_log_print(ANDROID_LOG_WARN, kLogTag, "engine_create failed: %d", status);
        if (engine)
        {
            Symbols().destroy(engine);
        }
        return 0;
    }
    return static_cast<jlong>(reinterpret_cast<uintptr_t>(engine));
}

// Offline processing (record->process, test tone). Uses a critical section over
// the primitive arrays so no copy is forced; input/output must not be the same
// array (the engine forbids overlap). Returns ech_dsp_status_t.
extern "C" JNIEXPORT jint JNICALL
Java_com_echidna_app_audio_EchidnaLabDsp_nativeProcess(JNIEnv *env,
                                                       jclass,
                                                       jlong handle,
                                                       jfloatArray input,
                                                       jfloatArray output,
                                                       jint frames)
{
    ech_dsp_engine_t *engine = AsEngine(handle);
    if (!engine || !input || !output || frames <= 0)
    {
        return ECH_DSP_STATUS_INVALID_ARGUMENT;
    }
    if (env->GetArrayLength(input) < frames || env->GetArrayLength(output) < frames)
    {
        return ECH_DSP_STATUS_INVALID_ARGUMENT;
    }

    auto *in_ptr = static_cast<float *>(env->GetPrimitiveArrayCritical(input, nullptr));
    if (!in_ptr)
    {
        return ECH_DSP_STATUS_ERROR;
    }
    auto *out_ptr = static_cast<float *>(env->GetPrimitiveArrayCritical(output, nullptr));
    if (!out_ptr)
    {
        env->ReleasePrimitiveArrayCritical(input, in_ptr, JNI_ABORT);
        return ECH_DSP_STATUS_ERROR;
    }

    ech_dsp_status_t status = ECH_DSP_STATUS_ERROR;
    try
    {
        status = Symbols().process(engine, in_ptr, out_ptr, static_cast<size_t>(frames));
    }
    catch (...)
    {
        status = ECH_DSP_STATUS_ERROR;
    }

    env->ReleasePrimitiveArrayCritical(output, out_ptr, 0);
    env->ReleasePrimitiveArrayCritical(input, in_ptr, JNI_ABORT);
    return static_cast<jint>(status);
}

// Realtime processing path. Operates on DIRECT float buffers so there is no
// array pinning, copy, or allocation on the audio callback thread — the caller
// preallocates the direct buffers once. `frames` is per-channel frames.
extern "C" JNIEXPORT jint JNICALL
Java_com_echidna_app_audio_EchidnaLabDsp_nativeProcessDirect(JNIEnv *env,
                                                             jclass,
                                                             jlong handle,
                                                             jobject inputBuffer,
                                                             jobject outputBuffer,
                                                             jint frames)
{
    ech_dsp_engine_t *engine = AsEngine(handle);
    if (!engine || !inputBuffer || !outputBuffer || frames <= 0)
    {
        return ECH_DSP_STATUS_INVALID_ARGUMENT;
    }
    auto *in_ptr = static_cast<float *>(env->GetDirectBufferAddress(inputBuffer));
    auto *out_ptr = static_cast<float *>(env->GetDirectBufferAddress(outputBuffer));
    if (!in_ptr || !out_ptr)
    {
        return ECH_DSP_STATUS_INVALID_ARGUMENT;
    }

    ech_dsp_status_t status = ECH_DSP_STATUS_ERROR;
    try
    {
        status = Symbols().process(engine, in_ptr, out_ptr, static_cast<size_t>(frames));
    }
    catch (...)
    {
        status = ECH_DSP_STATUS_ERROR;
    }
    return static_cast<jint>(status);
}

extern "C" JNIEXPORT void JNICALL
Java_com_echidna_app_audio_EchidnaLabDsp_nativeDestroy(JNIEnv *, jclass, jlong handle)
{
    ech_dsp_engine_t *engine = AsEngine(handle);
    if (engine && Symbols().destroy)
    {
        Symbols().destroy(engine);
    }
}
