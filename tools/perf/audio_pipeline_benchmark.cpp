#include "audio/pcm_buffer_processor.h"
#include "echidna/dsp/api.h"

#include <algorithm>
#include <array>
#include <atomic>
#include <bit>
#include <chrono>
#include <cmath>
#include <cstddef>
#include <cstdint>
#include <cstdlib>
#include <cstring>
#include <ctime>
#include <filesystem>
#include <fstream>
#include <iomanip>
#include <iostream>
#include <limits>
#include <map>
#include <numeric>
#include <optional>
#include <sstream>
#include <string>
#include <string_view>
#include <thread>
#include <utility>
#include <vector>

#ifndef ECHIDNA_PERF_BUILD_TYPE
#define ECHIDNA_PERF_BUILD_TYPE "unknown"
#endif

#ifndef ECHIDNA_PERF_CXX_FLAGS
#define ECHIDNA_PERF_CXX_FLAGS "unknown"
#endif

namespace
{
    using Clock = std::chrono::steady_clock;

    constexpr double kPi = 3.14159265358979323846;
    constexpr uint64_t kScenarioSeed = 0x45434849ULL;
    constexpr float kInputGuard = 12345.25f;
    constexpr float kOutputGuard = -54321.5f;
    constexpr int16_t kPcmGuard = static_cast<int16_t>(0x5a3c);
    constexpr size_t kCacheLineBytes = 64;

    std::atomic<uint64_t> g_published_checksum{0};

    uint64_t MixChecksum(uint64_t accumulator, uint64_t value)
    {
        constexpr uint64_t golden_ratio = 0x9e3779b97f4a7c15ULL;
        return std::rotl(accumulator, 17) ^ (value + golden_ratio + (accumulator << 6) +
                                             (accumulator >> 2));
    }

    uint64_t ObserveBytes(const void *data, size_t byte_count, uint64_t selector)
    {
        if (data == nullptr || byte_count == 0)
        {
            return MixChecksum(selector, byte_count);
        }
        const auto *bytes = static_cast<const uint8_t *>(data);
        uint64_t checksum = MixChecksum(selector, byte_count);
        const size_t stride = std::max<size_t>(1, byte_count / 8);
        for (size_t sample = 0; sample < 8; ++sample)
        {
            const size_t index = static_cast<size_t>(
                (selector + sample * stride) % byte_count);
            checksum = MixChecksum(checksum, bytes[index]);
        }
        return checksum;
    }

    using BufferObserver = uint64_t (*)(const void *, size_t, uint64_t);
    BufferObserver volatile g_buffer_observer = ObserveBytes;

    inline void DoNotOptimizeBuffer(const void *data, size_t byte_count)
    {
#if defined(_MSC_VER)
        (void)data;
        (void)byte_count;
        std::atomic_signal_fence(std::memory_order_seq_cst);
#elif defined(__GNUC__) || defined(__clang__)
        __asm__ __volatile__("" : : "r"(data), "r"(byte_count) : "memory");
#else
        (void)data;
        (void)byte_count;
        std::atomic_signal_fence(std::memory_order_seq_cst);
#endif
    }

    enum class AudioPath
    {
        kFloat32,
        kPcm16,
    };

    struct Options
    {
        bool quick{false};
        size_t warmups{200};
        size_t iterations{1200};
        uint32_t load_threads{0};
        double safety_factor{4.0};
        std::filesystem::path output_dir{"."};
    };

    struct ValidationResult
    {
        std::string id;
        std::string effect;
        std::string fixture;
        bool passed{false};
        std::string detail;
        std::map<std::string, double> metrics;
    };

    struct LatencyStats
    {
        double mean_us{0.0};
        double p50_us{0.0};
        double p95_us{0.0};
        double p99_us{0.0};
        double max_us{0.0};
    };

    struct PerformanceResult
    {
        std::string scenario_id;
        std::string profile;
        std::string fixture{"multi_tone"};
        AudioPath path{AudioPath::kFloat32};
        uint32_t sample_rate{0};
        uint32_t channels{0};
        size_t frames{0};
        uint32_t load_threads{0};
        size_t warmups{0};
        size_t samples{0};
        uint64_t seed{kScenarioSeed};
        bool setup_ok{false};
        bool processing_ok{false};
        bool strict_deadline_met{false};
        bool safety_gate_passed{false};
        bool safety_gate_applies{true};
        std::string error;
        LatencyStats latency;
        double per_frame_us{0.0};
        double frames_per_second{0.0};
        double real_time_factor{0.0};
        double deadline_us{0.0};
        double safety_limit_us{0.0};
        double mean_vs_baseline_ratio{0.0};
        double p95_vs_baseline_ratio{0.0};
        double p99_vs_baseline_ratio{0.0};
        double mean_overhead_vs_baseline_us{0.0};
        double p95_overhead_vs_baseline_us{0.0};
        double p99_overhead_vs_baseline_us{0.0};
        double mean_vs_neutral_ratio{0.0};
        double p95_vs_neutral_ratio{0.0};
        double p99_vs_neutral_ratio{0.0};
        double mean_overhead_vs_neutral_us{0.0};
        double p95_overhead_vs_neutral_us{0.0};
        double p99_overhead_vs_neutral_us{0.0};
        double p95_loaded_vs_unloaded_ratio{0.0};
        double p99_loaded_vs_unloaded_ratio{0.0};
    };

    struct Profile
    {
        std::string id;
        std::string json;
        ech_dsp_quality_mode_t quality{ECH_DSP_QUALITY_LOW_LATENCY};
        bool baseline{false};
    };

    struct ProcessedSignal
    {
        bool ok{false};
        bool guards_ok{false};
        std::string error;
        std::vector<float> output;
    };

    class XorShift64
    {
    public:
        explicit XorShift64(uint64_t seed) : state_(seed == 0 ? 1 : seed) {}

        uint64_t next()
        {
            uint64_t x = state_;
            x ^= x << 13;
            x ^= x >> 7;
            x ^= x << 17;
            state_ = x;
            return x;
        }

        float symmetric()
        {
            constexpr double scale = 1.0 / static_cast<double>(uint64_t{1} << 53);
            const double unit = static_cast<double>(next() >> 11) * scale;
            return static_cast<float>((unit * 2.0) - 1.0);
        }

    private:
        uint64_t state_;
    };

    class BackgroundLoad
    {
    public:
        explicit BackgroundLoad(uint32_t count) : sinks_(count)
        {
            workers_.reserve(count);
            for (uint32_t i = 0; i < count; ++i)
            {
                workers_.emplace_back([this, i]()
                                      { Worker(i); });
            }
            if (count > 0)
            {
                std::this_thread::sleep_for(std::chrono::milliseconds(20));
            }
        }

        ~BackgroundLoad() { (void)Stop(); }

        uint64_t Stop()
        {
            if (stopped_)
            {
                return combined_checksum_;
            }
            running_.store(false, std::memory_order_release);
            for (auto &worker : workers_)
            {
                if (worker.joinable())
                {
                    worker.join();
                }
            }
            for (const auto &sink : sinks_)
            {
                combined_checksum_ = MixChecksum(combined_checksum_, sink.value);
            }
            stopped_ = true;
            return combined_checksum_;
        }

        BackgroundLoad(const BackgroundLoad &) = delete;
        BackgroundLoad &operator=(const BackgroundLoad &) = delete;

    private:
        void Worker(uint32_t index)
        {
            double value = 0.125 + static_cast<double>(index) * 0.001;
            uint64_t checksum = index + 1;
            while (running_.load(std::memory_order_acquire))
            {
                for (size_t i = 0; i < 16384; ++i)
                {
                    value = std::fma(value, 1.0000001192092896, 0.0000001);
                    if (value > 1000.0)
                    {
                        value *= 0.0001;
                    }
                }
                checksum ^= std::bit_cast<uint64_t>(value);
            }
            sinks_[index].value = MixChecksum(checksum, std::bit_cast<uint64_t>(value));
        }

        struct alignas(kCacheLineBytes) WorkerSink
        {
            uint64_t value{0};
        };

        std::atomic<bool> running_{true};
        std::vector<std::thread> workers_;
        std::vector<WorkerSink> sinks_;
        uint64_t combined_checksum_{0};
        bool stopped_{false};
    };

    const char *PathName(AudioPath path)
    {
        return path == AudioPath::kFloat32 ? "float32" : "pcm16_bridge";
    }

