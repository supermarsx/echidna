#include "echidna/api.h"

/**
 * @file api.cpp
 * @brief Bridge between the zygisk hook runtime and the DSP library. Provides
 * exported C API functions called by the hooked audio path.
 */

#include <algorithm>
#include <cmath>
#include <cstring>
#include <dlfcn.h>
#include <mutex>
#include <string>
#include <string_view>
#include <vector>

#include "echidna/dsp/api.h"
#include "state/shared_state.h"
#include "utils/telemetry_shared_memory.h"

using echidna::state::SharedState;

namespace
{

    struct DspBridge
    {
        using InitFn = ech_dsp_status_t (*)(uint32_t, uint32_t, ech_dsp_quality_mode_t);
        using UpdateFn = ech_dsp_status_t (*)(const char *, size_t);
        using ProcessFn = ech_dsp_status_t (*)(const float *, float *, size_t);
        using ShutdownFn = void (*)(void);

        void *handle{nullptr};
        InitFn init{nullptr};
        UpdateFn update{nullptr};
        ProcessFn process{nullptr};
        ShutdownFn shutdown{nullptr};
        uint32_t sample_rate{0};
        uint32_t channels{0};
        ech_dsp_quality_mode_t quality{ECH_DSP_QUALITY_BALANCED};
        bool initialised{false};
        std::string pending_preset;
        std::vector<float> scratch_output;
    };

    DspBridge &GetDspBridge()
    {
        static DspBridge bridge;
        return bridge;
    }

    std::mutex &DspMutex()
    {
        static std::mutex mutex;
        return mutex;
    }

    /** Convert DSP status codes to echidna API result codes. */
    echidna_result_t ToEchidnaResult(ech_dsp_status_t status)
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

    /** Convert linear amplitude to decibels-safe lower bound. */
    float LinearToDb(float value)
    {
        if (value <= 0.0f)
        {
            return -120.0f;
        }
        return 20.0f * std::log10(value);
    }

    struct LevelStats
    {
        float rms_db{-120.0f};
        float peak_db{-120.0f};
    };

    /** Compute RMS and peak dB for an interleaved float buffer. */
    LevelStats CalculateLevels(const float *data, size_t samples)
    {
        if (!data || samples == 0)
        {
            return {};
        }
        double sum_squares = 0.0;
        float peak = 0.0f;
        for (size_t i = 0; i < samples; ++i)
        {
            const float sample = data[i];
            peak = std::max(peak, std::fabs(sample));
            sum_squares += static_cast<double>(sample) * static_cast<double>(sample);
        }
        const float rms = static_cast<float>(std::sqrt(sum_squares / samples));
        return {LinearToDb(rms), LinearToDb(peak)};
    }

    /** Quick pitch estimation using zero crossing counts for diagnostics. */
    float EstimatePitchHz(const float *data,
                          uint32_t frames,
                          uint32_t channels,
                          uint32_t sample_rate)
    {
        if (!data || frames == 0 || channels == 0 || sample_rate == 0)
        {
            return 0.0f;
        }
        size_t zero_crossings = 0;
        const size_t step = static_cast<size_t>(channels);
        const size_t samples = static_cast<size_t>(frames) * step;
        float previous = data[0];
        for (size_t i = step; i < samples; i += step)
        {
            const float sample = data[i];
            if ((previous >= 0.0f && sample < 0.0f) || (previous < 0.0f && sample >= 0.0f))
            {
                zero_crossings += 1;
            }
            previous = sample;
        }
        if (zero_crossings == 0)
        {
            return 0.0f;
        }
        const float cycles = static_cast<float>(zero_crossings) / 2.0f;
        return (cycles * static_cast<float>(sample_rate)) / static_cast<float>(frames);
    }

    float FrequencyForMidi(float midi)
    {
        return 440.0f * std::pow(2.0f, (midi - 69.0f) / 12.0f);
    }

    /** Attempt to extract a short "name" value from a JSON preset string. */
    std::string ExtractPresetName(const char *json, size_t length)
    {
        if (!json || length == 0)
        {
            return {};
        }
        const std::string_view view(json, length);
        const size_t key_pos = view.find("\"name\"");
        if (key_pos == std::string_view::npos)
        {
            return {};
        }
        size_t colon = view.find(':', key_pos);
        if (colon == std::string_view::npos)
        {
            return {};
        }
        size_t start = view.find('"', colon);
        if (start == std::string_view::npos)
        {
            return {};
        }
        size_t end = view.find('"', start + 1);
        while (end != std::string_view::npos && view[end - 1] == '\\')
        {
            end = view.find('"', end + 1);
        }
        if (end == std::string_view::npos || end <= start + 1)
        {
            return {};
        }
        return std::string(view.substr(start + 1, end - start - 1));
    }

