/**
 * @file dsp_quality_test.cpp
 * @brief §10 DSP correctness / quality tests at the engine (libech_dsp) level.
 *
 * These complement the per-effect golden-signal checks in effects_test.cpp and
 * the zygisk stream-registry fail-safe tests (stream_handle_registry_test.cpp).
 * They exercise the full engine chain through the public C API and the embedded
 * DspEngine, asserting host-deterministic quality properties:
 *
 *   - Neutral (pass) preset transparency: float output is BIT-EXACT to the input
 *     through the whole chain (all effects disabled, mix wet=100 / outGain=0),
 *     for mono and stereo, including negative zero.
 *   - Denormal / subnormal handling: finite, never amplified, no crash.
 *   - Full-chain finite guarantee: a real multi-effect preset fed pathological
 *     boundary signals (full-scale DC, alternating full-scale, impulse, silence,
 *     subnormals) never emits a non-finite sample and never overruns its buffer.
 *   - Boundary frame counts: single frame and the prepared realtime maximum, with
 *     buffer canaries proving the engine writes exactly `frames*channels` samples.
 *   - Fail-safe boundary of responsibility: the ENGINE itself does NOT reject
 *     non-finite input (garbage-in/garbage-out by design). The sanitizing guard
 *     lives one layer up in stream_handle_registry (std::isfinite). This test
 *     documents that contract so a future accidental change is caught.
 *
 * Uses an explicit CHECK macro (not assert()) so the checks run under NDEBUG /
 * Release, where assert() is compiled out and would pass vacuously.
 */

#include "echidna/dsp/api.h"
#include "engine.h"

#include <algorithm>
#include <cmath>
#include <cstddef>
#include <cstdint>
#include <cstdio>
#include <cstring>
#include <limits>
#include <vector>

namespace
{
    int g_failures = 0;

    void check(bool cond, const char *expr, const char *file, int line, const char *msg)
    {
        if (!cond)
        {
            std::fprintf(stderr, "FAIL: %s  [%s:%d] %s\n", expr, file, line, msg);
            ++g_failures;
        }
    }

#define CHECK(cond, msg) check((cond), #cond, __FILE__, __LINE__, (msg))

    constexpr double kPi = 3.14159265358979323846;

