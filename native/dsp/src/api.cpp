#include "echidna/dsp/api.h"

/**
 * @file api.cpp
 * @brief C ABI bridging functions exposing the DSP engine to external callers.
 */

#include <memory>
#include <mutex>
#include <new>
#include <string>
#include <string_view>

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

struct ech_dsp_engine
{
    std::unique_ptr<echidna::dsp::DspEngine> implementation;
};

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

    uint32_t ech_dsp_api_get_version(void)
    {
        return ECH_DSP_API_VERSION;
    }

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

    ech_dsp_status_t ech_dsp_prepare_realtime(size_t max_frames)
    {
        if (max_frames == 0)
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
        return engine->PrepareRealtime(max_frames);
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
            std::unique_lock lock(g_engine_mutex, std::try_to_lock);
            if (!lock.owns_lock())
            {
                return ECH_DSP_STATUS_ERROR;
            }
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

    ech_dsp_status_t ech_dsp_engine_create(uint32_t sample_rate,
                                           uint32_t channels,
                                           ech_dsp_quality_mode_t quality_mode,
                                           size_t max_frames,
                                           const char *config,
                                           size_t config_length,
                                           ech_dsp_engine_t **engine)
    {
        if (!engine || sample_rate == 0 || channels == 0 || channels > kMaxChannels ||
            max_frames == 0 || (config == nullptr) != (config_length == 0))
        {
            return ECH_DSP_STATUS_INVALID_ARGUMENT;
        }
        *engine = nullptr;
        const ech_dsp_quality_mode_t safe_quality =
            IsValidQualityMode(quality_mode) ? quality_mode : ECH_DSP_QUALITY_BALANCED;
        try
        {
            echidna::dsp::config::PresetDefinition preset;
            if (config)
            {
                const auto loaded = echidna::dsp::config::LoadPresetFromJson(
                    std::string_view(config, config_length));
                if (!loaded.ok)
                {
                    return ECH_DSP_STATUS_INVALID_ARGUMENT;
                }
                preset = loaded.preset;
            }

            auto holder = std::make_unique<ech_dsp_engine_t>();
            echidna::dsp::DspEngineOptions options;
            options.load_plugins = false;
            options.lock_free_realtime_process = true;
            holder->implementation = std::make_unique<echidna::dsp::DspEngine>(
                sample_rate, channels, safe_quality, options);
            if (holder->implementation->UpdatePreset(preset) != ECH_DSP_STATUS_OK ||
                holder->implementation->PrepareRealtime(max_frames) != ECH_DSP_STATUS_OK)
            {
                return ECH_DSP_STATUS_ERROR;
            }
            *engine = holder.release();
            return ECH_DSP_STATUS_OK;
        }
        catch (...)
        {
            return ECH_DSP_STATUS_ERROR;
        }
    }

    ech_dsp_status_t ech_dsp_engine_process(ech_dsp_engine_t *engine,
                                            const float *input,
                                            float *output,
                                            size_t frames)
    {
        if (!engine || !engine->implementation || !input || !output || frames == 0)
        {
            return ECH_DSP_STATUS_INVALID_ARGUMENT;
        }
        try
        {
            return engine->implementation->ProcessBlock(input, output, frames);
        }
        catch (...)
        {
            return ECH_DSP_STATUS_ERROR;
        }
    }

    void ech_dsp_engine_destroy(ech_dsp_engine_t *engine)
    {
        try
        {
            delete engine;
        }
        catch (...)
        {
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
