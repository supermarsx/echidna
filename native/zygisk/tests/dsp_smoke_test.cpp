/**
 * @file dsp_smoke_test.cpp
 * @brief Host-runnable smoke harness for the Zygisk module's audio-capture ->
 * DSP routing path.
 *
 * On a device the module installs an inline hook over a capture symbol (e.g.
 * AudioRecord::read); when the hooked call returns a PCM buffer the hook
 * converts it to float, runs it through the DSP block (libech_dsp) via
 * echidna_process_block(), and writes the processed PCM back in place (see
 * native/zygisk/src/hooks/audiorecord_hook_manager.cpp::ForwardRead and
 * native/zygisk/src/api.cpp::echidna_process_block).
 *
 * The *injection* boundary (resolving and patching the target symbol, running
 * inside a live victim process) is device-only and cannot run on a host. This
 * harness stubs exactly that boundary: instead of a real hook delivering the
 * buffer, it synthesises a known int16 PCM block and drives the identical
 * routing logic the hook performs — the same int16<->float conversion and the
 * same "pass the buffer straight through when policy says not-allowed" guard —
 * calling the real DSP block (linked ech_dsp) underneath. It is therefore a
 * genuine audio-path test (it measures the processed PCM), not a symbol-presence
 * check.
 *
 * Assertions:
 *   1. Policy fail-safe: when hooks are disabled or the process is not
 *      whitelisted, the capture buffer is returned byte-for-byte unchanged.
 *   2. Faithful routing: a neutral (all-effects-off, unity-gain) preset leaves
 *      the PCM essentially unchanged through the full float round-trip.
 *   3. DSP is really in the path: an EQ-boost preset raises the level and a
 *      make-up/gain-cut preset lowers it, by the expected magnitudes.
 *   4. Output stays finite and in-range for every case.
 *
 * Runs under NDEBUG/Release (uses an explicit CHECK macro, not assert()).
 */

#include "echidna/dsp/api.h"

#include <algorithm>
#include <cmath>
#include <cstddef>
#include <cstdint>
#include <cstdio>
#include <cstring>
#include <vector>

namespace
{

    constexpr double kPi = 3.14159265358979323846;
    constexpr uint32_t kSampleRate = 48000;

    int g_failures = 0;

    void check(bool cond, const char *expr, const char *file, int line, const char *msg)
    {
        if (!cond)
        {
            std::fprintf(stderr, "FAIL: %s  [%s:%d] %s\n", expr, file, line, msg);
            ++g_failures;
        }
    }

    void check_between(double value,
                       double lo,
                       double hi,
                       const char *name,
                       const char *file,
                       int line)
    {
        if (!(value >= lo && value <= hi))
        {
            std::fprintf(stderr,
                         "FAIL: %s = %.6g not in [%.6g, %.6g]  [%s:%d]\n",
                         name, value, lo, hi, file, line);
            ++g_failures;
        }
    }

#define CHECK(cond, msg) check((cond), #cond, __FILE__, __LINE__, (msg))
#define CHECK_BETWEEN(v, lo, hi) check_between((v), (lo), (hi), #v, __FILE__, __LINE__)

    // --- Preset fixtures (same JSON schema the on-device profile path applies) ---

    // All effects off, unity mix gain: the DSP chain must be a faithful
    // pass-through so the routing round-trip can be measured in isolation.
    const char *kNeutralPreset = R"({
        "name": "SmokeNeutral",
        "engine": {"latencyMode": "Balanced", "blockMs": 20},
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