    /** Load libech_dsp.so and bind its C entrypoints. */
    bool LoadDspLocked(DspBridge &dsp)
    {
        if (dsp.handle)
        {
            return dsp.init && dsp.update && dsp.process && dsp.shutdown;
        }
        dsp.handle = dlopen("libech_dsp.so", RTLD_NOW | RTLD_LOCAL);
        if (!dsp.handle)
        {
            return false;
        }
        dsp.init = reinterpret_cast<DspBridge::InitFn>(dlsym(dsp.handle, "ech_dsp_initialize"));
        dsp.update =
            reinterpret_cast<DspBridge::UpdateFn>(dlsym(dsp.handle, "ech_dsp_update_config"));
        dsp.process =
            reinterpret_cast<DspBridge::ProcessFn>(dlsym(dsp.handle, "ech_dsp_process_block"));
        dsp.shutdown = reinterpret_cast<DspBridge::ShutdownFn>(dlsym(dsp.handle, "ech_dsp_shutdown"));
        if (!dsp.init || !dsp.update || !dsp.process || !dsp.shutdown)
        {
            dlclose(dsp.handle);
            dsp.handle = nullptr;
            dsp.init = nullptr;
            dsp.update = nullptr;
            dsp.process = nullptr;
            dsp.shutdown = nullptr;
            return false;
        }
        return true;
    }

    echidna_result_t EnsureInitialisedLocked(DspBridge &dsp,
                                             uint32_t sample_rate,
                                             uint32_t channels)
    {
        if (!LoadDspLocked(dsp))
        {
            return ECHIDNA_RESULT_NOT_AVAILABLE;
        }
        if (dsp.initialised && dsp.sample_rate == sample_rate && dsp.channels == channels)
        {
            return ECHIDNA_RESULT_OK;
        }
        if (dsp.initialised && dsp.shutdown)
        {
            dsp.shutdown();
        }
        dsp.sample_rate = sample_rate;
        dsp.channels = channels;
        const ech_dsp_status_t init_status = dsp.init(sample_rate, channels, dsp.quality);
        if (init_status != ECH_DSP_STATUS_OK)
        {
            dsp.initialised = false;
            return ToEchidnaResult(init_status);
        }
        dsp.initialised = true;
        if (!dsp.pending_preset.empty())
        {
            const ech_dsp_status_t update_status =
                dsp.update(dsp.pending_preset.data(), dsp.pending_preset.size());
            if (update_status != ECH_DSP_STATUS_OK)
            {
                return ToEchidnaResult(update_status);
            }
        }
        return ECHIDNA_RESULT_OK;
    }

    /** Apply a preset into an already loaded DSP bridge (io locked). */
    echidna_result_t ApplyPresetLocked(DspBridge &dsp, const char *json, size_t length)
    {
        if (!LoadDspLocked(dsp))
        {
            return ECHIDNA_RESULT_NOT_AVAILABLE;
        }
        if (!dsp.initialised)
        {
            dsp.pending_preset.assign(json, length);
            return ECHIDNA_RESULT_OK;
        }
        const ech_dsp_status_t status = dsp.update(json, length);
        return ToEchidnaResult(status);
    }

} // namespace

uint32_t echidna_api_get_version(void)
{
    return ECHIDNA_API_VERSION;
}

echidna_status_t echidna_get_status(void)
{
    return static_cast<echidna_status_t>(SharedState::instance().status());
}

echidna_result_t echidna_set_profile(const char *profile_json, size_t length)
{
    if (!profile_json || length == 0)
    {
        return ECHIDNA_RESULT_INVALID_ARGUMENT;
    }
    auto &state = SharedState::instance();
    std::lock_guard<std::mutex> lock(DspMutex());
    auto &dsp = GetDspBridge();
    dsp.pending_preset.assign(profile_json, length);

    const std::string label = ExtractPresetName(profile_json, length);
    if (!label.empty())
    {
        state.setProfile(label);
    }
    else if (length < 96)
    {
        state.setProfile(std::string(profile_json, length));
    }

    const echidna_result_t result = ApplyPresetLocked(dsp, profile_json, length);
    if (result != ECHIDNA_RESULT_OK && result != ECHIDNA_RESULT_NOT_INITIALISED)
    {
        state.setStatus(echidna::state::InternalStatus::kError);
    }
    return result;
}