    const char *kPassThroughPreset = R"({
        "name": "Passthrough",
        "engine": {"latencyMode": "LL", "blockMs": 20},
        "modules": [
            {"id": "gate", "enabled": false},
            {"id": "eq", "enabled": false, "bands": []},
            {"id": "comp", "enabled": false},
            {"id": "pitch", "enabled": false},
            {"id": "formant", "enabled": false},
            {"id": "autotune", "enabled": false},
            {"id": "reverb", "enabled": false},
            {"id": "mix", "wet": 100.0, "outGain": 0.0}
        ]
    })";

    // A genuine multi-effect chain (gate + 0 dB EQ + compressor). Used to prove
    // the *whole* pipeline keeps finite-input finite for pathological signals.
    const char *kRealChainPreset = R"({
        "name": "RealChain",
        "engine": {"latencyMode": "LL", "blockMs": 20},
        "modules": [
            {"id": "gate", "enabled": true, "threshold": -60.0, "attackMs": 5.0, "releaseMs": 80.0, "hysteresis": 3.0},
            {"id": "eq", "enabled": true, "bands": [{"f": 1000.0, "g": 0.0, "q": 1.0}]},
            {"id": "comp", "enabled": true, "threshold": -18.0, "ratio": 3.0, "attackMs": 5.0, "releaseMs": 120.0, "makeup": 0.0},
            {"id": "pitch", "enabled": false},
            {"id": "formant", "enabled": false},
            {"id": "autotune", "enabled": false},
            {"id": "reverb", "enabled": false},
            {"id": "mix", "wet": 100.0, "outGain": 0.0}
        ]
    })";

    bool all_finite(const float *x, size_t n)
    {
        for (size_t i = 0; i < n; ++i)
        {
            if (!std::isfinite(x[i]))
            {
                return false;
            }
        }
        return true;
    }

    // Neutral preset is bit-exact through the full chain, for mono and stereo.
    void test_neutral_bit_exact()
    {
        for (uint32_t channels : {uint32_t{1}, uint32_t{2}})
        {
            using echidna::dsp::DspEngine;
            using echidna::dsp::DspEngineOptions;
            DspEngineOptions options;
            options.load_plugins = false;
            options.lock_free_realtime_process = true;
            DspEngine engine(48000, channels, ECH_DSP_QUALITY_LOW_LATENCY, options);

            auto loaded = echidna::dsp::config::LoadPresetFromJson(kPassThroughPreset);
            CHECK(loaded.ok, "neutral preset must parse");
            CHECK(engine.UpdatePreset(loaded.preset) == ECH_DSP_STATUS_OK,
                  "neutral preset must apply");
            const size_t frames = 256;
            CHECK(engine.PrepareRealtime(frames) == ECH_DSP_STATUS_OK,
                  "prepare realtime");

            const size_t samples = frames * channels;
            std::vector<float> input(samples);
            for (size_t i = 0; i < samples; ++i)
            {
                input[i] = static_cast<float>(i) / 777.0f - 0.31f;
            }
            input[3] = -0.0f; // negative zero must survive bit-exactly
            input[7] = 1.0f;
            input[8] = -1.0f;
            std::vector<float> output(samples, 123.0f);
            CHECK(engine.ProcessBlock(input.data(), output.data(), frames) ==
                      ECH_DSP_STATUS_OK,
                  "neutral process");
            CHECK(std::memcmp(input.data(), output.data(), samples * sizeof(float)) == 0,
                  "neutral preset must be float bit-exact through the full chain");
        }
    }

    // Subnormal / denormal input: finite, never amplified, no crash.
    void test_denormals()
    {
        using echidna::dsp::DspEngine;
        using echidna::dsp::DspEngineOptions;
        DspEngineOptions options;
        options.load_plugins = false;
        options.lock_free_realtime_process = true;
        DspEngine engine(48000, 1, ECH_DSP_QUALITY_LOW_LATENCY, options);
        auto loaded = echidna::dsp::config::LoadPresetFromJson(kPassThroughPreset);
        CHECK(loaded.ok && engine.UpdatePreset(loaded.preset) == ECH_DSP_STATUS_OK,
              "denormal preset apply");
        const size_t frames = 128;
        CHECK(engine.PrepareRealtime(frames) == ECH_DSP_STATUS_OK, "denormal prepare");

        const float dm = std::numeric_limits<float>::denorm_min();
        const float sn = std::numeric_limits<float>::min();
        std::vector<float> input(frames);
        for (size_t i = 0; i < frames; ++i)
        {
            switch (i % 4)
            {
            case 0:
                input[i] = dm;
                break;
            case 1:
                input[i] = -dm;
                break;
            case 2:
                input[i] = sn / 2.0f;
                break;
            default:
                input[i] = -1e-40f;
                break;
            }
        }
        std::vector<float> output(frames, 3.0f);
        CHECK(engine.ProcessBlock(input.data(), output.data(), frames) ==
                  ECH_DSP_STATUS_OK,
              "denormal process");
        CHECK(all_finite(output.data(), frames),
              "denormal input must not produce a non-finite sample");
        for (size_t i = 0; i < frames; ++i)
        {
            // Defined: preserved exactly, or a CPU flush-to-zero mode collapses it
            // toward zero. Never amplified.
            CHECK(std::fabs(output[i]) <= std::fabs(input[i]),
                  "denormal must never be amplified by a neutral chain");
        }
    }

    // Full real chain fed pathological boundary signals must keep finite-input
    // finite and must never write outside the frame boundary (canaries).
    void test_full_chain_finite_and_canaries()
    {
        using echidna::dsp::DspEngine;
        using echidna::dsp::DspEngineOptions;
        DspEngineOptions options;
        options.load_plugins = false;
        options.lock_free_realtime_process = true;
        DspEngine engine(48000, 1, ECH_DSP_QUALITY_LOW_LATENCY, options);
        auto loaded = echidna::dsp::config::LoadPresetFromJson(kRealChainPreset);
        CHECK(loaded.ok, "real chain preset must parse");
        CHECK(engine.UpdatePreset(loaded.preset) == ECH_DSP_STATUS_OK,
              "real chain preset apply");
        constexpr size_t kMaxFrames = 256;
        CHECK(engine.PrepareRealtime(kMaxFrames) == ECH_DSP_STATUS_OK,
              "real chain prepare");

        constexpr float kGuard = 987654.0f;
        auto run = [&](const std::vector<float> &payload, const char *what)
        {
            const size_t n = payload.size();
            std::vector<float> in(n + 2, kGuard);
            std::vector<float> out(n + 2, kGuard);
            std::copy(payload.begin(), payload.end(), in.begin() + 1);
            CHECK(engine.ProcessBlock(in.data() + 1, out.data() + 1, n) ==
                      ECH_DSP_STATUS_OK,
                  what);
            CHECK(out.front() == kGuard && out.back() == kGuard,
                  "engine must not write outside the frame boundary");
            CHECK(all_finite(out.data() + 1, n),
                  "finite input must yield finite output through the real chain");
        };

        run(std::vector<float>(1, 0.5f), "single frame");
        run(std::vector<float>(kMaxFrames, 0.0f), "silence full block");
        run(std::vector<float>(kMaxFrames, 1.0f), "full-scale DC +1");
        run(std::vector<float>(kMaxFrames, -1.0f), "full-scale DC -1");

        std::vector<float> alternating(kMaxFrames);
        for (size_t i = 0; i < alternating.size(); ++i)
        {
            alternating[i] = (i % 2 == 0) ? 1.0f : -1.0f;
        }
        run(alternating, "alternating full-scale");

        std::vector<float> impulse(kMaxFrames, 0.0f);
        impulse[kMaxFrames / 2] = 1.0f;
        run(impulse, "impulse");

        std::vector<float> subnormals(kMaxFrames,
                                      std::numeric_limits<float>::denorm_min());
        run(subnormals, "subnormal block");

        // A loud sine, run repeatedly, must stay finite as compressor/gate state
        // settles across many callbacks.
        std::vector<float> tone(kMaxFrames);
        for (size_t i = 0; i < tone.size(); ++i)
        {
            tone[i] = static_cast<float>(0.9 * std::sin(2.0 * kPi * 300.0 *
                                                        static_cast<double>(i) / 48000.0));
        }
        for (int iter = 0; iter < 32; ++iter)
        {
            run(tone, "sustained tone finite");
        }
    }

    // Documents the fail-safe boundary of responsibility: the ENGINE accepts
    // non-finite input (does not reject it) and is garbage-in/garbage-out. The
    // std::isfinite sanitizing guard is deliberately one layer up, in the zygisk
    // stream_handle_registry. If this ever changes, the change should be a
    // conscious one -- this check will flag it.
    void test_engine_does_not_reject_non_finite()
    {
        using echidna::dsp::DspEngine;
        using echidna::dsp::DspEngineOptions;
        DspEngineOptions options;
        options.load_plugins = false;
        options.lock_free_realtime_process = true;
        DspEngine engine(48000, 1, ECH_DSP_QUALITY_LOW_LATENCY, options);
        auto loaded = echidna::dsp::config::LoadPresetFromJson(kPassThroughPreset);
        CHECK(loaded.ok && engine.UpdatePreset(loaded.preset) == ECH_DSP_STATUS_OK,
              "non-finite boundary preset apply");
        const size_t frames = 64;
        CHECK(engine.PrepareRealtime(frames) == ECH_DSP_STATUS_OK,
              "non-finite boundary prepare");

        constexpr float kGuard = 555.0f;
        std::vector<float> in(frames + 2, 0.1f);
        in.front() = kGuard;
        in.back() = kGuard;
        in[1 + 10] = std::numeric_limits<float>::quiet_NaN();
        in[1 + 20] = std::numeric_limits<float>::infinity();
        std::vector<float> out(frames + 2, kGuard);
        // Accepts the block (no rejection, no crash) ...
        CHECK(engine.ProcessBlock(in.data() + 1, out.data() + 1, frames) ==
                  ECH_DSP_STATUS_OK,
              "engine accepts non-finite input (registry is the isfinite guard)");
        // ... and does not corrupt neighbouring memory ...
        CHECK(out.front() == kGuard && out.back() == kGuard,
              "engine stays within the frame boundary even for non-finite input");
        // ... and, being GIGO, propagates non-finite rather than sanitizing.
        CHECK(!std::isfinite(out[1 + 10]),
              "engine does not sanitize NaN input (sanitization is the registry's job)");
    }
} // namespace

int main()
{
    test_neutral_bit_exact();
    test_denormals();
    test_full_chain_finite_and_canaries();
    test_engine_does_not_reject_non_finite();
    ech_dsp_shutdown();

    if (g_failures != 0)
    {
        std::fprintf(stderr, "dsp_quality_test: %d check(s) failed\n", g_failures);
        return 1;
    }
    std::fprintf(stderr, "dsp_quality_test: all checks passed\n");
    return 0;
}
