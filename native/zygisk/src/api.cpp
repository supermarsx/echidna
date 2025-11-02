#include "echidna/api.h"

#include <algorithm>
#include <cmath>
#include <cstring>
#include <time.h>

#include "state/shared_state.h"
#include "utils/telemetry_shared_memory.h"

using echidna::state::SharedState;

namespace {

uint64_t DiffNanoseconds(const timespec &start, const timespec &end) {
    const int64_t sec = static_cast<int64_t>(end.tv_sec) - static_cast<int64_t>(start.tv_sec);
    const int64_t nsec = static_cast<int64_t>(end.tv_nsec) - static_cast<int64_t>(start.tv_nsec);
    return static_cast<uint64_t>(sec) * 1000000000ull + static_cast<uint64_t>(nsec);
}

uint32_t ClampNanosecondsToMicros(uint64_t ns) {
    return static_cast<uint32_t>(ns / 1000ull);
}

float LinearToDb(float value) {
    if (value <= 0.0f) {
        return -120.0f;
    }
    return 20.0f * std::log10(value);
}

float FrequencyForMidi(float midi) {
    return 440.0f * std::pow(2.0f, (midi - 69.0f) / 12.0f);
}

}  // namespace

echidna_status_t echidna_get_status(void) {
    return static_cast<echidna_status_t>(SharedState::instance().status());
}

void echidna_set_profile(const char *profile) {
    if (!profile) {
        return;
    }
    SharedState::instance().setProfile(profile);
}

echidna_status_t echidna_process_block(const float *input,
                                       float *output,
                                       uint32_t frames,
                                       uint32_t sample_rate,
                                       uint32_t channel_count) {
    auto &state = SharedState::instance();
    if (!input || frames == 0 || channel_count == 0 || sample_rate == 0) {
        state.setStatus(echidna::state::InternalStatus::kError);
        state.telemetry().recordCallback(0, 0, 0, echidna::utils::kTelemetryFlagError, 0);
        return static_cast<echidna_status_t>(state.status());
    }

    timespec wall_start{};
    timespec wall_end{};
    timespec cpu_start{};
    timespec cpu_end{};
    clock_gettime(CLOCK_MONOTONIC, &wall_start);
    clock_gettime(CLOCK_THREAD_CPUTIME_ID, &cpu_start);

    if (output && output != input) {
        std::memcpy(output, input, static_cast<size_t>(frames) * channel_count * sizeof(float));
    }

    clock_gettime(CLOCK_THREAD_CPUTIME_ID, &cpu_end);
    clock_gettime(CLOCK_MONOTONIC, &wall_end);

    const uint64_t wall_ns = DiffNanoseconds(wall_start, wall_end);
    const uint64_t cpu_ns = DiffNanoseconds(cpu_start, cpu_end);
    const uint32_t wall_us = ClampNanosecondsToMicros(wall_ns);
    const uint32_t cpu_us = ClampNanosecondsToMicros(cpu_ns);

    const size_t total_samples = static_cast<size_t>(frames) * static_cast<size_t>(channel_count);
    double sum_squares = 0.0;
    float peak = 0.0f;
    size_t zero_crossings = 0;

    if (total_samples > 0) {
        const float *samples = input;
        float previous = samples[0];
        peak = std::max(peak, std::fabs(previous));
        sum_squares += static_cast<double>(previous) * static_cast<double>(previous);
        for (size_t i = 1; i < total_samples; ++i) {
            const float sample = samples[i];
            peak = std::max(peak, std::fabs(sample));
            sum_squares += static_cast<double>(sample) * static_cast<double>(sample);
            if ((previous >= 0.0f && sample < 0.0f) || (previous < 0.0f && sample >= 0.0f)) {
                zero_crossings += 1;
            }
            previous = sample;
        }
    }

    const float rms = total_samples > 0 ? static_cast<float>(std::sqrt(sum_squares / total_samples)) : 0.0f;
    const float rms_db = LinearToDb(rms);
    const float peak_db = LinearToDb(peak);

    float detected_pitch = 0.0f;
    if (zero_crossings > 0 && frames > 0) {
        const float cycles = static_cast<float>(zero_crossings) / 2.0f;
        detected_pitch = (cycles * static_cast<float>(sample_rate)) / static_cast<float>(frames);
    }

    float target_pitch = detected_pitch;
    float formant_shift_cents = 0.0f;
    float formant_width = 0.0f;
    if (detected_pitch > 0.0f) {
        const float midi = 69.0f + 12.0f * std::log2(detected_pitch / 440.0f);
        const float rounded_midi = std::round(midi);
        target_pitch = FrequencyForMidi(rounded_midi);
        formant_shift_cents = (midi - rounded_midi) * 100.0f;
        formant_width = std::clamp(std::fabs(formant_shift_cents), 0.0f, 600.0f);
    }

    const uint64_t timestamp_ns = static_cast<uint64_t>(wall_end.tv_sec) * 1000000000ull +
                                  static_cast<uint64_t>(wall_end.tv_nsec);

    state.telemetry().recordCallback(timestamp_ns,
                                     wall_us,
                                     cpu_us,
                                     echidna::utils::kTelemetryFlagDsp,
                                     0);
    state.telemetry().updateAudioLevels(rms_db,
                                        rms_db,
                                        peak_db,
                                        peak_db,
                                        detected_pitch,
                                        target_pitch,
                                        formant_shift_cents,
                                        formant_width,
                                        0);

    state.setStatus(echidna::state::InternalStatus::kHooked);
    return static_cast<echidna_status_t>(state.status());
}