echidna_result_t echidna_process_block(const float *input,
                                       float *output,
                                       uint32_t frames,
                                       uint32_t sample_rate,
                                       uint32_t channel_count)
{
    auto &state = SharedState::instance();
    if (!input || frames == 0 || channel_count == 0 || sample_rate == 0)
    {
        state.setStatus(echidna::state::InternalStatus::kError);
        state.telemetry().recordCallback(0, 0, 0, echidna::utils::kTelemetryFlagError, 0);
        return ECHIDNA_RESULT_INVALID_ARGUMENT;
    }

    timespec wall_start{};
    timespec wall_end{};
    timespec cpu_start{};
    timespec cpu_end{};
    clock_gettime(CLOCK_MONOTONIC, &wall_start);
    clock_gettime(CLOCK_THREAD_CPUTIME_ID, &cpu_start);

    echidna_result_t result = ECHIDNA_RESULT_OK;
    uint32_t flags = echidna::utils::kTelemetryFlagDsp;
    const size_t sample_count =
        static_cast<size_t>(frames) * static_cast<size_t>(channel_count);
    LevelStats input_levels = CalculateLevels(input, sample_count);
    LevelStats output_levels;
    float detected_pitch = 0.0f;
    float target_pitch = 0.0f;
    float formant_shift_cents = 0.0f;
    float formant_width = 0.0f;

    {
        std::lock_guard<std::mutex> lock(DspMutex());
        auto &dsp = GetDspBridge();
        result = EnsureInitialisedLocked(dsp, sample_rate, channel_count);
        if (result != ECHIDNA_RESULT_OK)
        {
            flags |= echidna::utils::kTelemetryFlagError;
        }
        else
        {
            const size_t samples = sample_count;
            float *process_output = output;
            if (!output || output == input)
            {
                dsp.scratch_output.resize(samples);
                process_output = dsp.scratch_output.data();
            }
            const ech_dsp_status_t dsp_status =
                dsp.process(input, process_output, static_cast<size_t>(frames));
            result = ToEchidnaResult(dsp_status);
            if (result != ECHIDNA_RESULT_OK)
            {
                flags |= echidna::utils::kTelemetryFlagError;
                if (output && output != input)
                {
                    std::memcpy(output,
                                input,
                                sizeof(float) * static_cast<size_t>(frames) * channel_count);
                }
            }
            else
            {
                if (output && process_output != output)
                {
                    std::memcpy(output,
                                process_output,
                                sizeof(float) * static_cast<size_t>(frames) * channel_count);
                }
                output_levels = CalculateLevels(process_output, sample_count);
                detected_pitch =
                    EstimatePitchHz(process_output, frames, channel_count, sample_rate);
                if (detected_pitch > 0.0f)
                {
                    const float midi = 69.0f + 12.0f * std::log2(detected_pitch / 440.0f);
                    const float rounded_midi = std::round(midi);
                    target_pitch = FrequencyForMidi(rounded_midi);
                    formant_shift_cents = (midi - rounded_midi) * 100.0f;
                    formant_width = std::clamp(std::fabs(formant_shift_cents), 0.0f, 600.0f);
                }
            }
        }
    }

    clock_gettime(CLOCK_THREAD_CPUTIME_ID, &cpu_end);
    clock_gettime(CLOCK_MONOTONIC, &wall_end);

    const int64_t wall_ns_raw = (static_cast<int64_t>(wall_end.tv_sec) -
                                 static_cast<int64_t>(wall_start.tv_sec)) *
                                    1000000000ll +
                                (static_cast<int64_t>(wall_end.tv_nsec) -
                                 static_cast<int64_t>(wall_start.tv_nsec));
    const int64_t cpu_ns_raw = (static_cast<int64_t>(cpu_end.tv_sec) -
                                static_cast<int64_t>(cpu_start.tv_sec)) *
                                   1000000000ll +
                               (static_cast<int64_t>(cpu_end.tv_nsec) -
                                static_cast<int64_t>(cpu_start.tv_nsec));
    const uint32_t wall_us = static_cast<uint32_t>(std::max<int64_t>(wall_ns_raw, 0ll) / 1000ll);
    const uint32_t cpu_us = static_cast<uint32_t>(std::max<int64_t>(cpu_ns_raw, 0ll) / 1000ll);
    const uint64_t timestamp_ns = static_cast<uint64_t>(wall_end.tv_sec) * 1000000000ull +
                                  static_cast<uint64_t>(wall_end.tv_nsec);

    state.telemetry().recordCallback(timestamp_ns, wall_us, cpu_us, flags, 0);
    state.telemetry().updateAudioLevels(input_levels.rms_db,
                                        output_levels.rms_db,
                                        input_levels.peak_db,
                                        output_levels.peak_db,
                                        detected_pitch,
                                        target_pitch,
                                        formant_shift_cents,
                                        formant_width,
                                        0);

    if (result == ECHIDNA_RESULT_OK)
    {
        state.setStatus(echidna::state::InternalStatus::kHooked);
    }
    else if (state.status() != static_cast<int>(echidna::state::InternalStatus::kDisabled))
    {
        state.setStatus(echidna::state::InternalStatus::kError);
    }
    return result;
}
