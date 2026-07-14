#include <jni.h>

#include <android/log.h>
#include <dlfcn.h>

#include <cstddef>
#include <array>
#include <cstdlib>
#include <limits>
#include <mutex>
#include <string>
#include <string_view>
#include <sys/stat.h>

#include <echidna_api.h>

namespace
{
    constexpr std::array<std::string_view, 3> kCandidateLibraries = {
        "libechidna.so", "libechidna_jni.so", "libechidna.dylib"};
    constexpr std::array<std::string_view, 2> kCandidateLibraryDirectories = {
        "/data/adb/echidna/lib", "/data/adb/modules/echidna/lib"};
    constexpr const char *kLogTag = "EchidnaControlJNI";

    struct EchidnaSymbols
    {
        using SetProfileFn = echidna_result_t (*)(const char *, size_t);
        using ProcessBlockFn = echidna_result_t (*)(const float *, float *, uint32_t, uint32_t, uint32_t);
        using GetStatusFn = echidna_status_t (*)(void);
        using GetVersionFn = uint32_t (*)(void);

        void *handle{nullptr};
        SetProfileFn set_profile{nullptr};
        ProcessBlockFn process_block{nullptr};
        GetStatusFn get_status{nullptr};
        GetVersionFn get_version{nullptr};
        echidna_result_t last_error{ECHIDNA_RESULT_OK};
    };

    EchidnaSymbols &Symbols()
    {
        static EchidnaSymbols symbols;
        return symbols;
    }

    bool TryLoadSymbolsFrom(const char *library_path)
    {
        auto &symbols = Symbols();
        void *handle = dlopen(library_path, RTLD_NOW | RTLD_LOCAL);
        if (!handle)
        {
            return false;
        }

        auto set_profile =
            reinterpret_cast<EchidnaSymbols::SetProfileFn>(dlsym(handle, "echidna_set_profile"));
        auto process_block = reinterpret_cast<EchidnaSymbols::ProcessBlockFn>(
            dlsym(handle, "echidna_process_block"));
        auto get_status =
            reinterpret_cast<EchidnaSymbols::GetStatusFn>(dlsym(handle, "echidna_get_status"));
        auto get_version = reinterpret_cast<EchidnaSymbols::GetVersionFn>(
            dlsym(handle, "echidna_api_get_version"));
        if (!set_profile || !process_block || !get_status || !get_version)
        {
            dlclose(handle);
            return false;
        }

        symbols.handle = handle;
        symbols.set_profile = set_profile;
        symbols.process_block = process_block;
        symbols.get_status = get_status;
        symbols.get_version = get_version;
        symbols.last_error = ECHIDNA_RESULT_OK;
        return true;
    }

    bool LoadSymbols()
    {
        auto &symbols = Symbols();
        if (symbols.handle && symbols.set_profile && symbols.process_block && symbols.get_status &&
            symbols.get_version)
        {
            return true;
        }

        if (const char *env_path = std::getenv("ECHIDNA_LIBRARY_PATH"))
        {
            if (TryLoadSymbolsFrom(env_path))
            {
                return true;
            }

            std::string base_path(env_path);
            if (!base_path.empty() && base_path.back() == '/')
            {
                base_path.pop_back();
            }
            struct stat path_info
            {
            };
            if (!base_path.empty() && stat(base_path.c_str(), &path_info) == 0 &&
                S_ISDIR(path_info.st_mode))
            {
                for (const auto &candidate : kCandidateLibraries)
                {
                    std::string candidate_path = base_path;
                    candidate_path.append("/");
                    candidate_path.append(candidate);
                    if (TryLoadSymbolsFrom(candidate_path.c_str()))
                    {
                        return true;
                    }
                }
            }
        }

        for (const auto &directory : kCandidateLibraryDirectories)
        {
            std::string base_path(directory);
            if (!base_path.empty() && base_path.back() == '/')
            {
                base_path.pop_back();
            }
            for (const auto &candidate : kCandidateLibraries)
            {
                std::string candidate_path = base_path;
                candidate_path.append("/");
                candidate_path.append(candidate);
                if (TryLoadSymbolsFrom(candidate_path.c_str()))
                {
                    return true;
                }
            }
        }

        for (const auto &candidate : kCandidateLibraries)
        {
            if (TryLoadSymbolsFrom(candidate.data()))
            {
                return true;
            }
        }

        symbols.last_error = ECHIDNA_RESULT_NOT_AVAILABLE;
        __android_log_print(ANDROID_LOG_WARN, kLogTag, "Failed to locate Echidna API library");
        return false;
    }

    bool EnsureLoaded()
    {
        auto &symbols = Symbols();
        if (symbols.handle)
        {
            return true;
        }

        static std::mutex load_mutex;
        const std::lock_guard<std::mutex> lock(load_mutex);
        if (symbols.handle)
        {
            return true;
        }

        return LoadSymbols();
    }

