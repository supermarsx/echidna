#include <array>
#include <atomic>
#include <cerrno>
#include <cmath>
#include <cstdint>
#include <limits>
#include <thread>
#include <vector>

#include "effect_context.h"
#include "test_support.h"

using echidna::dsp::config::PresetDefinition;
using echidna::effects::legacy::EffectContext;

namespace
{
    PresetDefinition GainPreset(float gain_db)
    {
        PresetDefinition preset;
        preset.name = "legacy-effect-test";
        preset.mix.params.dry_wet = 50.0f;
        preset.mix.params.output_gain_db = gain_db;
        return preset;
    }

    int Configure(EffectContext &context, const effect_config_t &config)
    {
        CHECK_TRUE(context.Initialize() == 0);
        CHECK_TRUE(context.SetConfig(config) == 0);
        CHECK_TRUE(!context.plugin_directory_scanned());
        return 0;
    }

    int TestNeutralAndSafety()
    {
        EffectContext context(101, 9);
        CHECK_TRUE(Configure(context, MakeEffectConfig()) == 0);
        CHECK_TRUE(context.session_id() == 101 && context.io_id() == 9);
        auto neutral_preset = GainPreset(0.0f);
        CHECK_TRUE(context.Enable() == -EPERM);
        CHECK_TRUE(context.SetPolicyPreset(true, &neutral_preset) == 0);
        CHECK_TRUE(context.Enable() == 0);
        context.RevokeAuthorization();

        std::array<float, 8> input{-0.8f, -0.4f, -0.1f, 0.0f,
                                   0.1f, 0.4f, 0.7f, 0.9f};
        std::array<float, 8> output{};
        audio_buffer_t in{};
        in.frameCount = input.size();
        in.f32 = input.data();
        audio_buffer_t out{};
        out.frameCount = output.size();
        out.f32 = output.data();
        CHECK_TRUE(context.Process(&in, &out) == 0);
        CHECK_TRUE(input == output);

        input[0] = std::numeric_limits<float>::quiet_NaN();
        input[1] = std::numeric_limits<float>::infinity();
        input[2] = 2.0f;
        CHECK_TRUE(context.Process(&in, &out) == 0);
        for (float value : output)
        {
            CHECK_TRUE(std::isfinite(value));
            CHECK_TRUE(value >= -1.0f && value <= 1.0f);
        }
        const auto telemetry = context.telemetry();
        CHECK_TRUE(telemetry.process_calls == 2);
        CHECK_TRUE(telemetry.bypass_calls == 2);
        CHECK_TRUE(telemetry.processed_calls == 0);
        CHECK_TRUE(telemetry.sanitized_samples >= 3);
        return 0;
    }

    int TestRealDspAndPolicyBypass()
    {
        EffectContext context(102, 10);
        CHECK_TRUE(Configure(context, MakeEffectConfig()) == 0);
        auto preset = GainPreset(-6.0f);
        CHECK_TRUE(context.SetPolicyPreset(true, &preset) == 0);
        CHECK_TRUE(context.Enable() == 0);
        context.RevokeAuthorization();

        std::vector<float> input(256);
        std::vector<float> output(256, 0.0f);
        for (size_t index = 0; index < input.size(); ++index)
        {
            input[index] = 0.25f * std::sin(static_cast<float>(index) * 0.09f);
        }
        audio_buffer_t in{input.size(), {.f32 = input.data()}};
        audio_buffer_t out{output.size(), {.f32 = output.data()}};
        CHECK_TRUE(context.Process(&in, &out) == 0);
        CHECK_TRUE(input == output);
        CHECK_TRUE(context.Disable() == 0);

        CHECK_TRUE(context.SetPolicyPreset(true, &preset) == 0);
        CHECK_TRUE(context.Enable() == 0);
        CHECK_TRUE(context.Process(&in, &out) == 0);
        double difference = 0.0;
        double input_energy = 0.0;
        double output_energy = 0.0;
        for (size_t index = 0; index < input.size(); ++index)
        {
            CHECK_TRUE(std::isfinite(output[index]));
            difference += std::abs(input[index] - output[index]);
            input_energy += input[index] * input[index];
            output_energy += output[index] * output[index];
        }
        CHECK_TRUE(difference > 0.1);
        const double ratio = std::sqrt(output_energy / input_energy);
        CHECK_TRUE(ratio > 0.48 && ratio < 0.53);
        const auto telemetry = context.telemetry();
        CHECK_TRUE(telemetry.processed_calls == 1);
        CHECK_TRUE(telemetry.bypass_calls == 1);
        CHECK_TRUE(telemetry.dsp_failures == 0);
        return 0;
    }

