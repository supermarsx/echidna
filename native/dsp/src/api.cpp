#include "echidna/dsp/api.h"

/**
 * @file api.cpp
 * @brief C ABI bridging functions exposing the DSP engine to external callers.
 */

#include <memory>
#include <mutex>
#include <new>
#include <string>

#include "config/preset_loader.h"
#include "engine.h"

namespace
{
    std::mutex g_engine_mutex;
    std::shared_ptr<echidna::dsp::DspEngine> g_engine;
    constexpr uint32_t kMaxChannels = 8;

    bool IsValidQualityMode(ech_dsp_quality_mode_t quality_mode)
    {
        return quality_mode == ECH_DSP_QUALITY_LOW_LATENCY ||
               quality_mode == ECH_DSP_QUALITY_BALANCED ||
               quality_mode == ECH_DSP_QUALITY_HIGH;
    }
} // namespace

namespace echidna::dsp
{

    /**
     * @brief Accessor used internally to safely retrieve the global engine.
     */
    std::shared_ptr<DspEngine> acquire_engine()
    {
        std::lock_guard<std::mutex> lock(g_engine_mutex);
        return g_engine;
    }

    /**
     * @brief Placeholder for symmetry with acquire_engine; no-op currently.
     */
    void release_engine() {}

} // namespace echidna::dsp

extern "C"
{

    /**
     * @brief Initialize a global DSP engine instance.
     *
     * This sets up the singleton engine with the provided sample-rate and
     * channel layout. Must be called before processing or updating configuration.
     */
    ech_dsp_status_t ech_dsp_initialize(uint32_t sample_rate,
                                        uint32_t channels,
                                        ech_dsp_quality_mode_t quality_mode)
    {
        if (sample_rate == 0 || channels == 0 || channels > kMaxChannels)
        {
            return ECH_DSP_STATUS_INVALID_ARGUMENT;
        }
        const ech_dsp_quality_mode_t safe_quality =
            IsValidQualityMode(quality_mode) ? quality_mode : ECH_DSP_QUALITY_BALANCED;
        try
        {
            std::lock_guard<std::mutex> lock(g_engine_mutex);
            g_engine = std::make_shared<echidna::dsp::DspEngine>(sample_rate, channels,
                                                                 safe_quality);
            return ECH_DSP_STATUS_OK;
        }
        catch (const std::bad_alloc &)
        {
            return ECH_DSP_STATUS_ERROR;
        }
        catch (...)
        {
            return ECH_DSP_STATUS_ERROR;
        }
    }

    /**
     * @brief Update engine configuration from a JSON buffer.
     *
     * The provided JSON is parsed into a preset definition and applied to the
     * current engine instance.
     */
    ech_dsp_status_t ech_dsp_update_config(const char *json_config,
                                           size_t json_length)
    {
        if (!json_config || json_length == 0)
        {
            return ECH_DSP_STATUS_INVALID_ARGUMENT;
        }
        std::shared_ptr<echidna::dsp::DspEngine> engine;
        {
            std::lock_guard<std::mutex> lock(g_engine_mutex);
            engine = g_engine;
        }
        if (!engine)
        {
            return ECH_DSP_STATUS_NOT_INITIALISED;
        }
        try
        {
            std::string json(json_config, json_length);
            auto result = echidna::dsp::config::LoadPresetFromJson(json);
            if (!result.ok)
            {
                return ECH_DSP_STATUS_INVALID_ARGUMENT;
            }
            return engine->UpdatePreset(result.preset);
        }
        catch (const std::bad_alloc &)
        {
            return ECH_DSP_STATUS_ERROR;
        }
        catch (...)
        {
            return ECH_DSP_STATUS_ERROR;
        }
    }

    /**
     * @brief Process a single block through the global engine instance.
     */
    ech_dsp_status_t ech_dsp_process_block(const float *input,
                                           float *output,
                                           size_t frames)
    {
        if (!input || !output)
        {
            return ECH_DSP_STATUS_INVALID_ARGUMENT;
        }
        std::shared_ptr<echidna::dsp::DspEngine> engine;
        {
            std::lock_guard<std::mutex> lock(g_engine_mutex);
            engine = g_engine;
        }
        if (!engine)
        {
            return ECH_DSP_STATUS_NOT_INITIALISED;
        }
        try
        {
            return engine->ProcessBlock(input, output, frames);
        }
        catch (const std::bad_alloc &)
        {
            return ECH_DSP_STATUS_ERROR;
        }
        catch (...)
        {
            return ECH_DSP_STATUS_ERROR;
        }
    }

    /**
     * @brief Shutdown the global engine and free resources.
     */
    void ech_dsp_shutdown(void)
    {
        try
        {
            std::lock_guard<std::mutex> lock(g_engine_mutex);
            g_engine.reset();
        }
        catch (...)
        {
        }
    }

} // extern "C"