    bool ComputeRequiredSamples(jint frames, jint channel_count, size_t *out)
    {
        if (frames <= 0 || channel_count <= 0 || out == nullptr)
        {
            return false;
        }
        const size_t frame_count = static_cast<size_t>(frames);
        const size_t channels = static_cast<size_t>(channel_count);
        if (frame_count > std::numeric_limits<size_t>::max() / channels)
        {
            return false;
        }
        *out = frame_count * channels;
        return true;
    }

    bool HasFloatArrayCapacity(JNIEnv *env, jfloatArray array, size_t required_samples)
    {
        return env != nullptr && array != nullptr &&
               static_cast<size_t>(env->GetArrayLength(array)) >= required_samples;
    }

} // namespace

extern "C" JNIEXPORT jint JNICALL
Java_com_echidna_control_bridge_EchidnaNative_nativeSetProfile(JNIEnv *env,
                                                               jclass,
                                                               jstring profile)
{
    if (!EnsureLoaded() || !profile)
    {
        return Symbols().last_error;
    }
    const char *chars = env->GetStringUTFChars(profile, nullptr);
    if (!chars)
    {
        return ECHIDNA_RESULT_ERROR;
    }
    const size_t length = static_cast<size_t>(env->GetStringUTFLength(profile));
    echidna_result_t result = ECHIDNA_RESULT_ERROR;
    try
    {
        result = Symbols().set_profile(chars, length);
    }
    catch (...)
    {
        result = ECHIDNA_RESULT_ERROR;
    }
    env->ReleaseStringUTFChars(profile, chars);
    return static_cast<jint>(result);
}

extern "C" JNIEXPORT jint JNICALL
Java_com_echidna_control_bridge_EchidnaNative_nativeProcessBlock(JNIEnv *env,
                                                                 jclass,
                                                                 jfloatArray input,
                                                                 jfloatArray output,
                                                                 jint frames,
                                                                 jint sample_rate,
                                                                 jint channel_count)
{
    if (!EnsureLoaded() || !input)
    {
        return Symbols().last_error;
    }
    size_t required_samples = 0;
    if (sample_rate <= 0 || !ComputeRequiredSamples(frames, channel_count, &required_samples) ||
        !HasFloatArrayCapacity(env, input, required_samples))
    {
        return ECHIDNA_RESULT_INVALID_ARGUMENT;
    }
    if (output && !HasFloatArrayCapacity(env, output, required_samples))
    {
        return ECHIDNA_RESULT_INVALID_ARGUMENT;
    }
    jboolean input_is_copy = JNI_FALSE;
    float *input_ptr = env->GetFloatArrayElements(input, &input_is_copy);
    if (!input_ptr)
    {
        return ECHIDNA_RESULT_ERROR;
    }
    float *output_ptr = nullptr;
    jboolean output_is_copy = JNI_FALSE;
    if (output)
    {
        output_ptr = env->GetFloatArrayElements(output, &output_is_copy);
        if (!output_ptr)
        {
            env->ReleaseFloatArrayElements(input, input_ptr, JNI_ABORT);
            return ECHIDNA_RESULT_ERROR;
        }
    }
    echidna_result_t result = ECHIDNA_RESULT_ERROR;
    try
    {
        result = Symbols().process_block(input_ptr,
                                         output_ptr,
                                         static_cast<uint32_t>(frames),
                                         static_cast<uint32_t>(sample_rate),
                                         static_cast<uint32_t>(channel_count));
    }
    catch (...)
    {
        result = ECHIDNA_RESULT_ERROR;
    }
    if (output && output_ptr)
    {
        env->ReleaseFloatArrayElements(output, output_ptr, 0);
    }
    env->ReleaseFloatArrayElements(input, input_ptr, JNI_ABORT);
    return static_cast<jint>(result);
}

extern "C" JNIEXPORT jint JNICALL
Java_com_echidna_control_bridge_EchidnaNative_nativeGetStatus(JNIEnv *, jclass)
{
    if (!EnsureLoaded())
    {
        return static_cast<jint>(ECHIDNA_STATUS_ERROR);
    }
    try
    {
        return static_cast<jint>(Symbols().get_status());
    }
    catch (...)
    {
        return static_cast<jint>(ECHIDNA_STATUS_ERROR);
    }
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_echidna_control_bridge_EchidnaNative_nativeGetApiVersion(JNIEnv *, jclass)
{
    if (!EnsureLoaded())
    {
        return static_cast<jlong>(ECHIDNA_API_VERSION);
    }
    try
    {
        return static_cast<jlong>(Symbols().get_version());
    }
    catch (...)
    {
        return static_cast<jlong>(ECHIDNA_API_VERSION);
    }
}