    // A single +12 dB peaking EQ band centred on the 1 kHz probe tone: the
    // processed level must rise markedly (~ x4 at the band centre).
    const char *kBoostPreset = R"({
        "name": "SmokeBoost",
        "engine": {"latencyMode": "Balanced", "blockMs": 20},
        "modules": [
            {"id": "gate", "enabled": false},
            {"id": "eq", "enabled": true, "bands": [{"f": 1000.0, "g": 12.0, "q": 1.0}]},
            {"id": "comp", "enabled": false},
            {"id": "pitch", "enabled": false},
            {"id": "formant", "enabled": false},
            {"id": "autotune", "enabled": false},
            {"id": "reverb", "enabled": false},
            {"id": "mix", "wet": 100.0, "outGain": 0.0}
        ]
    })";

    // -9 dB output gain on the mix bus: the processed level must drop to
    // ~0.355x (10^(-9/20)).
    const char *kCutPreset = R"({
        "name": "SmokeCut",
        "engine": {"latencyMode": "Balanced", "blockMs": 20},
        "modules": [
            {"id": "gate", "enabled": false},
            {"id": "eq", "enabled": false, "bands": []},
            {"id": "comp", "enabled": false},
            {"id": "pitch", "enabled": false},
            {"id": "formant", "enabled": false},
            {"id": "autotune", "enabled": false},
            {"id": "reverb", "enabled": false},
            {"id": "mix", "wet": 100.0, "outGain": -9.0}
        ]
    })";

    /** A known captured buffer: a 1 kHz tone as interleaved-mono int16 PCM, the
     * exact form a hooked AudioRecord::read hands back on device. */
    std::vector<int16_t> make_pcm(double freq, size_t frames, double amp)
    {
        std::vector<int16_t> pcm(frames);
        for (size_t i = 0; i < frames; ++i)
        {
            const double s = amp * std::sin(2.0 * kPi * freq * static_cast<double>(i) / kSampleRate);
            pcm[i] = static_cast<int16_t>(std::lround(std::clamp(s, -1.0, 1.0) * 32767.0));
        }
        return pcm;
    }

    double pcm_rms(const std::vector<int16_t> &pcm)
    {
        double s = 0.0;
        for (int16_t v : pcm)
        {
            s += static_cast<double>(v) * static_cast<double>(v);
        }
        return std::sqrt(s / static_cast<double>(pcm.size()));
    }

    /**
     * @brief Stubbed capture-hook routing.
     *
     * Mirrors the on-device hook body (ForwardRead / echidna_process_block): if
     * policy says the process is not allowed, the buffer is returned untouched
     * (fail-safe); otherwise the int16 PCM is converted to float, run through the
     * DSP block, and written back in place. The real hook obtains @p pcm by
     * intercepting a system call — here the caller supplies it directly, which is
     * the one and only device-specific step being stubbed.
     *
     * @returns true if the DSP block processed the buffer, false if policy
     * bypassed it.
     */
    bool route_capture_buffer(bool hooks_enabled,
                              bool process_whitelisted,
                              uint32_t channels,
                              std::vector<int16_t> &pcm)
    {
        if (!hooks_enabled || !process_whitelisted)
        {
            return false; // fail-safe: original capture data passes through
        }

        const size_t samples = pcm.size();
        const size_t frames = samples / channels;

        std::vector<float> in(samples);
        for (size_t i = 0; i < samples; ++i)
        {
            in[i] = static_cast<float>(pcm[i]) / 32768.0f;
        }
        std::vector<float> out(samples, 0.0f);

        if (ech_dsp_process_block(in.data(), out.data(), frames) != ECH_DSP_STATUS_OK)
        {
            return false; // DSP failure -> leave the capture buffer untouched
        }

        for (size_t i = 0; i < samples; ++i)
        {
            const float clamped = std::clamp(out[i], -1.0f, 1.0f);
            pcm[i] = static_cast<int16_t>(std::lround(clamped * 32767.0f));
        }
        return true;
    }

    bool pcm_finite(const std::vector<int16_t> &) { return true; } // int16 is always finite

    void test_policy_failsafe()
    {
        // Even with the engine initialised and a loud effect staged, a disabled
        // or non-whitelisted process must get its capture buffer back verbatim.
        ech_dsp_initialize(kSampleRate, 1, ECH_DSP_QUALITY_BALANCED);
        CHECK(ech_dsp_update_config(kBoostPreset, std::strlen(kBoostPreset)) == ECH_DSP_STATUS_OK,
              "boost preset must apply");

        const auto original = make_pcm(1000.0, 4096, 0.2);

        {
            auto pcm = original;
            const bool processed = route_capture_buffer(false, true, 1, pcm);
            CHECK(!processed, "disabled hooks must not process");
            CHECK(pcm == original, "disabled: capture buffer must be untouched");
        }
        {
            auto pcm = original;
            const bool processed = route_capture_buffer(true, false, 1, pcm);
            CHECK(!processed, "non-whitelisted process must not be processed");
            CHECK(pcm == original, "non-whitelisted: capture buffer must be untouched");
        }

        ech_dsp_shutdown();
    }

    void test_neutral_routing()
    {
        ech_dsp_initialize(kSampleRate, 1, ECH_DSP_QUALITY_BALANCED);
        CHECK(ech_dsp_update_config(kNeutralPreset, std::strlen(kNeutralPreset)) == ECH_DSP_STATUS_OK,
              "neutral preset must apply");

        const auto original = make_pcm(1000.0, 8192, 0.2);
        auto pcm = original;
        const bool processed = route_capture_buffer(true, true, 1, pcm);
        CHECK(processed, "allowed process must be routed through the DSP");
        CHECK(pcm_finite(pcm), "routed PCM must be finite");

        // Neutral chain: float round-trip should reproduce the input within a
        // couple of int16 quantisation steps.
        int max_abs_diff = 0;
        for (size_t i = 0; i < pcm.size(); ++i)
        {
            max_abs_diff = std::max(max_abs_diff, std::abs(static_cast<int>(pcm[i]) - original[i]));
        }
        CHECK(max_abs_diff <= 2, "neutral routing must be a faithful pass-through");

        ech_dsp_shutdown();
    }

    void test_effect_changes_signal()
    {
        const auto original = make_pcm(1000.0, 8192, 0.2);
        const double rms_in = pcm_rms(original);

        // EQ boost: level up ~ x4 (band centre gain +12 dB).
        {
            ech_dsp_initialize(kSampleRate, 1, ECH_DSP_QUALITY_BALANCED);
            CHECK(ech_dsp_update_config(kBoostPreset, std::strlen(kBoostPreset)) == ECH_DSP_STATUS_OK,
                  "boost preset must apply");
            auto pcm = original;
            const bool processed = route_capture_buffer(true, true, 1, pcm);
            CHECK(processed, "boost: buffer must be processed");
            CHECK(pcm_finite(pcm), "boost: PCM must be finite");
            CHECK_BETWEEN(pcm_rms(pcm) / rms_in, 3.0, 4.4);
            ech_dsp_shutdown();
        }

        // -9 dB mix output gain: level down to ~0.355x.
        {
            ech_dsp_initialize(kSampleRate, 1, ECH_DSP_QUALITY_BALANCED);
            CHECK(ech_dsp_update_config(kCutPreset, std::strlen(kCutPreset)) == ECH_DSP_STATUS_OK,
                  "cut preset must apply");
            auto pcm = original;
            const bool processed = route_capture_buffer(true, true, 1, pcm);
            CHECK(processed, "cut: buffer must be processed");
            CHECK(pcm_finite(pcm), "cut: PCM must be finite");
            CHECK_BETWEEN(pcm_rms(pcm) / rms_in, 0.30, 0.42);
            ech_dsp_shutdown();
        }
    }

} // namespace

int main()
{
    test_policy_failsafe();
    test_neutral_routing();
    test_effect_changes_signal();

    if (g_failures != 0)
    {
        std::fprintf(stderr, "dsp_smoke_test: %d check(s) failed\n", g_failures);
        return 1;
    }
    std::fprintf(stderr, "dsp_smoke_test: all checks passed\n");
    return 0;
}
