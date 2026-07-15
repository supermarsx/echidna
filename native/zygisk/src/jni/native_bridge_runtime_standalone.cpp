#include "jni/native_bridge_runtime.h"

#include <atomic>
#include <cstring>
#include <mutex>
#include <string>

#include "echidna/dsp/api.h"

namespace echidna::jni
{
    namespace
    {
        struct RuntimeState
        {
            std::mutex mutex;
            std::atomic<int> status{ECHIDNA_STATUS_DISABLED};
            std::atomic<bool> bypass{false};
            bool abi_compatible{false};
            bool dsp_initialised{false};
            uint32_t sample_rate{0};
            uint32_t channels{0};
            std::string pending_profile;

            ~RuntimeState()
            {
                if (dsp_initialised)
                {
                    ech_dsp_shutdown();
                }
            }
        };

        RuntimeState &GetRuntimeState()
        {
            static RuntimeState runtime;
            return runtime;
        }

        echidna_result_t ConvertStatus(ech_dsp_status_t status)
        {
            switch (status)
            {
            case ECH_DSP_STATUS_OK:
                return ECHIDNA_RESULT_OK;
            case ECH_DSP_STATUS_INVALID_ARGUMENT:
                return ECHIDNA_RESULT_INVALID_ARGUMENT;
            case ECH_DSP_STATUS_NOT_INITIALISED:
                return ECHIDNA_RESULT_NOT_INITIALISED;
            default:
                return ECHIDNA_RESULT_ERROR;
            }
        }

        bool EnsureDspInitialisedLocked(RuntimeState &runtime,
                                        uint32_t sample_rate,
                                        uint32_t channels)
        {
            if (!runtime.abi_compatible)
            {
                return false;
            }
            if (runtime.dsp_initialised && runtime.sample_rate == sample_rate &&
                runtime.channels == channels)
            {
                return true;
            }
            if (runtime.dsp_initialised)
            {
                ech_dsp_shutdown();
                runtime.dsp_initialised = false;
            }
            if (ech_dsp_initialize(sample_rate, channels, ECH_DSP_QUALITY_BALANCED) !=
                ECH_DSP_STATUS_OK)
            {
                return false;
            }
            runtime.dsp_initialised = true;
            runtime.sample_rate = sample_rate;
            runtime.channels = channels;
            if (!runtime.pending_profile.empty() &&
                ech_dsp_update_config(runtime.pending_profile.data(),
                                      runtime.pending_profile.size()) != ECH_DSP_STATUS_OK)
            {
                ech_dsp_shutdown();
                runtime.dsp_initialised = false;
                return false;
            }
            return true;
        }
    } // namespace

    bool InitialiseRuntime()
    {
        auto &runtime = GetRuntimeState();
        std::lock_guard<std::mutex> lock(runtime.mutex);
        runtime.abi_compatible = ech_dsp_api_get_version() == ECH_DSP_API_VERSION;
        runtime.status.store(runtime.abi_compatible ? ECHIDNA_STATUS_WAITING_FOR_ATTACH
                                                    : ECHIDNA_STATUS_ERROR,
                             std::memory_order_release);
        return runtime.abi_compatible;
    }

    bool IsRuntimeReady()
    {
        auto &runtime = GetRuntimeState();
        return runtime.abi_compatible && !runtime.bypass.load(std::memory_order_acquire);
    }

    void SetRuntimeBypass(bool bypass)
    {
        auto &runtime = GetRuntimeState();
        runtime.bypass.store(bypass, std::memory_order_release);
        runtime.status.store(bypass ? ECHIDNA_STATUS_DISABLED
                                    : (runtime.abi_compatible
                                           ? ECHIDNA_STATUS_WAITING_FOR_ATTACH
                                           : ECHIDNA_STATUS_ERROR),
                             std::memory_order_release);
    }

    bool SetRuntimeProfile(const char *json, size_t length)
    {
        if (!json || length == 0)
        {
            return false;
        }
        auto &runtime = GetRuntimeState();
        std::lock_guard<std::mutex> lock(runtime.mutex);
        try
        {
            runtime.pending_profile.assign(json, length);
        }
        catch (...)
        {
            runtime.status.store(ECHIDNA_STATUS_ERROR, std::memory_order_release);
            return false;
        }
        if (!runtime.dsp_initialised)
        {
            return true;
        }
        const bool ok = ech_dsp_update_config(json, length) == ECH_DSP_STATUS_OK;
        if (!ok)
        {
            runtime.status.store(ECHIDNA_STATUS_ERROR, std::memory_order_release);
        }
        return ok;
    }

    int RuntimeStatus()
    {
        return GetRuntimeState().status.load(std::memory_order_acquire);
    }

    echidna_result_t ProcessRuntimeBlock(const float *input,
                                         float *output,
                                         uint32_t frames,
                                         uint32_t sample_rate,
                                         uint32_t channels)
    {
        if (!input || !output || frames == 0 || sample_rate == 0 || channels == 0 ||
            channels > 8)
        {
            return ECHIDNA_RESULT_INVALID_ARGUMENT;
        }
        auto &runtime = GetRuntimeState();
        if (runtime.bypass.load(std::memory_order_acquire))
        {
            return ECHIDNA_RESULT_NOT_AVAILABLE;
        }
        std::unique_lock<std::mutex> lock(runtime.mutex, std::try_to_lock);
        if (!lock.owns_lock())
        {
            return ECHIDNA_RESULT_NOT_AVAILABLE;
        }
        if (!EnsureDspInitialisedLocked(runtime, sample_rate, channels))
        {
            runtime.status.store(ECHIDNA_STATUS_ERROR, std::memory_order_release);
            return ECHIDNA_RESULT_NOT_INITIALISED;
        }
        const echidna_result_t result =
            ConvertStatus(ech_dsp_process_block(input, output, frames));
        runtime.status.store(result == ECHIDNA_RESULT_OK ? ECHIDNA_STATUS_HOOKED
                                                         : ECHIDNA_STATUS_ERROR,
                             std::memory_order_release);
        return result;
    }

} // namespace echidna::jni