    int TestPcm16InPlaceAndAccumulate()
    {
        auto pcm_config = MakeEffectConfig(44100,
                                           AUDIO_CHANNEL_IN_STEREO,
                                           AUDIO_FORMAT_PCM_16_BIT);
        EffectContext neutral(103, 11);
        CHECK_TRUE(Configure(neutral, pcm_config) == 0);
        auto neutral_preset = GainPreset(0.0f);
        CHECK_TRUE(neutral.SetPolicyPreset(true, &neutral_preset) == 0);
        CHECK_TRUE(neutral.Enable() == 0);
        neutral.RevokeAuthorization();
        std::array<int16_t, 8> samples{-32768, -12000, -1, 0, 1, 9000, 16000, 32767};
        const auto original = samples;
        audio_buffer_t in{samples.size() / 2, {.s16 = samples.data()}};
        audio_buffer_t out{samples.size() / 2, {.s16 = samples.data()}};
        CHECK_TRUE(neutral.Process(&in, &out) == 0);
        CHECK_TRUE(samples == original);

        EffectContext active(104, 12);
        CHECK_TRUE(Configure(active, pcm_config) == 0);
        auto preset = GainPreset(-12.0f);
        CHECK_TRUE(active.SetPolicyPreset(true, &preset) == 0);
        CHECK_TRUE(active.Enable() == 0);
        std::array<int16_t, 8> processed{};
        audio_buffer_t processed_out{processed.size() / 2, {.s16 = processed.data()}};
        CHECK_TRUE(active.Process(&in, &processed_out) == 0);
        CHECK_TRUE(processed != original);
        for (size_t index = 0; index < processed.size(); ++index)
        {
            CHECK_TRUE(std::abs(static_cast<int32_t>(processed[index])) <=
                       std::abs(static_cast<int32_t>(original[index])) + 1);
        }

        auto accumulate_config = MakeEffectConfig(48000,
                                                  AUDIO_CHANNEL_IN_MONO,
                                                  AUDIO_FORMAT_PCM_FLOAT,
                                                  EFFECT_BUFFER_ACCESS_ACCUMULATE);
        EffectContext accumulate(105, 13);
        CHECK_TRUE(Configure(accumulate, accumulate_config) == 0);
        CHECK_TRUE(accumulate.SetPolicyPreset(true, &neutral_preset) == 0);
        CHECK_TRUE(accumulate.Enable() == 0);
        accumulate.RevokeAuthorization();
        std::array<float, 2> add_input{0.1f, -0.2f};
        std::array<float, 2> add_output{0.2f, 0.3f};
        audio_buffer_t add_in{2, {.f32 = add_input.data()}};
        audio_buffer_t add_out{2, {.f32 = add_output.data()}};
        CHECK_TRUE(accumulate.Process(&add_in, &add_out) == 0);
        CHECK_TRUE(std::abs(add_output[0] - 0.3f) < 1e-6f);
        CHECK_TRUE(std::abs(add_output[1] - 0.1f) < 1e-6f);
        return 0;
    }