    std::string NeutralPreset()
    {
        return R"({
          "name":"Performance Neutral",
          "engine":{"latencyMode":"LL","blockMs":15},
          "modules":[
            {"id":"gate","enabled":false},
            {"id":"eq","enabled":false,"bands":[]},
            {"id":"comp","enabled":false},
            {"id":"pitch","enabled":false},
            {"id":"formant","enabled":false},
            {"id":"autotune","enabled":false},
            {"id":"reverb","enabled":false},
            {"id":"mix","wet":100,"outGain":0}
          ]
        })";
    }

    std::string VoiceFxPreset()
    {
        return R"({
          "name":"Performance Voice FX LL",
          "engine":{"latencyMode":"LL","blockMs":15},
          "modules":[
            {"id":"gate","enabled":true,"threshold":-60,"attackMs":3,
             "releaseMs":80,"hysteresis":3},
            {"id":"eq","enabled":true,"bands":[
              {"f":300,"g":3,"q":0.8},{"f":3000,"g":-3,"q":1.2}]},
            {"id":"comp","enabled":true,"mode":"manual","threshold":-18,
             "ratio":3,"knee":4,"attackMs":5,"releaseMs":120,"makeup":2},
            {"id":"pitch","enabled":true,"semitones":2,"cents":0,
             "quality":"LL","preserveFormants":false},
            {"id":"formant","enabled":true,"cents":-150,
             "intelligibility":true},
            {"id":"autotune","enabled":false},
            {"id":"reverb","enabled":true,"room":25,"damp":30,
             "predelayMs":3,"mix":12},
            {"id":"mix","wet":85,"outGain":0}
          ]
        })";
    }

    std::string HqAutoTunePreset()
    {
        return R"({
          "name":"Performance HQ AutoTune",
          "engine":{"latencyMode":"HQ","blockMs":30},
          "modules":[
            {"id":"gate","enabled":false},
            {"id":"eq","enabled":true,"bands":[{"f":1000,"g":2,"q":1}]},
            {"id":"comp","enabled":true,"mode":"manual","threshold":-20,
             "ratio":2.5,"knee":4,"attackMs":5,"releaseMs":120,"makeup":1},
            {"id":"pitch","enabled":true,"semitones":1,"cents":0,
             "quality":"HQ","preserveFormants":true},
            {"id":"formant","enabled":true,"cents":100,
             "intelligibility":false},
            {"id":"autotune","enabled":true,"key":"C","scale":"Major",
             "retuneMs":5,"humanize":5,"flexTune":0,"snapStrength":90,
             "formantPreserve":true},
            {"id":"reverb","enabled":true,"room":35,"damp":30,
             "predelayMs":5,"mix":15},
            {"id":"mix","wet":90,"outGain":0}
          ]
        })";
    }

    std::string SingleEffectPreset(std::string_view module)
    {
        std::ostringstream json;
        json << R"({"name":"Effect validation","engine":{"latencyMode":"LL",)"
             << R"("blockMs":15},"modules":[)" << module
             << R"(,{"id":"mix","wet":100,"outGain":0}]})";
        return json.str();
    }

    bool StartDsp(uint32_t sample_rate,
                  uint32_t channels,
                  ech_dsp_quality_mode_t quality,
                  const std::string &preset,
                  size_t max_frames,
                  std::string *error)
    {
        ech_dsp_shutdown();
        const auto init = ech_dsp_initialize(sample_rate, channels, quality);
        if (init != ECH_DSP_STATUS_OK)
        {
            if (error)
            {
                *error = "ech_dsp_initialize returned " + std::to_string(init);
            }
            return false;
        }
        const auto update = ech_dsp_update_config(preset.data(), preset.size());
        if (update != ECH_DSP_STATUS_OK)
        {
            if (error)
            {
                *error = "ech_dsp_update_config returned " + std::to_string(update);
            }
            ech_dsp_shutdown();
            return false;
        }
        const auto prepare = ech_dsp_prepare_realtime(max_frames);
        if (prepare != ECH_DSP_STATUS_OK)
        {
            if (error)
            {
                *error = "ech_dsp_prepare_realtime returned " + std::to_string(prepare);
            }
            ech_dsp_shutdown();
            return false;
        }
        return true;
    }

    echidna_result_t ProcessPcmAdapter(const float *input,
                                       float *output,
                                       uint32_t frames,
                                       uint32_t,
                                       uint32_t)
    {
        const auto status = ech_dsp_process_block(input, output, frames);
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

    std::vector<float> MakeFixture(std::string_view fixture,
                                   size_t frames,
                                   uint32_t sample_rate,
                                   uint32_t channels,
                                   uint64_t seed = kScenarioSeed)
    {
        std::vector<float> signal(frames * channels, 0.0f);
        XorShift64 rng(seed);
        std::vector<double> phase(channels, 0.0);
        const double max_sweep = std::min(8000.0, static_cast<double>(sample_rate) * 0.4);
        for (size_t frame = 0; frame < frames; ++frame)
        {
            const double t = static_cast<double>(frame) / sample_rate;
            const double progress = frames > 1
                                        ? static_cast<double>(frame) /
                                              static_cast<double>(frames - 1)
                                        : 0.0;
            for (uint32_t channel = 0; channel < channels; ++channel)
            {
                float value = 0.0f;
                if (fixture == "sine_sweep")
                {
                    const double frequency = 80.0 * std::pow(max_sweep / 80.0, progress);
                    phase[channel] += 2.0 * kPi * frequency / sample_rate;
                    value = static_cast<float>(0.35 * std::sin(phase[channel] + channel * 0.2));
                }
                else if (fixture == "multi_tone")
                {
                    const double offset = static_cast<double>(channel) * 37.0;
                    value = static_cast<float>(
                        0.18 * std::sin(2.0 * kPi * (220.0 + offset) * t) +
                        0.12 * std::sin(2.0 * kPi * (997.0 + offset) * t) +
                        0.08 * std::sin(2.0 * kPi * (3200.0 + offset) * t));
                }
                else if (fixture == "impulse")
                {
                    value = frame == 0 ? (channel == 0 ? 0.8f : -0.6f) : 0.0f;
                }
                else if (fixture == "noise")
                {
                    value = rng.symmetric() * 0.3f;
                }
                else if (fixture == "amplitude_step")
                {
                    const double amplitude = frame < frames / 2 ? 0.01 : 0.5;
                    value = static_cast<float>(amplitude *
                                               std::sin(2.0 * kPi * 300.0 * t));
                }
                signal[frame * channels + channel] = value;
            }
        }
        return signal;
    }

    std::vector<float> MakeTone(double frequency,
                                double amplitude,
                                size_t frames,
                                uint32_t sample_rate,
                                uint32_t channels)
    {
        std::vector<float> signal(frames * channels);
        for (size_t frame = 0; frame < frames; ++frame)
        {
            const double frame_number = static_cast<double>(frame);
            const float value = static_cast<float>(
                amplitude * std::sin(2.0 * kPi * frequency * frame_number / sample_rate));
            for (uint32_t channel = 0; channel < channels; ++channel)
            {
                signal[frame * channels + channel] = value;
            }
        }
        return signal;
    }

    bool AllFinite(const std::vector<float> &samples)
    {
        return std::all_of(samples.begin(), samples.end(),
                           [](float value)
                           { return std::isfinite(value); });
    }

    double Rms(const std::vector<float> &samples,
               uint32_t channels,
               uint32_t channel,
               size_t begin_frame,
               size_t end_frame)
    {
        if (channels == 0 || channel >= channels || begin_frame >= end_frame)
        {
            return 0.0;
        }
        end_frame = std::min(end_frame, samples.size() / channels);
        double sum = 0.0;
        size_t count = 0;
        for (size_t frame = begin_frame; frame < end_frame; ++frame)
        {
            const double value = samples[frame * channels + channel];
            sum += value * value;
            ++count;
        }
        return count == 0 ? 0.0 : std::sqrt(sum / static_cast<double>(count));
    }

    double Peak(const std::vector<float> &samples)
    {
        double peak = 0.0;
        for (float sample : samples)
        {
            peak = std::max(peak, std::fabs(static_cast<double>(sample)));
        }
        return peak;
    }

    double MaxAbsDelta(const std::vector<float> &a, const std::vector<float> &b)
    {
        const size_t count = std::min(a.size(), b.size());
        double result = 0.0;
        for (size_t i = 0; i < count; ++i)
        {
            result = std::max(result,
                              std::fabs(static_cast<double>(a[i]) -
                                        static_cast<double>(b[i])));
        }
        return result;
    }

    double MeanAbsDelta(const std::vector<float> &a, const std::vector<float> &b)
    {
        const size_t count = std::min(a.size(), b.size());
        if (count == 0)
        {
            return 0.0;
        }
        double sum = 0.0;
        for (size_t i = 0; i < count; ++i)
        {
            sum += std::fabs(static_cast<double>(a[i]) -
                             static_cast<double>(b[i]));
        }
        return sum / static_cast<double>(count);
    }

    double Correlation(const std::vector<float> &a, const std::vector<float> &b)
    {
        const size_t count = std::min(a.size(), b.size());
        if (count == 0)
        {
            return 0.0;
        }
        const double mean_a = std::accumulate(a.begin(), a.begin() + count, 0.0) /
                              static_cast<double>(count);
        const double mean_b = std::accumulate(b.begin(), b.begin() + count, 0.0) /
                              static_cast<double>(count);
        double numerator = 0.0;
        double denom_a = 0.0;
        double denom_b = 0.0;
        for (size_t i = 0; i < count; ++i)
        {
            const double da = a[i] - mean_a;
            const double db = b[i] - mean_b;
            numerator += da * db;
            denom_a += da * da;
            denom_b += db * db;
        }
        const double denominator = std::sqrt(denom_a * denom_b);
        if (denominator <= std::numeric_limits<double>::epsilon())
        {
            return MaxAbsDelta(a, b) == 0.0 ? 1.0 : 0.0;
        }
        return numerator / denominator;
    }

    double SnrDb(const std::vector<float> &reference, const std::vector<float> &actual)
    {
        const size_t count = std::min(reference.size(), actual.size());
        double signal = 0.0;
        double noise = 0.0;
        for (size_t i = 0; i < count; ++i)
        {
            const double expected = reference[i];
            const double error = actual[i] - expected;
            signal += expected * expected;
            noise += error * error;
        }
        if (noise <= std::numeric_limits<double>::epsilon())
        {
            return 300.0;
        }
        if (signal <= std::numeric_limits<double>::epsilon())
        {
            return -300.0;
        }
        return 10.0 * std::log10(signal / noise);
    }

    double GoertzelAmplitude(const std::vector<float> &samples,
                             uint32_t channels,
                             uint32_t channel,
                             uint32_t sample_rate,
                             double frequency,
                             size_t begin_frame = 0)
    {
        const size_t total_frames = samples.size() / channels;
        if (begin_frame >= total_frames || channels == 0 || channel >= channels)
        {
            return 0.0;
        }
        const double omega = 2.0 * kPi * frequency / sample_rate;
        const double coeff = 2.0 * std::cos(omega);
        double previous = 0.0;
        double previous2 = 0.0;
        for (size_t frame = begin_frame; frame < total_frames; ++frame)
        {
            const double current = samples[frame * channels + channel] +
                                   coeff * previous - previous2;
            previous2 = previous;
            previous = current;
        }
        const double power = previous2 * previous2 + previous * previous -
                             coeff * previous * previous2;
        const double count = static_cast<double>(total_frames - begin_frame);
        return count > 0.0 ? 2.0 * std::sqrt(std::max(0.0, power)) / count : 0.0;
    }

    double EstimateFrequency(const std::vector<float> &samples,
                             uint32_t channels,
                             uint32_t channel,
                             uint32_t sample_rate,
                             size_t begin_frame = 0)
    {
        const size_t frames = samples.size() / channels;
        std::vector<double> crossings;
        crossings.reserve(frames / 32);
        for (size_t frame = std::max<size_t>(1, begin_frame); frame < frames; ++frame)
        {
            const double previous = samples[(frame - 1) * channels + channel];
            const double current = samples[frame * channels + channel];
            if (previous <= 0.0 && current > 0.0 && current != previous)
            {
                const double fraction = -previous / (current - previous);
                crossings.push_back(static_cast<double>(frame - 1) + fraction);
            }
        }
        if (crossings.size() < 2)
        {
            return 0.0;
        }
        const double periods = static_cast<double>(crossings.size() - 1);
        return periods * sample_rate / (crossings.back() - crossings.front());
    }

    ProcessedSignal ProcessStream(const std::string &preset,
                                  ech_dsp_quality_mode_t quality,
                                  uint32_t sample_rate,
                                  uint32_t channels,
                                  size_t block_frames,
                                  const std::vector<float> &input)
    {
        ProcessedSignal result;
        if (block_frames == 0 || input.empty() || input.size() % channels != 0)
        {
            result.error = "invalid ProcessStream arguments";
            return result;
        }
        if (!StartDsp(sample_rate,
                      channels,
                      quality,
                      preset,
                      block_frames,
                      &result.error))
        {
            return result;
        }
        result.output.assign(input.size(), 0.0f);
        std::vector<float> guarded_input(block_frames * channels + 2, 0.0f);
        std::vector<float> guarded_output(block_frames * channels + 2, 0.0f);
        result.guards_ok = true;
        const size_t total_frames = input.size() / channels;
        for (size_t offset = 0; offset < total_frames; offset += block_frames)
        {
            const size_t count = std::min(block_frames, total_frames - offset);
            const size_t samples = count * channels;
            guarded_input.front() = kInputGuard;
            guarded_input.back() = kInputGuard;
            guarded_output.front() = kOutputGuard;
            guarded_output.back() = kOutputGuard;
            std::fill(guarded_input.begin() + 1, guarded_input.end() - 1, 0.0f);
            std::fill(guarded_output.begin() + 1, guarded_output.end() - 1, 0.0f);
            std::copy_n(input.data() + offset * channels,
                        samples,
                        guarded_input.data() + 1);
            const auto status = ech_dsp_process_block(
                guarded_input.data() + 1, guarded_output.data() + 1, count);
            if (status != ECH_DSP_STATUS_OK)
            {
                result.error = "ech_dsp_process_block returned " + std::to_string(status);
                ech_dsp_shutdown();
                return result;
            }
            result.guards_ok = result.guards_ok &&
                               guarded_input.front() == kInputGuard &&
                               guarded_input.back() == kInputGuard &&
                               guarded_output.front() == kOutputGuard &&
                               guarded_output.back() == kOutputGuard;
            std::copy_n(guarded_output.data() + 1,
                        samples,
                        result.output.data() + offset * channels);
        }
        ech_dsp_shutdown();
        result.ok = true;
        return result;
    }

    void AddValidation(std::vector<ValidationResult> *results,
                       std::string id,
                       std::string effect,
                       std::string fixture,
                       bool passed,
                       std::string detail,
                       std::map<std::string, double> metrics = {})
    {
        results->push_back({std::move(id),
                            std::move(effect),
                            std::move(fixture),
                            passed,
                            std::move(detail),
                            std::move(metrics)});
    }

    void ValidateOutputObserver(std::vector<ValidationResult> *results)
    {
        std::array<uint8_t, 64> output{};
        constexpr uint64_t selector = 0;
        DoNotOptimizeBuffer(output.data(), output.size());
        const uint64_t before = g_buffer_observer(output.data(), output.size(), selector);
        output[0] = 1;
        DoNotOptimizeBuffer(output.data(), output.size());
        const uint64_t after = g_buffer_observer(output.data(), output.size(), selector);
        const bool passed = before != after &&
                            MixChecksum(kScenarioSeed, before) !=
                                MixChecksum(kScenarioSeed, after);
        AddValidation(results,
                      "benchmark_output_dependency",
                      "benchmark",
                      "mutated_baseline_output",
                      passed,
                      passed ? "observable checksum changes when baseline output changes"
                             : "benchmark observer did not depend on changed output bytes",
                      {{"checksum_changed", passed ? 1.0 : 0.0}});
    }

    void ValidateNeutralFixtures(std::vector<ValidationResult> *results)
    {
        constexpr uint32_t sample_rate = 48000;
        constexpr uint32_t channels = 2;
        constexpr size_t frames = 12288;
        const std::vector<std::string> fixtures{
            "sine_sweep", "multi_tone", "impulse", "noise", "silence", "amplitude_step"};
        for (const auto &fixture : fixtures)
        {
            const auto input = MakeFixture(fixture, frames, sample_rate, channels);
            const auto processed = ProcessStream(
                NeutralPreset(), ECH_DSP_QUALITY_LOW_LATENCY, sample_rate, channels, 256, input);
            const double max_delta = processed.ok ? MaxAbsDelta(input, processed.output)
                                                  : std::numeric_limits<double>::infinity();
            const double snr = processed.ok ? SnrDb(input, processed.output) : -300.0;
            const bool passed = processed.ok && processed.guards_ok &&
                                AllFinite(processed.output) && max_delta <= 2.0e-6;
            AddValidation(results,
                          "neutral_" + fixture,
                          "neutral",
                          fixture,
                          passed,
                          passed ? "neutral preset is transparent and bounds-safe"
                                 : "neutral preset changed samples or processing failed: " +
                                       processed.error,
                          {{"max_abs_delta", max_delta},
                           {"snr_db", snr},
                           {"output_peak", processed.ok ? Peak(processed.output) : 0.0}});
        }
    }

    void ValidateEq(std::vector<ValidationResult> *results)
    {
        constexpr uint32_t sample_rate = 48000;
        constexpr size_t frames = 48000;
        std::vector<float> input(frames);
        for (size_t frame = 0; frame < frames; ++frame)
        {
            const double t = static_cast<double>(frame) / sample_rate;
            input[frame] = static_cast<float>(0.1 * std::sin(2.0 * kPi * 200.0 * t) +
                                              0.1 * std::sin(2.0 * kPi * 1000.0 * t));
        }
        const auto preset = SingleEffectPreset(
            R"({"id":"eq","enabled":true,"bands":[{"f":1000,"g":9,"q":2}]})");
        const auto processed = ProcessStream(
            preset, ECH_DSP_QUALITY_LOW_LATENCY, sample_rate, 1, 256, input);
        const size_t settle = 4096;
        const double center_in = GoertzelAmplitude(input, 1, 0, sample_rate, 1000.0, settle);
        const double center_out = processed.ok
                                      ? GoertzelAmplitude(processed.output,
                                                          1,
                                                          0,
                                                          sample_rate,
                                                          1000.0,
                                                          settle)
                                      : 0.0;
        const double off_in = GoertzelAmplitude(input, 1, 0, sample_rate, 200.0, settle);
        const double off_out = processed.ok
                                   ? GoertzelAmplitude(processed.output,
                                                       1,
                                                       0,
                                                       sample_rate,
                                                       200.0,
                                                       settle)
                                   : 0.0;
        const double center_gain = center_in > 0.0 ? center_out / center_in : 0.0;
        const double off_gain = off_in > 0.0 ? off_out / off_in : 0.0;
        const bool passed = processed.ok && processed.guards_ok &&
                            AllFinite(processed.output) && center_gain > 2.3 &&
                            center_gain < 3.3 && off_gain > 0.85 && off_gain < 1.2;
        AddValidation(results,
                      "eq_center_band_gain",
                      "eq",
                      "multi_tone",
                      passed,
                      passed ? "enabled EQ boosts its configured band directionally"
                             : "EQ band response did not match the configured +9 dB boost",
                      {{"center_gain_ratio", center_gain},
                       {"off_band_gain_ratio", off_gain},
                       {"mean_abs_delta", processed.ok ? MeanAbsDelta(input, processed.output)
                                                       : 0.0}});
    }

    void ValidateCompressor(std::vector<ValidationResult> *results)
    {
        constexpr uint32_t sample_rate = 48000;
        constexpr size_t frames = 96000;
        const auto input = MakeFixture("amplitude_step", frames, sample_rate, 1);
        const auto preset = SingleEffectPreset(
            R"({"id":"comp","enabled":true,"mode":"manual","threshold":-24,)"
            R"("ratio":6,"knee":0,"attackMs":1,"releaseMs":120,"makeup":0})");
        const auto processed = ProcessStream(
            preset, ECH_DSP_QUALITY_LOW_LATENCY, sample_rate, 1, 256, input);
        const double quiet_in = Rms(input, 1, 0, 16000, 23000);
        const double quiet_out = processed.ok ? Rms(processed.output, 1, 0, 16000, 23000) : 0.0;
        const double loud_in = Rms(input, 1, 0, 72000, 95000);
        const double loud_out = processed.ok ? Rms(processed.output, 1, 0, 72000, 95000) : 0.0;
        const double quiet_gain = quiet_in > 0.0 ? quiet_out / quiet_in : 0.0;
        const double loud_gain = loud_in > 0.0 ? loud_out / loud_in : 0.0;
        const double input_range = quiet_in > 0.0 ? loud_in / quiet_in : 0.0;
        const double output_range = quiet_out > 0.0 ? loud_out / quiet_out : 0.0;
        const bool passed = processed.ok && processed.guards_ok &&
                            AllFinite(processed.output) && quiet_gain > 0.85 &&
                            loud_gain < 0.55 && output_range < input_range * 0.6;
        AddValidation(results,
                      "compressor_dynamic_range",
                      "compressor",
                      "amplitude_step",
                      passed,
                      passed ? "compressor reduces loud-level gain and dynamic range"
                             : "compressor did not produce expected gain reduction",
                      {{"quiet_gain_ratio", quiet_gain},
                       {"loud_gain_ratio", loud_gain},
                       {"input_dynamic_range_ratio", input_range},
                       {"output_dynamic_range_ratio", output_range}});
    }

    void ValidateGate(std::vector<ValidationResult> *results)
    {
        constexpr uint32_t sample_rate = 48000;
        constexpr size_t frames = 96000;
        const auto input = MakeTone(300.0, 0.002, frames, sample_rate, 1);
        const auto preset = SingleEffectPreset(
            R"({"id":"gate","enabled":true,"threshold":-45,"attackMs":5,)"
            R"("releaseMs":80,"hysteresis":3})");
        const auto processed = ProcessStream(
            preset, ECH_DSP_QUALITY_LOW_LATENCY, sample_rate, 1, 256, input);
        const double input_rms = Rms(input, 1, 0, frames / 2, frames);
        const double output_rms = processed.ok
                                      ? Rms(processed.output, 1, 0, frames / 2, frames)
                                      : 0.0;
        const double attenuation = input_rms > 0.0 ? output_rms / input_rms : 0.0;
        const bool passed = processed.ok && processed.guards_ok &&
                            AllFinite(processed.output) && attenuation < 0.1;
        AddValidation(results,
                      "gate_subthreshold_attenuation",
                      "gate",
                      "low_level_tone",
                      passed,
                      passed ? "gate closes after its stateful release interval"
                             : "sub-threshold signal was not attenuated after two seconds",
                      {{"steady_state_gain_ratio", attenuation},
                       {"input_rms", input_rms},
                       {"output_rms", output_rms},
                       {"fixture_duration_seconds",
                        static_cast<double>(frames) / sample_rate}});
    }

    void ValidatePitch(std::vector<ValidationResult> *results)
    {
        constexpr uint32_t sample_rate = 48000;
        constexpr size_t block_frames = 480;
        constexpr size_t block_count = 64;
        constexpr size_t total_frames = block_frames * block_count;
        struct PitchCase
        {
            int semitones;
            const char *id;
            const char *success_detail;
        };
        constexpr std::array<PitchCase, 2> cases = {
            PitchCase{4,
                      "pitch_positive_direction",
                      "positive semitone shift raises the fundamental"},
            PitchCase{-5,
                      "pitch_negative_direction",
                      "negative semitone shift lowers the fundamental"},
        };

        for (const auto &test_case : cases)
        {
            const auto input = MakeTone(300.0, 0.35, total_frames, sample_rate, 1);
            const auto preset = SingleEffectPreset(
                std::string(R"({"id":"pitch","enabled":true,"semitones":)") +
                std::to_string(test_case.semitones) +
                R"(,"cents":0,"quality":"LL","preserveFormants":false})");
            const auto processed = ProcessStream(preset,
                                                 ECH_DSP_QUALITY_LOW_LATENCY,
                                                 sample_rate,
                                                 1,
                                                 block_frames,
                                                 input);
            const auto input_tail =
                std::vector<float>(input.end() - block_frames, input.end());
            const auto output_tail =
                processed.ok
                    ? std::vector<float>(processed.output.end() - block_frames,
                                         processed.output.end())
                    : std::vector<float>{};
            constexpr size_t stream_tail_frames = block_frames * 16;
            const auto stream_output_tail =
                processed.ok
                    ? std::vector<float>(processed.output.end() - stream_tail_frames,
                                         processed.output.end())
                    : std::vector<float>{};
            const double input_hz = EstimateFrequency(input_tail, 1, 0, sample_rate, 32);
            const double output_hz = processed.ok
                                         ? EstimateFrequency(
                                               output_tail, 1, 0, sample_rate, 32)
                                         : 0.0;
            const double stream_output_hz =
                processed.ok
                    ? EstimateFrequency(stream_output_tail, 1, 0, sample_rate, 32)
                    : 0.0;
            const double expected_ratio =
                std::pow(2.0, static_cast<double>(test_case.semitones) / 12.0);
            const double actual_ratio = input_hz > 0.0 ? output_hz / input_hz : 0.0;
            const double stream_ratio =
                input_hz > 0.0 ? stream_output_hz / input_hz : 0.0;
            const bool passed = processed.ok && processed.guards_ok &&
                                AllFinite(processed.output) &&
                                std::fabs(actual_ratio - expected_ratio) < 0.04 &&
                                std::fabs(stream_ratio - expected_ratio) < 0.04;
            AddValidation(results,
                          test_case.id,
                          "pitch",
                          "sine",
                          passed,
                          passed
                              ? test_case.success_detail
                              : "pitch shift fundamental moved by the wrong amount or direction",
                          {{"input_hz", input_hz},
                           {"output_hz", output_hz},
                           {"stream_output_hz", stream_output_hz},
                           {"expected_ratio", expected_ratio},
                           {"actual_ratio", actual_ratio},
                           {"stream_ratio", stream_ratio}});
        }
    }

    void ValidateFormant(std::vector<ValidationResult> *results)
    {
        constexpr uint32_t sample_rate = 48000;
        constexpr size_t frames = 16384;
        const auto input = MakeFixture("multi_tone", frames, sample_rate, 1);
        const auto preset = SingleEffectPreset(
            R"({"id":"formant","enabled":true,"cents":600,)"
            R"("intelligibility":false})");
        const auto processed = ProcessStream(
            preset, ECH_DSP_QUALITY_LOW_LATENCY, sample_rate, 1, 512, input);
        const double input_rms = Rms(input, 1, 0, 1024, frames);
        const double output_rms = processed.ok ? Rms(processed.output, 1, 0, 1024, frames) : 0.0;
        const double energy_ratio = input_rms > 0.0 ? output_rms / input_rms : 0.0;
        const double correlation = processed.ok ? Correlation(input, processed.output) : 1.0;
        const double delta = processed.ok ? MeanAbsDelta(input, processed.output) : 0.0;
        const bool passed = processed.ok && processed.guards_ok &&
                            AllFinite(processed.output) && delta > 1.0e-4 &&
                            correlation < 0.9999 && energy_ratio > 0.9 && energy_ratio < 1.1;
        AddValidation(results,
                      "formant_phase_response",
                      "formant",
                      "multi_tone",
                      passed,
                      passed ? "formant stage changes phase while preserving bounded energy"
                             : "enabled formant stage did not measurably alter the waveform",
                      {{"correlation", correlation},
                       {"mean_abs_delta", delta},
                       {"rms_ratio", energy_ratio}});
    }

    void ValidateAutoTune(std::vector<ValidationResult> *results)
    {
        const auto preset = SingleEffectPreset(
            R"({"id":"autotune","enabled":true,"key":"C","scale":"Chromatic",)"
            R"("retuneMs":1,"humanize":0,"flexTune":0,"snapStrength":100,)"
            R"("formantPreserve":false})");

        constexpr std::array<uint32_t, 3> sample_rates = {44100, 48000, 96000};
        constexpr std::array<size_t, 3> block_sizes = {128, 480, 2048};
        constexpr std::array<double, 2> input_frequencies = {430.0, 450.0};
        for (uint32_t sample_rate : sample_rates)
        {
            for (size_t block_frames : block_sizes)
            {
                const size_t four_second_callbacks =
                    (static_cast<size_t>(sample_rate) * 4 + block_frames - 1) /
                    block_frames;
                const size_t block_count = std::max<size_t>(200, four_second_callbacks);
                const size_t total_frames = block_frames * block_count;
                for (double input_frequency : input_frequencies)
                {
                    const auto input =
                        MakeTone(input_frequency, 0.35, total_frames, sample_rate, 1);
                    const auto processed = ProcessStream(preset,
                                                         ECH_DSP_QUALITY_LOW_LATENCY,
                                                         sample_rate,
                                                         1,
                                                         block_frames,
                                                         input);
                    const size_t output_tail_frames =
                        std::min(total_frames,
                                 std::max(block_frames * 16,
                                          static_cast<size_t>(sample_rate) / 4));
                    const size_t stream_tail_frames =
                        std::min(total_frames,
                                 std::max(block_frames * 64,
                                          static_cast<size_t>(sample_rate)));
                    const auto input_tail = std::vector<float>(
                        input.end() - output_tail_frames, input.end());
                    const auto output_tail =
                        processed.ok
                            ? std::vector<float>(processed.output.end() - output_tail_frames,
                                                 processed.output.end())
                            : std::vector<float>{};
                    const auto stream_output_tail =
                        processed.ok
                            ? std::vector<float>(processed.output.end() - stream_tail_frames,
                                                 processed.output.end())
                            : std::vector<float>{};
                    const double input_hz =
                        EstimateFrequency(input_tail, 1, 0, sample_rate, 32);
                    const double output_hz =
                        processed.ok
                            ? EstimateFrequency(output_tail, 1, 0, sample_rate, 32)
                            : 0.0;
                    const double stream_output_hz =
                        processed.ok
                            ? EstimateFrequency(
                                  stream_output_tail, 1, 0, sample_rate, 32)
                            : 0.0;
                    const double input_error = std::fabs(input_hz - 440.0);
                    const double output_error = std::fabs(output_hz - 440.0);
                    const double stream_output_error =
                        std::fabs(stream_output_hz - 440.0);
                    const double delta =
                        processed.ok ? MeanAbsDelta(input_tail, output_tail) : 0.0;
                    double max_boundary_jump = 0.0;
                    if (processed.ok)
                    {
                        const size_t tail_start = total_frames - stream_tail_frames;
                        size_t boundary =
                            ((tail_start + block_frames - 1) / block_frames) * block_frames;
                        if (boundary == 0)
                        {
                            boundary = block_frames;
                        }
                        for (; boundary < total_frames; boundary += block_frames)
                        {
                            max_boundary_jump = std::max(
                                max_boundary_jump,
                                std::fabs(static_cast<double>(processed.output[boundary]) -
                                          processed.output[boundary - 1]));
                        }
                    }
                    const bool activated = delta > 1.0e-5;
                    const bool continuous = max_boundary_jump < 0.05;
                    const bool converged = activated && output_hz > 0.0 &&
                                           stream_output_hz > 0.0 &&
                                           output_error < input_error * 0.6 &&
                                           stream_output_error < input_error * 0.6 &&
                                           continuous;
                    const bool passed = processed.ok && processed.guards_ok &&
                                        AllFinite(processed.output) && converged;
                    const std::string direction =
                        input_frequency < 440.0 ? "up" : "down";
                    std::string id = "autotune_convergence_" +
                                     std::to_string(sample_rate) + "hz_" +
                                     std::to_string(block_frames) + "f_" + direction;
                    if (sample_rate == 48000 && input_frequency == 450.0 &&
                        (block_frames == 480 || block_frames == 2048))
                    {
                        id = "autotune_convergence_" + std::to_string(block_frames);
                    }
                    AddValidation(
                        results,
                        id,
                        "autotune",
                        "detuned_sine_" + direction,
                        passed,
                        passed ? "long stream converges toward A4 across callback boundaries"
                               : "autotune failed callback-size, rate, or direction convergence",
                        {{"sample_rate_hz", static_cast<double>(sample_rate)},
                         {"block_frames", static_cast<double>(block_frames)},
                         {"callback_count", static_cast<double>(block_count)},
                         {"stream_duration_seconds",
                          static_cast<double>(total_frames) / sample_rate},
                         {"input_hz", input_hz},
                         {"output_hz", output_hz},
                         {"stream_output_hz", stream_output_hz},
                         {"input_error_hz", input_error},
                         {"output_error_hz", output_error},
                         {"stream_output_error_hz", stream_output_error},
                         {"max_callback_boundary_jump", max_boundary_jump},
                         {"callback_boundary_jump_limit", 0.05},
                         {"mean_abs_delta", delta},
                         {"activated", activated ? 1.0 : 0.0}});
                }
            }
        }
    }

    void ValidateReverb(std::vector<ValidationResult> *results)
    {
        constexpr uint32_t sample_rate = 48000;
        constexpr size_t frames = 96000;
        const auto input = MakeFixture("impulse", frames, sample_rate, 1);
        const auto preset = SingleEffectPreset(
            R"({"id":"reverb","enabled":true,"room":80,"damp":20,)"
            R"("predelayMs":0,"mix":50})");
        const auto processed = ProcessStream(
            preset, ECH_DSP_QUALITY_LOW_LATENCY, sample_rate, 1, 256, input);
        const double early_tail = processed.ok ? Rms(processed.output, 1, 0, 1500, 12000) : 0.0;
        const double late_tail = processed.ok ? Rms(processed.output, 1, 0, 12000, 70000) : 0.0;
        const bool passed = processed.ok && processed.guards_ok &&
                            AllFinite(processed.output) && early_tail > 1.0e-5 &&
                            late_tail > 1.0e-7 && MeanAbsDelta(input, processed.output) > 1.0e-5;
        AddValidation(results,
                      "reverb_impulse_tail",
                      "reverb",
                      "impulse_then_silence",
                      passed,
                      passed ? "reverb produces a finite stateful decay tail"
                             : "reverb failed to produce the configured impulse tail",
                      {{"early_tail_rms", early_tail},
                       {"late_tail_rms", late_tail},
                       {"output_peak", processed.ok ? Peak(processed.output) : 0.0}});
    }

    void ValidateMix(std::vector<ValidationResult> *results)
    {
        constexpr uint32_t sample_rate = 48000;
        constexpr size_t frames = 8192;
        const auto input = MakeTone(440.0, 0.1, frames, sample_rate, 1);
        const std::string preset =
            R"({"name":"Mix validation","engine":{"latencyMode":"LL","blockMs":15},)"
            R"("modules":[{"id":"mix","wet":100,"outGain":6}]})";
        const auto processed = ProcessStream(
            preset, ECH_DSP_QUALITY_LOW_LATENCY, sample_rate, 1, 256, input);
        const double input_rms = Rms(input, 1, 0, 1024, frames);
        const double output_rms = processed.ok ? Rms(processed.output, 1, 0, 1024, frames) : 0.0;
        const double ratio = input_rms > 0.0 ? output_rms / input_rms : 0.0;
        const bool passed = processed.ok && processed.guards_ok &&
                            AllFinite(processed.output) && ratio > 1.9 && ratio < 2.1;
        AddValidation(results,
                      "mix_output_gain",
                      "mix",
                      "sine",
                      passed,
                      passed ? "mix output gain applies the expected +6 dB direction"
                             : "mix output gain did not produce approximately +6 dB",
                      {{"rms_gain_ratio", ratio},
                       {"mean_abs_delta", processed.ok ? MeanAbsDelta(input, processed.output)
                                                       : 0.0}});
    }

    void ValidateStereoIsolation(std::vector<ValidationResult> *results)
    {
        constexpr uint32_t sample_rate = 48000;
        constexpr size_t frames = 8192;
        std::vector<float> input(frames * 2, 0.0f);
        for (size_t frame = 0; frame < frames; ++frame)
        {
            const double frame_number = static_cast<double>(frame);
            input[frame * 2] = static_cast<float>(
                0.3 * std::sin(2.0 * kPi * 500.0 * frame_number / sample_rate));
        }
        const auto processed = ProcessStream(
            NeutralPreset(), ECH_DSP_QUALITY_LOW_LATENCY, sample_rate, 2, 256, input);
        const double right_rms = processed.ok ? Rms(processed.output, 2, 1, 0, frames) : 1.0;
        const double left_delta = processed.ok ? MaxAbsDelta(input, processed.output)
                                               : std::numeric_limits<double>::infinity();
        const bool passed = processed.ok && processed.guards_ok && right_rms < 1.0e-8 &&
                            left_delta < 2.0e-6;
        AddValidation(results,
                      "stereo_channel_isolation",
                      "channel_layout",
                      "left_tone_right_silence",
                      passed,
                      passed ? "stereo neutral path preserves channel isolation"
                             : "stereo neutral path introduced channel leakage or changed samples",
                      {{"right_channel_rms", right_rms}, {"max_abs_delta", left_delta}});
    }

    std::vector<int16_t> EncodePcm16(const std::vector<float> &input)
    {
        std::vector<int16_t> encoded(input.size());
        for (size_t i = 0; i < input.size(); ++i)
        {
            const float clamped = std::clamp(input[i], -1.0f, 1.0f);
            if (clamped <= -1.0f)
            {
                encoded[i] = std::numeric_limits<int16_t>::min();
            }
            else
            {
                encoded[i] = static_cast<int16_t>(std::lround(clamped * 32767.0f));
            }
        }
        return encoded;
    }

    std::vector<float> DecodePcm16(const int16_t *input, size_t count)
    {
        std::vector<float> decoded(count);
        for (size_t i = 0; i < count; ++i)
        {
            decoded[i] = static_cast<float>(input[i]) / 32768.0f;
        }
        return decoded;
    }

    void ValidatePcm16Bridge(std::vector<ValidationResult> *results)
    {
        constexpr uint32_t sample_rate = 48000;
        constexpr uint32_t channels = 2;
        constexpr size_t frames = 480;
        const auto source_float = MakeFixture("multi_tone", frames, sample_rate, channels);
        const auto source_pcm = EncodePcm16(source_float);
        std::vector<int16_t> guarded(source_pcm.size() + 2, kPcmGuard);
        std::copy(source_pcm.begin(), source_pcm.end(), guarded.begin() + 1);
        std::vector<float> input_scratch(source_pcm.size());
        std::vector<float> output_scratch(source_pcm.size());
        std::string error;
        const bool started = StartDsp(sample_rate,
                                      channels,
                                      ECH_DSP_QUALITY_LOW_LATENCY,
                                      NeutralPreset(),
                                      frames,
                                      &error);
        echidna::audio::BufferProcessResult status =
            echidna::audio::BufferProcessResult::kProcessorError;
        if (started)
        {
            status = echidna::audio::ProcessPcmBufferInPlace(
                guarded.data() + 1,
                source_pcm.size() * sizeof(int16_t),
                echidna::audio::PcmFormat::kSigned16,
                sample_rate,
                channels,
                input_scratch.data(),
                output_scratch.data(),
                input_scratch.size(),
                ProcessPcmAdapter);
        }
        ech_dsp_shutdown();
        const auto decoded = DecodePcm16(guarded.data() + 1, source_pcm.size());
        const auto source_quantized = DecodePcm16(source_pcm.data(), source_pcm.size());
        const double max_error = MaxAbsDelta(source_quantized, decoded);
        const bool guards_ok = guarded.front() == kPcmGuard && guarded.back() == kPcmGuard;
        const bool passed = started &&
                            status == echidna::audio::BufferProcessResult::kProcessed &&
                            guards_ok && max_error <= (2.1 / 32768.0);
        AddValidation(results,
                      "pcm16_neutral_quantization",
                      "pcm16_bridge",
                      "multi_tone",
                      passed,
                      passed ? "real PCM16 bridge is bounds-safe and within two LSB"
                             : "PCM16 bridge changed neutral samples beyond quantization tolerance",
                      {{"max_error_normalized", max_error},
                       {"max_error_lsb", max_error * 32768.0},
                       {"guards_ok", guards_ok ? 1.0 : 0.0}});

        const auto clip_input_float = MakeTone(440.0, 0.8, frames, sample_rate, channels);
        const auto clip_input_pcm = EncodePcm16(clip_input_float);
        guarded.assign(clip_input_pcm.size() + 2, kPcmGuard);
        std::copy(clip_input_pcm.begin(), clip_input_pcm.end(), guarded.begin() + 1);
        const std::string gain_preset =
            R"({"name":"PCM clipping","engine":{"latencyMode":"LL","blockMs":15},)"
            R"("modules":[{"id":"mix","wet":100,"outGain":12}]})";
        const bool clip_started = StartDsp(sample_rate,
                                           channels,
                                           ECH_DSP_QUALITY_LOW_LATENCY,
                                           gain_preset,
                                           frames,
                                           &error);
        if (clip_started)
        {
            status = echidna::audio::ProcessPcmBufferInPlace(
                guarded.data() + 1,
                clip_input_pcm.size() * sizeof(int16_t),
                echidna::audio::PcmFormat::kSigned16,
                sample_rate,
                channels,
                input_scratch.data(),
                output_scratch.data(),
                input_scratch.size(),
                ProcessPcmAdapter);
        }
        ech_dsp_shutdown();
        const auto [minimum, maximum] = std::minmax_element(guarded.begin() + 1,
                                                            guarded.end() - 1);
        const bool clipped_positive = maximum != guarded.end() - 1 &&
                                      *maximum == std::numeric_limits<int16_t>::max();
        const bool clipped_negative = minimum != guarded.end() - 1 &&
                                      *minimum == std::numeric_limits<int16_t>::min();
        const bool clip_guards = guarded.front() == kPcmGuard && guarded.back() == kPcmGuard;
        const bool clip_passed = clip_started &&
                                 status == echidna::audio::BufferProcessResult::kProcessed &&
                                 clipped_positive && clipped_negative && clip_guards;
        AddValidation(results,
                      "pcm16_clipping_matches_float_bounds",
                      "pcm16_bridge",
                      "amplified_sine",
                      clip_passed,
                      clip_passed ? "PCM16 bridge saturates amplified float output at both rails"
                                  : "PCM16 bridge did not saturate predictably at int16 rails",
                      {{"minimum_pcm", minimum != guarded.end() - 1 ? *minimum : 0.0},
                       {"maximum_pcm", maximum != guarded.end() - 1 ? *maximum : 0.0},
                       {"guards_ok", clip_guards ? 1.0 : 0.0}});
    }

    void ValidateFullChainFixtures(std::vector<ValidationResult> *results)
    {
        constexpr uint32_t sample_rate = 48000;
        constexpr uint32_t channels = 2;
        constexpr size_t frames = 24576;
        for (const std::string fixture : {"sine_sweep", "noise", "silence"})
        {
            const auto input = MakeFixture(fixture, frames, sample_rate, channels);
            const auto processed = ProcessStream(
                VoiceFxPreset(), ECH_DSP_QUALITY_LOW_LATENCY, sample_rate, channels, 256, input);
            const double delta = processed.ok ? MeanAbsDelta(input, processed.output) : 0.0;
            const double output_peak = processed.ok ? Peak(processed.output) : 0.0;
            const bool silence = fixture == "silence";
            const bool directional = silence ? output_peak < 1.0e-7 : delta > 1.0e-4;
            const bool passed = processed.ok && processed.guards_ok &&
                                AllFinite(processed.output) && directional &&
                                output_peak < 8.0;
            AddValidation(results,
                          "full_chain_" + fixture,
                          "voice_fx_chain",
                          fixture,
                          passed,
                          passed
                              ? "full configured chain is finite, bounded, and directionally active"
                              : "full chain failed activation, finiteness, or bounds check",
                          {{"mean_abs_delta", delta},
                           {"correlation",
                            processed.ok ? Correlation(input, processed.output) : 0.0},
                           {"input_rms", Rms(input, channels, 0, 0, frames)},
                           {"output_rms", processed.ok
                                              ? Rms(processed.output, channels, 0, 0, frames)
                                              : 0.0},
                           {"output_peak", output_peak}});
        }
    }

    std::vector<ValidationResult> RunValidations()
    {
        std::vector<ValidationResult> results;
        ValidateOutputObserver(&results);
        const bool version_ok = ech_dsp_api_get_version() == ECH_DSP_API_VERSION;
        AddValidation(&results,
                      "public_api_version",
                      "public_api",
                      "none",
                      version_ok,
                      version_ok ? "public ABI version matches the benchmark headers"
                                 : "loaded DSP ABI does not match benchmark headers",
                      {{"runtime_version", static_cast<double>(ech_dsp_api_get_version())},
                       {"header_version", static_cast<double>(ECH_DSP_API_VERSION)}});
        ValidateNeutralFixtures(&results);
        ValidateEq(&results);
        ValidateCompressor(&results);
        ValidateGate(&results);
        ValidatePitch(&results);
        ValidateFormant(&results);
        ValidateAutoTune(&results);
        ValidateReverb(&results);
        ValidateMix(&results);
        ValidateStereoIsolation(&results);
        ValidatePcm16Bridge(&results);
        ValidateFullChainFixtures(&results);
        return results;
    }

    double NearestRank(std::vector<double> sorted, double percentile)
    {
        if (sorted.empty())
        {
            return 0.0;
        }
        std::sort(sorted.begin(), sorted.end());
        const size_t rank = static_cast<size_t>(
            std::ceil(percentile * static_cast<double>(sorted.size())));
        return sorted[std::clamp<size_t>(rank, 1, sorted.size()) - 1];
    }

    LatencyStats Summarize(const std::vector<double> &latencies_us)
    {
        LatencyStats stats;
        if (latencies_us.empty())
        {
            return stats;
        }
        stats.mean_us = std::accumulate(latencies_us.begin(), latencies_us.end(), 0.0) /
                        static_cast<double>(latencies_us.size());
        stats.p50_us = NearestRank(latencies_us, 0.50);
        stats.p95_us = NearestRank(latencies_us, 0.95);
        stats.p99_us = NearestRank(latencies_us, 0.99);
        stats.max_us = *std::max_element(latencies_us.begin(), latencies_us.end());
        return stats;
    }

    PerformanceResult RunPerformanceScenario(const Profile &profile,
                                             AudioPath path,
                                             uint32_t sample_rate,
                                             uint32_t channels,
                                             size_t frames,
                                             uint32_t load_threads,
                                             const Options &options)
    {
        PerformanceResult result;
        result.profile = profile.id;
        result.path = path;
        result.sample_rate = sample_rate;
        result.channels = channels;
        result.frames = frames;
        result.load_threads = load_threads;
        result.warmups = options.warmups;
        result.samples = options.iterations;
        result.deadline_us = static_cast<double>(frames) * 1.0e6 / sample_rate;
        result.safety_limit_us = std::max(30000.0,
                                          options.safety_factor * result.deadline_us);
        std::ostringstream id;
        id << profile.id << '_' << PathName(path) << '_' << sample_rate << "hz_" << channels
           << "ch_" << frames << "f_load" << load_threads;
        result.scenario_id = id.str();

        const auto input_float = MakeFixture("multi_tone", frames, sample_rate, channels);
        std::vector<float> output_float(input_float.size(), 0.0f);
        const auto input_pcm = EncodePcm16(input_float);
        std::vector<int16_t> working_pcm(input_pcm.size(), 0);
        std::vector<int16_t> baseline_pcm(input_pcm.size(), 0);
        std::vector<float> input_scratch(input_float.size(), 0.0f);
        std::vector<float> output_scratch(input_float.size(), 0.0f);
        const void *observed_buffer = nullptr;
        size_t observed_bytes = 0;
        if (path == AudioPath::kFloat32)
        {
            observed_buffer = output_float.data();
            observed_bytes = output_float.size() * sizeof(float);
        }
        else if (profile.baseline)
        {
            observed_buffer = baseline_pcm.data();
            observed_bytes = baseline_pcm.size() * sizeof(int16_t);
        }
        else
        {
            observed_buffer = working_pcm.data();
            observed_bytes = working_pcm.size() * sizeof(int16_t);
        }

        if (!profile.baseline)
        {
            result.setup_ok = StartDsp(sample_rate,
                                       channels,
                                       profile.quality,
                                       profile.json,
                                       frames,
                                       &result.error);
            if (!result.setup_ok)
            {
                return result;
            }
        }
        else
        {
            result.setup_ok = true;
        }

        auto invoke = [&]() -> bool
        {
            if (profile.baseline)
            {
                if (path == AudioPath::kFloat32)
                {
                    std::memcpy(output_float.data(),
                                input_float.data(),
                                input_float.size() * sizeof(float));
                }
                else
                {
                    std::memcpy(baseline_pcm.data(),
                                input_pcm.data(),
                                input_pcm.size() * sizeof(int16_t));
                }
                return true;
            }
            if (path == AudioPath::kFloat32)
            {
                const auto status = ech_dsp_process_block(
                    input_float.data(), output_float.data(), frames);
                if (status != ECH_DSP_STATUS_OK)
                {
                    result.error = "ech_dsp_process_block returned " +
                                   std::to_string(status);
                    return false;
                }
                return true;
            }
            const auto status = echidna::audio::ProcessPcmBufferInPlace(
                working_pcm.data(),
                working_pcm.size() * sizeof(int16_t),
                echidna::audio::PcmFormat::kSigned16,
                sample_rate,
                channels,
                input_scratch.data(),
                output_scratch.data(),
                input_scratch.size(),
                ProcessPcmAdapter);
            if (status != echidna::audio::BufferProcessResult::kProcessed)
            {
                result.error = "ProcessPcmBufferInPlace returned " +
                               std::to_string(static_cast<int>(status));
                return false;
            }
            return true;
        };

        uint64_t scenario_checksum = kScenarioSeed;
        BackgroundLoad load(load_threads);
        for (size_t warmup = 0; warmup < options.warmups; ++warmup)
        {
            if (!profile.baseline && path == AudioPath::kPcm16)
            {
                std::copy(input_pcm.begin(), input_pcm.end(), working_pcm.begin());
            }
            if (!invoke())
            {
                ech_dsp_shutdown();
                return result;
            }
            DoNotOptimizeBuffer(observed_buffer, observed_bytes);
            scenario_checksum = MixChecksum(
                scenario_checksum,
                g_buffer_observer(observed_buffer, observed_bytes, warmup));
        }

        std::vector<double> latencies_us;
        latencies_us.reserve(options.iterations);
        for (size_t iteration = 0; iteration < options.iterations; ++iteration)
        {
            if (!profile.baseline && path == AudioPath::kPcm16)
            {
                std::copy(input_pcm.begin(), input_pcm.end(), working_pcm.begin());
            }
            const auto start = Clock::now();
            const bool ok = invoke();
            DoNotOptimizeBuffer(observed_buffer, observed_bytes);
            const auto end = Clock::now();
            if (!ok)
            {
                ech_dsp_shutdown();
                return result;
            }
            latencies_us.push_back(
                std::chrono::duration<double, std::micro>(end - start).count());
            std::atomic_signal_fence(std::memory_order_seq_cst);
            scenario_checksum = MixChecksum(
                scenario_checksum,
                g_buffer_observer(observed_buffer,
                                  observed_bytes,
                                  options.warmups + iteration));
        }
        const uint64_t load_checksum = load.Stop();
        g_published_checksum.fetch_xor(
            MixChecksum(scenario_checksum, load_checksum), std::memory_order_relaxed);
        if (!profile.baseline)
        {
            ech_dsp_shutdown();
        }
        if (path == AudioPath::kFloat32 && !AllFinite(output_float))
        {
            result.error = "float processing produced non-finite output";
            return result;
        }
        result.processing_ok = true;
        result.latency = Summarize(latencies_us);
        result.per_frame_us = result.latency.mean_us / static_cast<double>(frames);
        result.frames_per_second = result.latency.mean_us > 0.0
                                       ? static_cast<double>(frames) * 1.0e6 /
                                             result.latency.mean_us
                                       : 0.0;
        result.real_time_factor = result.latency.mean_us > 0.0
                                      ? result.deadline_us / result.latency.mean_us
                                      : 0.0;
        result.strict_deadline_met = result.latency.p99_us <= result.deadline_us;
        result.safety_gate_applies = load_threads == 0;
        result.safety_gate_passed = !result.safety_gate_applies ||
                                    result.latency.p99_us <= result.safety_limit_us;
        return result;
    }

    std::string ReferenceKey(const PerformanceResult &result, std::string_view profile)
    {
        std::ostringstream key;
        key << profile << '|' << PathName(result.path) << '|' << result.sample_rate << '|'
            << result.channels << '|' << result.frames << '|' << result.load_threads;
        return key.str();
    }

    std::string UnloadedKey(const PerformanceResult &result)
    {
        std::ostringstream key;
        key << result.profile << '|' << PathName(result.path) << '|' << result.sample_rate << '|'
            << result.channels << '|' << result.frames << "|0";
        return key.str();
    }

    double Ratio(double value, double reference)
    {
        return reference > 0.0 ? value / reference : 0.0;
    }

    void AddRatios(std::vector<PerformanceResult> *results)
    {
        std::map<std::string, const PerformanceResult *> by_key;
        for (const auto &result : *results)
        {
            by_key[ReferenceKey(result, result.profile)] = &result;
        }
        for (auto &result : *results)
        {
            const auto baseline_it = by_key.find(ReferenceKey(result, "memcpy_baseline"));
            if (baseline_it != by_key.end())
            {
                const auto &reference = *baseline_it->second;
                result.mean_vs_baseline_ratio =
                    Ratio(result.latency.mean_us, reference.latency.mean_us);
                result.p95_vs_baseline_ratio =
                    Ratio(result.latency.p95_us, reference.latency.p95_us);
                result.p99_vs_baseline_ratio =
                    Ratio(result.latency.p99_us, reference.latency.p99_us);
                result.mean_overhead_vs_baseline_us =
                    result.latency.mean_us - reference.latency.mean_us;
                result.p95_overhead_vs_baseline_us =
                    result.latency.p95_us - reference.latency.p95_us;
                result.p99_overhead_vs_baseline_us =
                    result.latency.p99_us - reference.latency.p99_us;
            }
            const auto neutral_it = by_key.find(ReferenceKey(result, "neutral_dsp"));
            if (neutral_it != by_key.end())
            {
                const auto &reference = *neutral_it->second;
                result.mean_vs_neutral_ratio =
                    Ratio(result.latency.mean_us, reference.latency.mean_us);
                result.p95_vs_neutral_ratio =
                    Ratio(result.latency.p95_us, reference.latency.p95_us);
                result.p99_vs_neutral_ratio =
                    Ratio(result.latency.p99_us, reference.latency.p99_us);
                result.mean_overhead_vs_neutral_us =
                    result.latency.mean_us - reference.latency.mean_us;
                result.p95_overhead_vs_neutral_us =
                    result.latency.p95_us - reference.latency.p95_us;
                result.p99_overhead_vs_neutral_us =
                    result.latency.p99_us - reference.latency.p99_us;
            }
            if (result.load_threads > 0)
            {
                const auto unloaded_it = by_key.find(UnloadedKey(result));
                if (unloaded_it != by_key.end())
                {
                    const auto &reference = *unloaded_it->second;
                    result.p95_loaded_vs_unloaded_ratio =
                        Ratio(result.latency.p95_us, reference.latency.p95_us);
                    result.p99_loaded_vs_unloaded_ratio =
                        Ratio(result.latency.p99_us, reference.latency.p99_us);
                }
            }
        }
    }

    std::vector<PerformanceResult> RunPerformance(const Options &options)
    {
        const std::vector<Profile> core_profiles{
            {"memcpy_baseline", "", ECH_DSP_QUALITY_LOW_LATENCY, true},
            {"neutral_dsp", NeutralPreset(), ECH_DSP_QUALITY_LOW_LATENCY, false},
            {"voice_fx_ll", VoiceFxPreset(), ECH_DSP_QUALITY_LOW_LATENCY, false},
        };
        const Profile hq_profile{
            "autotune_hq", HqAutoTunePreset(), ECH_DSP_QUALITY_HIGH, false};
        const std::vector<uint32_t> sample_rates = options.quick
                                                       ? std::vector<uint32_t>{48000}
                                                       : std::vector<uint32_t>{16000,
                                                                               44100,
                                                                               48000,
                                                                               96000};
        const std::vector<size_t> blocks = options.quick
                                               ? std::vector<size_t>{128, 480}
                                               : std::vector<size_t>{64,
                                                                     128,
                                                                     240,
                                                                     256,
                                                                     480,
                                                                     960};
        std::vector<uint32_t> loads{0};
        if (options.load_threads > 0)
        {
            loads.push_back(options.load_threads);
        }
        std::vector<PerformanceResult> results;
        for (uint32_t load : loads)
        {
            for (uint32_t sample_rate : sample_rates)
            {
                for (uint32_t channels : {1u, 2u})
                {
                    for (size_t frames : blocks)
                    {
                        for (AudioPath path : {AudioPath::kFloat32, AudioPath::kPcm16})
                        {
                            for (const auto &profile : core_profiles)
                            {
                                results.push_back(RunPerformanceScenario(profile,
                                                                         path,
                                                                         sample_rate,
                                                                         channels,
                                                                         frames,
                                                                         load,
                                                                         options));
                            }
                        }
                    }
                }
            }
            for (uint32_t channels : {1u, 2u})
            {
                for (size_t frames : {size_t{480}, size_t{960}})
                {
                    for (AudioPath path : {AudioPath::kFloat32, AudioPath::kPcm16})
                    {
                        results.push_back(RunPerformanceScenario(hq_profile,
                                                                 path,
                                                                 48000,
                                                                 channels,
                                                                 frames,
                                                                 load,
                                                                 options));
                    }
                }
            }
        }
        AddRatios(&results);
        return results;
    }

    std::string JsonEscape(std::string_view input)
    {
        std::ostringstream output;
        for (char character : input)
        {
            switch (character)
            {
            case '\\':
                output << "\\\\";
                break;
            case '"':
                output << "\\\"";
                break;
            case '\n':
                output << "\\n";
                break;
            case '\r':
                output << "\\r";
                break;
            case '\t':
                output << "\\t";
                break;
            default:
                if (static_cast<unsigned char>(character) < 0x20)
                {
                    output << "\\u" << std::hex << std::setw(4) << std::setfill('0')
                           << static_cast<int>(static_cast<unsigned char>(character))
                           << std::dec << std::setfill(' ');
                }
                else
                {
                    output << character;
                }
            }
        }
        return output.str();
    }

    std::string UtcTimestamp()
    {
        const std::time_t now = std::time(nullptr);
        std::tm utc{};
#if defined(_WIN32)
        gmtime_s(&utc, &now);
#else
        gmtime_r(&now, &utc);
#endif
        std::ostringstream output;
        output << std::put_time(&utc, "%Y-%m-%dT%H:%M:%SZ");
        return output.str();
    }

    std::string CompilerDescription()
    {
#if defined(__clang__)
        return std::string("Clang ") + __clang_version__;
#elif defined(__GNUC__)
        return std::string("GCC ") + __VERSION__;
#elif defined(_MSC_VER)
        return std::string("MSVC ") + std::to_string(_MSC_VER);
#else
        return "unknown";
#endif
    }

    std::string OperatingSystem()
    {
#if defined(_WIN32)
        return "Windows";
#elif defined(__ANDROID__)
        return "Android";
#elif defined(__linux__)
        return "Linux";
#elif defined(__APPLE__)
        return "macOS";
#else
        return "unknown";
#endif
    }

    std::string CpuDescription()
    {
#if defined(_WIN32)
        if (const char *identifier = std::getenv("PROCESSOR_IDENTIFIER"))
        {
            return identifier;
        }
#elif defined(__linux__)
        std::ifstream cpuinfo("/proc/cpuinfo");
        std::string line;
        while (std::getline(cpuinfo, line))
        {
            const auto colon = line.find(':');
            if (colon != std::string::npos &&
                (line.starts_with("model name") || line.starts_with("Hardware")))
            {
                return line.substr(colon + 2);
            }
        }
#endif
        return "unknown";
    }

    bool WriteJson(const std::filesystem::path &path,
                   const Options &options,
                   const std::vector<ValidationResult> &validations,
                   const std::vector<PerformanceResult> &performance)
    {
        std::ofstream output(path);
        if (!output)
        {
            return false;
        }
        output << std::setprecision(10);
        output << "{\n";
        output << "  \"schema_version\": \"1.0.0\",\n";
        output << "  \"generated_utc\": \"" << UtcTimestamp() << "\",\n";
        output << "  \"scope\": \"host processing benchmark; not device or end-to-end latency\",\n";
        output << "  \"methodology\": {\n";
        output << "    \"mode\": \"" << (options.quick ? "quick" : "full") << "\",\n";
        output << "    \"warmups_per_scenario\": " << options.warmups << ",\n";
        output << "    \"measured_iterations_per_scenario\": " << options.iterations << ",\n";
        output << "    \"clock\": \"std::chrono::steady_clock\",\n";
        output << "    \"public_api_path\": \"initialize -> update_config -> "
                  "prepare_realtime -> process_block\",\n";
        output << "    \"clock_is_steady\": " << (Clock::is_steady ? "true" : "false") << ",\n";
        output << "    \"clock_tick_ns\": "
               << (1.0e9 * static_cast<double>(Clock::period::num) /
                   static_cast<double>(Clock::period::den))
               << ",\n";
        output << "    \"percentile_method\": \"nearest-rank ceil(p*n), no interpolation\",\n";
        output << "    \"scenario_seed\": " << kScenarioSeed << ",\n";
        output << "    \"allocations_config_and_pcm_reset_outside_timed_region\": true,\n";
        output << "    \"warmups_run_under_configured_load\": true,\n";
        output << "    \"compiler_barrier_before_end_timestamp\": true,\n";
        output << "    \"output_checksum_after_end_timestamp\": true,\n";
        output << "    \"observable_checksum\": "
               << g_published_checksum.load(std::memory_order_relaxed) << ",\n";
        output << "    \"strict_deadline_uses\": \"p99 <= buffer duration\",\n";
        output << "    \"safety_gate_uses\": "
                  "\"unloaded p99 <= max(30ms, safety_factor * buffer duration)\",\n";
        output << "    \"safety_factor\": " << options.safety_factor << "\n";
        output << "  },\n";
        output << "  \"environment\": {\n";
        output << "    \"os\": \"" << JsonEscape(OperatingSystem()) << "\",\n";
        output << "    \"cpu\": \"" << JsonEscape(CpuDescription()) << "\",\n";
        output << "    \"logical_cpu_count\": " << std::thread::hardware_concurrency() << ",\n";
        output << "    \"compiler\": \"" << JsonEscape(CompilerDescription()) << "\",\n";
        output << "    \"build_type\": \"" << JsonEscape(ECHIDNA_PERF_BUILD_TYPE) << "\",\n";
        output << "    \"cxx_flags\": \"" << JsonEscape(ECHIDNA_PERF_CXX_FLAGS) << "\"\n";
        output << "  },\n";
        output << "  \"effect_inventory\": {\n";
        output << "    \"available\": [\"gate\", \"eq\", \"compressor\", \"pitch\", "
                  "\"formant\", \"autotune\", \"reverb\", \"mix\"],\n";
        output << "    \"unavailable\": [\"limiter\", \"spatial\"],\n";
        output << "    \"note\": \"Unavailable stages are not fabricated; clipping is "
                  "measured at the PCM16 bridge.\"\n";
        output << "  },\n";
        output << "  \"validations\": [\n";
        for (size_t i = 0; i < validations.size(); ++i)
        {
            const auto &validation = validations[i];
            output << "    {\n";
            output << "      \"id\": \"" << JsonEscape(validation.id) << "\",\n";
            output << "      \"effect\": \"" << JsonEscape(validation.effect) << "\",\n";
            output << "      \"fixture\": \"" << JsonEscape(validation.fixture) << "\",\n";
            output << "      \"passed\": " << (validation.passed ? "true" : "false") << ",\n";
            output << "      \"detail\": \"" << JsonEscape(validation.detail) << "\",\n";
            output << "      \"metrics\": {";
            size_t metric_index = 0;
            for (const auto &[name, value] : validation.metrics)
            {
                output << (metric_index++ == 0 ? "" : ", ") << "\"" << JsonEscape(name)
                       << "\": ";
                if (std::isfinite(value))
                {
                    output << value;
                }
                else
                {
                    output << "null";
                }
            }
            output << "}\n";
            output << "    }" << (i + 1 == validations.size() ? "\n" : ",\n");
        }
        output << "  ],\n";
        output << "  \"performance\": [\n";
        for (size_t i = 0; i < performance.size(); ++i)
        {
            const auto &item = performance[i];
            output << "    {\n";
            output << "      \"scenario_id\": \"" << JsonEscape(item.scenario_id) << "\",\n";
            output << "      \"profile\": \"" << JsonEscape(item.profile) << "\",\n";
            output << "      \"path\": \"" << PathName(item.path) << "\",\n";
            output << "      \"fixture\": \"" << item.fixture << "\",\n";
            output << "      \"sample_rate_hz\": " << item.sample_rate << ",\n";
            output << "      \"channels\": " << item.channels << ",\n";
            output << "      \"frames_per_buffer\": " << item.frames << ",\n";
            output << "      \"background_load_threads\": " << item.load_threads << ",\n";
            output << "      \"warmup_count\": " << item.warmups << ",\n";
            output << "      \"sample_count\": " << item.samples << ",\n";
            output << "      \"scenario_seed\": " << item.seed << ",\n";
            output << "      \"setup_ok\": " << (item.setup_ok ? "true" : "false") << ",\n";
            output << "      \"processing_ok\": "
                   << (item.processing_ok ? "true" : "false") << ",\n";
            output << "      \"error\": \"" << JsonEscape(item.error) << "\",\n";
            output << "      \"latency_us\": {\"mean\": " << item.latency.mean_us
                   << ", \"p50\": " << item.latency.p50_us << ", \"p95\": "
                   << item.latency.p95_us << ", \"p99\": " << item.latency.p99_us
                   << ", \"max\": " << item.latency.max_us << "},\n";
            output << "      \"per_frame_us\": " << item.per_frame_us << ",\n";
            output << "      \"frames_per_second\": " << item.frames_per_second << ",\n";
            output << "      \"real_time_factor\": " << item.real_time_factor << ",\n";
            output << "      \"deadline_us\": " << item.deadline_us << ",\n";
            output << "      \"strict_deadline_met\": "
                   << (item.strict_deadline_met ? "true" : "false") << ",\n";
            output << "      \"safety_limit_us\": " << item.safety_limit_us << ",\n";
            output << "      \"safety_gate_applies\": "
                   << (item.safety_gate_applies ? "true" : "false") << ",\n";
            output << "      \"safety_gate_passed\": "
                   << (item.safety_gate_passed ? "true" : "false") << ",\n";
            output << "      \"ratios\": {\n";
            output << "        \"mean_vs_baseline\": " << item.mean_vs_baseline_ratio << ",\n";
            output << "        \"p95_vs_baseline\": " << item.p95_vs_baseline_ratio << ",\n";
            output << "        \"p99_vs_baseline\": " << item.p99_vs_baseline_ratio << ",\n";
            output << "        \"mean_overhead_vs_baseline_us\": "
                   << item.mean_overhead_vs_baseline_us << ",\n";
            output << "        \"p95_overhead_vs_baseline_us\": "
                   << item.p95_overhead_vs_baseline_us << ",\n";
            output << "        \"p99_overhead_vs_baseline_us\": "
                   << item.p99_overhead_vs_baseline_us << ",\n";
            output << "        \"mean_vs_neutral\": " << item.mean_vs_neutral_ratio << ",\n";
            output << "        \"p95_vs_neutral\": " << item.p95_vs_neutral_ratio << ",\n";
            output << "        \"p99_vs_neutral\": " << item.p99_vs_neutral_ratio << ",\n";
            output << "        \"mean_overhead_vs_neutral_us\": "
                   << item.mean_overhead_vs_neutral_us << ",\n";
            output << "        \"p95_overhead_vs_neutral_us\": "
                   << item.p95_overhead_vs_neutral_us << ",\n";
            output << "        \"p99_overhead_vs_neutral_us\": "
                   << item.p99_overhead_vs_neutral_us << ",\n";
            output << "        \"p95_loaded_vs_unloaded\": "
                   << item.p95_loaded_vs_unloaded_ratio << ",\n";
            output << "        \"p99_loaded_vs_unloaded\": "
                   << item.p99_loaded_vs_unloaded_ratio << "\n";
            output << "      }\n";
            output << "    }" << (i + 1 == performance.size() ? "\n" : ",\n");
        }
        output << "  ]\n";
        output << "}\n";
        return output.good();
    }

    bool WriteMarkdown(const std::filesystem::path &path,
                       const Options &options,
                       const std::vector<ValidationResult> &validations,
                       const std::vector<PerformanceResult> &performance)
    {
        std::ofstream output(path);
        if (!output)
        {
            return false;
        }
        const size_t failed_validations = static_cast<size_t>(std::count_if(
            validations.begin(), validations.end(), [](const auto &item)
            { return !item.passed; }));
        const size_t missed_deadlines = static_cast<size_t>(std::count_if(
            performance.begin(), performance.end(), [](const auto &item)
            { return item.processing_ok && !item.strict_deadline_met; }));
        const size_t failed_safety = static_cast<size_t>(std::count_if(
            performance.begin(), performance.end(), [](const auto &item)
            { return !item.safety_gate_passed; }));

        output << "# Echidna audio pipeline performance report\n\n";
        output << "Generated: " << UtcTimestamp() << "\n\n";
        output << "> Host processing measurements only. These are not Android device callback, "
                  "HAL, acoustic,\n> or end-to-end latency measurements.\n\n";
        output << "## Summary\n\n";
        output << "- Mode: " << (options.quick ? "quick" : "full") << "\n";
        output << "- Functional checks: " << validations.size() - failed_validations << "/"
               << validations.size() << " passed\n";
        output << "- Performance scenarios: " << performance.size() << "\n";
        output << "- Strict p99 deadline misses: " << missed_deadlines << "\n";
        output << "- Catastrophic safety-gate failures: " << failed_safety << "\n";
        output << "- Warmups / measured samples per scenario: " << options.warmups << " / "
               << options.iterations << "\n";
        output << "- Percentiles: nearest-rank `ceil(p*n)` over per-buffer "
                  "`steady_clock` samples\n";
        output << "- Environment: " << OperatingSystem() << ", " << CpuDescription() << ", "
               << CompilerDescription() << ", build type " << ECHIDNA_PERF_BUILD_TYPE << "\n\n";

        output << "## Functional DSP activation\n\n";
        output << "| Check | Effect | Fixture | Result | Key metrics |\n";
        output << "| --- | --- | --- | --- | --- |\n";
        for (const auto &item : validations)
        {
            output << "| " << item.id << " | " << item.effect << " | " << item.fixture
                   << " | " << (item.passed ? "PASS" : "FAIL") << " | ";
            size_t metric_index = 0;
            for (const auto &[name, value] : item.metrics)
            {
                if (metric_index++ > 0)
                {
                    output << "; ";
                }
                output << name << '=' << std::setprecision(5) << value;
                if (metric_index >= 4)
                {
                    break;
                }
            }
            output << " |\n";
        }
        output << "\nNo limiter or spatial stage exists in the current engine. The report does "
                  "not fabricate those\nchecks; PCM16 saturation and stereo isolation are "
                  "covered explicitly.\n\n";

        output << "## Performance\n\n";
        output << "The table selects the highest p99-to-deadline utilization for each "
                  "profile, path, and\nload level. Every scenario remains available in the JSON "
                  "report.\n\n";
        std::map<std::string, const PerformanceResult *> worst_by_group;
        for (const auto &item : performance)
        {
            if (!item.processing_ok || item.deadline_us <= 0.0)
            {
                continue;
            }
            std::ostringstream key;
            key << item.profile << '|' << PathName(item.path) << '|' << item.load_threads;
            const auto current = worst_by_group.find(key.str());
            const double utilization = item.latency.p99_us / item.deadline_us;
            const double current_utilization =
                current == worst_by_group.end()
                    ? -1.0
                    : current->second->latency.p99_us / current->second->deadline_us;
            if (utilization > current_utilization)
            {
                worst_by_group[key.str()] = &item;
            }
        }
        output << "| Profile | Path | Rate | Ch | Frames | Load | p50 us | p95 us | p99 us | "
                  "RTF | p99/base | p99/neutral | Deadline |\n";
        output << "| --- | --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | "
                  "---: | ---: | --- |\n";
        for (const auto &[key, item_pointer] : worst_by_group)
        {
            (void)key;
            const auto &item = *item_pointer;
            output << "| " << item.profile << " | " << PathName(item.path) << " | "
                   << item.sample_rate << " | " << item.channels << " | " << item.frames
                   << " | " << item.load_threads << " | " << std::fixed << std::setprecision(3)
                   << item.latency.p50_us << " | " << item.latency.p95_us << " | "
                   << item.latency.p99_us << " | " << item.real_time_factor << " | "
                   << item.p99_vs_baseline_ratio << " | " << item.p99_vs_neutral_ratio
                   << " | " << (item.strict_deadline_met ? "PASS" : "MISS") << " |\n";
        }
        output << "\nThe strict deadline column remains visible but is descriptive. The regression "
                  "gate only fails\nunloaded scenarios whose p99 exceeds `max(30 ms, "
               << options.safety_factor << " x buffer duration)`. Loaded scenarios are reported "
                                           "for comparison and are non-gating.\n";
        return output.good();
    }

    std::optional<size_t> ParseSize(std::string_view value, bool allow_zero = false)
    {
        try
        {
            size_t consumed = 0;
            const auto parsed = std::stoull(std::string(value), &consumed);
            if (consumed != value.size() || (!allow_zero && parsed == 0))
            {
                return std::nullopt;
            }
            return static_cast<size_t>(parsed);
        }
        catch (...)
        {
            return std::nullopt;
        }
    }

    bool ParseOptions(int argc, char **argv, Options *options)
    {
        for (int i = 1; i < argc; ++i)
        {
            const std::string_view argument(argv[i]);
            if (argument == "--quick")
            {
                options->quick = true;
                options->warmups = 50;
                options->iterations = 300;
            }
            else if (argument == "--warmups" || argument == "--iterations" ||
                     argument == "--load-threads" || argument == "--output-dir" ||
                     argument == "--safety-factor")
            {
                if (++i >= argc)
                {
                    std::cerr << "Missing value for " << argument << '\n';
                    return false;
                }
                const std::string_view value(argv[i]);
                if (argument == "--output-dir")
                {
                    options->output_dir = std::filesystem::path(value);
                    continue;
                }
                if (argument == "--safety-factor")
                {
                    try
                    {
                        options->safety_factor = std::stod(std::string(value));
                    }
                    catch (...)
                    {
                        return false;
                    }
                    if (!std::isfinite(options->safety_factor) ||
                        options->safety_factor < 1.0)
                    {
                        std::cerr << "--safety-factor must be finite and >= 1\n";
                        return false;
                    }
                    continue;
                }
                const bool allow_zero = argument == "--load-threads";
                const auto parsed = ParseSize(value, allow_zero);
                if (!parsed)
                {
                    std::cerr << "Invalid positive integer for " << argument << '\n';
                    return false;
                }
                if (argument == "--warmups")
                {
                    options->warmups = *parsed;
                }
                else if (argument == "--iterations")
                {
                    options->iterations = *parsed;
                }
                else
                {
                    if (*parsed > 64)
                    {
                        std::cerr << "--load-threads must be <= 64\n";
                        return false;
                    }
                    options->load_threads = static_cast<uint32_t>(*parsed);
                }
            }
            else if (argument == "--help")
            {
                std::cout << "Usage: audio_pipeline_benchmark [--quick] [--warmups N] "
                             "[--iterations N]\n"
                             "       [--load-threads N] [--safety-factor N] "
                             "[--output-dir PATH]\n";
                return false;
            }
            else
            {
                std::cerr << "Unknown argument: " << argument << '\n';
                return false;
            }
        }
        return true;
    }

} // namespace

int main(int argc, char **argv)
{
    Options options;
    if (!ParseOptions(argc, argv, &options))
    {
        return 64;
    }
    std::error_code filesystem_error;
    std::filesystem::create_directories(options.output_dir, filesystem_error);
    if (filesystem_error)
    {
        std::cerr << "Cannot create output directory: " << filesystem_error.message() << '\n';
        return 74;
    }

    std::cout << "Running functional audio validation...\n";
    const auto validations = RunValidations();
    std::cout << "Running " << (options.quick ? "quick" : "full")
              << " performance matrix...\n";
    const auto performance = RunPerformance(options);

    const auto json_path = options.output_dir / "audio_pipeline_results.json";
    const auto markdown_path = options.output_dir / "audio_pipeline_results.md";
    if (!WriteJson(json_path, options, validations, performance) ||
        !WriteMarkdown(markdown_path, options, validations, performance))
    {
        std::cerr << "Failed to write benchmark reports\n";
        return 74;
    }

    const size_t failed_validations = static_cast<size_t>(std::count_if(
        validations.begin(), validations.end(), [](const auto &item)
        { return !item.passed; }));
    const size_t failed_processing = static_cast<size_t>(std::count_if(
        performance.begin(), performance.end(), [](const auto &item)
        { return !item.setup_ok || !item.processing_ok; }));
    const size_t failed_safety = static_cast<size_t>(std::count_if(
        performance.begin(), performance.end(), [](const auto &item)
        { return !item.safety_gate_passed; }));

    std::cout << "Reports: " << json_path << " and " << markdown_path << '\n';
    std::cout << "Functional failures: " << failed_validations
              << ", processing failures: " << failed_processing
              << ", safety-gate failures: " << failed_safety << '\n';
    std::cout << "Observable checksum: "
              << g_published_checksum.load(std::memory_order_relaxed) << '\n';
    return (failed_validations == 0 && failed_processing == 0 && failed_safety == 0) ? 0 : 2;
}