    int TestConfigAndBounds()
    {
        EffectContext context(106, 14);
        CHECK_TRUE(context.Initialize() == 0);
        auto config = MakeEffectConfig();

        auto invalid = config;
        invalid.inputCfg.samplingRate = 7999;
        invalid.outputCfg.samplingRate = 7999;
        CHECK_TRUE(context.SetConfig(invalid) == -EINVAL);
        invalid = config;
        invalid.outputCfg.samplingRate = 44100;
        CHECK_TRUE(context.SetConfig(invalid) == -EINVAL);
        invalid = config;
        invalid.inputCfg.channels = 0x3;
        invalid.outputCfg.channels = 0x3;
        CHECK_TRUE(context.SetConfig(invalid) == -EINVAL);
        invalid = config;
        invalid.outputCfg.format = AUDIO_FORMAT_PCM_16_BIT;
        CHECK_TRUE(context.SetConfig(invalid) == -EINVAL);
        invalid = config;
        invalid.inputCfg.mask &= ~EFFECT_CONFIG_FORMAT;
        CHECK_TRUE(context.SetConfig(invalid) == -EINVAL);
        invalid = config;
        invalid.outputCfg.accessMode = EFFECT_BUFFER_ACCESS_READ;
        CHECK_TRUE(context.SetConfig(invalid) == -EINVAL);
        CHECK_TRUE(context.SetConfig(config) == 0);
        auto neutral_preset = GainPreset(0.0f);
        CHECK_TRUE(context.SetPolicyPreset(true, &neutral_preset) == 0);
        CHECK_TRUE(context.Enable() == 0);
        context.RevokeAuthorization();

        constexpr float kCanary = 12345.0f;
        constexpr size_t kFrames = 64;
        std::vector<float> input(kFrames + 2, kCanary);
        std::vector<float> output(kFrames + 2, kCanary);
        for (size_t index = 1; index <= kFrames; ++index)
        {
            input[index] = 0.1f;
            output[index] = 0.0f;
        }
        audio_buffer_t in{kFrames, {.f32 = input.data() + 1}};
        audio_buffer_t out{kFrames, {.f32 = output.data() + 1}};
        CHECK_TRUE(context.Process(&in, &out) == 0);
        CHECK_TRUE(input.front() == kCanary && input.back() == kCanary);
        CHECK_TRUE(output.front() == kCanary && output.back() == kCanary);

        in.frameCount = echidna::effects::legacy::kMaxFramesPerProcess + 1;
        out.frameCount = in.frameCount;
        const auto before = output;
        CHECK_TRUE(context.Process(&in, &out) == -EINVAL);
        CHECK_TRUE(output == before);
        in.frameCount = kFrames;
        out.frameCount = kFrames - 1;
        CHECK_TRUE(context.Process(&in, &out) == -EINVAL);
        CHECK_TRUE(context.Process(nullptr, &out) == -EINVAL);
        CHECK_TRUE(context.telemetry().invalid_calls == 3);
        return 0;
    }

    int TestIndependentConcurrentSessions()
    {
        EffectContext first(201, 21);
        EffectContext second(202, 22);
        const auto config = MakeEffectConfig();
        CHECK_TRUE(Configure(first, config) == 0);
        CHECK_TRUE(Configure(second, config) == 0);
        auto first_preset = GainPreset(-6.0f);
        auto second_preset = GainPreset(-12.0f);
        CHECK_TRUE(first.SetPolicyPreset(true, &first_preset) == 0);
        CHECK_TRUE(second.SetPolicyPreset(true, &second_preset) == 0);
        CHECK_TRUE(first.Enable() == 0 && second.Enable() == 0);

        std::atomic<bool> ok{true};
        auto run = [&ok](EffectContext &context, float expected)
        {
            std::array<float, 64> input{};
            std::array<float, 64> output{};
            input.fill(0.2f);
            audio_buffer_t in{input.size(), {.f32 = input.data()}};
            audio_buffer_t out{output.size(), {.f32 = output.data()}};
            for (size_t iteration = 0; iteration < 200; ++iteration)
            {
                if (context.Process(&in, &out) != 0 ||
                    std::abs(output[0] - expected) > 0.005f)
                {
                    ok.store(false, std::memory_order_relaxed);
                    return;
                }
            }
        };
        std::thread first_thread(run, std::ref(first), 0.2f * 0.501187f);
        std::thread second_thread(run, std::ref(second), 0.2f * 0.251189f);
        first_thread.join();
        second_thread.join();
        CHECK_TRUE(ok.load(std::memory_order_relaxed));
        CHECK_TRUE(first.telemetry().processed_calls == 200);
        CHECK_TRUE(second.telemetry().processed_calls == 200);
        return 0;
    }
} // namespace

int main()
{
    CHECK_TRUE(TestNeutralAndSafety() == 0);
    CHECK_TRUE(TestRealDspAndPolicyBypass() == 0);
    CHECK_TRUE(TestPcm16InPlaceAndAccumulate() == 0);
    CHECK_TRUE(TestConfigAndBounds() == 0);
    CHECK_TRUE(TestIndependentConcurrentSessions() == 0);
    return 0;
}
