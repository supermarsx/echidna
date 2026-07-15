/**
 * @file effects_test.cpp
 * @brief Per-effect DSP correctness tests driven by deterministic golden
 * signals (pure tones, impulses) with quantitative assertions on the processed
 * output: pitch-shift cents accuracy, formant tilt, auto-tune snap, and
 * gate / compressor / EQ sanity.
 *
 * These tests link the DSP effect classes directly (white-box) and drive each
 * processor with a known input, then measure the output (RMS, peak, fundamental
 * period via autocorrelation) and assert calibrated tolerances. The checks are
 * expressed with an explicit CHECK macro rather than assert() so they run under
 * NDEBUG/Release builds (assert() is compiled out there and would make the test
 * pass vacuously).
 *
 * Where an effect's current implementation has a direction/behaviour quirk that
 * cannot be corrected from the test tree, the assertion targets the property
 * that is genuinely invariant (e.g. the *magnitude* of a pitch shift, or that a
 * gate never amplifies) and the quirk is documented inline. See t2-e17 log for
 * the accompanying findings list.
 */

#include "effects/auto_tune.h"
#include "effects/compressor.h"
#include "effects/formant_shifter.h"
#include "effects/gate_processor.h"
#include "effects/parametric_eq.h"
#include "effects/pitch_shifter.h"

#include <cmath>
#include <cstddef>
#include <cstdint>
#include <cstdio>
#include <vector>

using namespace echidna::dsp::effects;

namespace
{

    constexpr double kPi = 3.14159265358979323846;
    constexpr double kSampleRate = 48000.0;

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

    /** Generate a mono sine of @p freq Hz, @p n samples, amplitude @p amp. */
    std::vector<float> make_sine(double freq, size_t n, double amp)
    {
        std::vector<float> v(n);
        for (size_t i = 0; i < n; ++i)
        {
            v[i] = static_cast<float>(amp * std::sin(2.0 * kPi * freq * static_cast<double>(i) / kSampleRate));
        }
        return v;
    }

    std::vector<float> make_sine_at_rate(double freq,
                                         size_t frames,
                                         double amp,
                                         uint32_t sample_rate)
    {
        std::vector<float> samples(frames);
        for (size_t frame = 0; frame < frames; ++frame)
        {
            samples[frame] = static_cast<float>(
                amp * std::sin(2.0 * kPi * freq * static_cast<double>(frame) /
                               static_cast<double>(sample_rate)));
        }
        return samples;
    }

    double estimate_crossing_frequency(const std::vector<float> &samples,
                                       uint32_t sample_rate,
                                       size_t begin_frame,
                                       size_t end_frame = static_cast<size_t>(-1))
    {
        end_frame = std::min(end_frame, samples.size());
        double first_crossing = 0.0;
        double last_crossing = 0.0;
        size_t crossings = 0;
        for (size_t frame = std::max<size_t>(1, begin_frame); frame < end_frame; ++frame)
        {
            const double previous = samples[frame - 1];
            const double current = samples[frame];
            if (previous <= 0.0 && current > 0.0 && current != previous)
            {
                const double crossing = static_cast<double>(frame - 1) -
                                        previous / (current - previous);
                if (crossings == 0)
                {
                    first_crossing = crossing;
                }
                last_crossing = crossing;
                ++crossings;
            }
        }
        if (crossings < 2 || last_crossing <= first_crossing)
        {
            return 0.0;
        }
        return static_cast<double>(crossings - 1) * sample_rate /
               (last_crossing - first_crossing);
    }

    double autocorr(const std::vector<float> &x, int lag, size_t lo, size_t hi)
    {
        double c = 0.0;
        for (size_t i = lo; i + static_cast<size_t>(lag) < hi; ++i)
        {
            c += static_cast<double>(x[i]) * static_cast<double>(x[i + static_cast<size_t>(lag)]);
        }
        return c;
    }

    /**
     * @brief Estimate the fundamental period (in samples) of a mono buffer via
     * autocorrelation with parabolic peak interpolation for sub-sample accuracy.
     */
    double estimate_period(const std::vector<float> &x,
                           int min_lag,
                           int max_lag,
                           size_t lo,
                           size_t hi)
    {
        double best = -1e300;
        int best_lag = min_lag;
        for (int lag = min_lag; lag <= max_lag; ++lag)
        {
            const double c = autocorr(x, lag, lo, hi);
            if (c > best)
            {
                best = c;
                best_lag = lag;
            }
        }
        const double cm = autocorr(x, best_lag - 1, lo, hi);
        const double c0 = autocorr(x, best_lag, lo, hi);
        const double cp = autocorr(x, best_lag + 1, lo, hi);
        const double denom = cm - 2.0 * c0 + cp;
        const double delta = denom != 0.0 ? 0.5 * (cm - cp) / denom : 0.0;
        return static_cast<double>(best_lag) + delta;
    }

    double estimate_freq(const std::vector<float> &x, int min_lag, int max_lag, size_t lo, size_t hi)
    {
        return kSampleRate / estimate_period(x, min_lag, max_lag, lo, hi);
    }

    double rms(const std::vector<float> &x, size_t lo, size_t hi)
    {
        double s = 0.0;
        for (size_t i = lo; i < hi; ++i)
        {
            s += static_cast<double>(x[i]) * static_cast<double>(x[i]);
        }
        return std::sqrt(s / static_cast<double>(hi - lo));
    }

    double peak(const std::vector<float> &x, size_t lo, size_t hi)
    {
        double p = 0.0;
        for (size_t i = lo; i < hi; ++i)
        {
            p = std::max(p, static_cast<double>(std::fabs(x[i])));
        }
        return p;
    }

    bool all_finite(const std::vector<float> &x)
    {
        for (float v : x)
        {
            if (!std::isfinite(v))
            {
                return false;
            }
        }
        return true;
    }

    double cents(double f, double ref) { return 1200.0 * std::log2(f / ref); }

    // --- Pitch shifter -----------------------------------------------------
    // The low-latency granular backend is a naive within-block resampler: it
    // maps a requested ratio 2^((semitones + cents/100)/12) onto the output
    // fundamental. These tests assert direction, magnitude and exact identity at
    // unity while preserving the legacy static-ratio backend contract.
    void test_pitch_shifter()
    {
        const uint32_t sr = static_cast<uint32_t>(kSampleRate);

        // Unity (0 semitones / 0 cents) must be an exact pass-through.
        {
            const size_t n = 1024;
            auto in = make_sine(300.0, n, 0.4);
            auto buf = in;
            PitchShifter p;
            p.prepare(sr, 1);
            p.set_enabled(true);
            p.set_parameters(PitchParameters{});
            ProcessContext ctx{buf.data(), n, 1, sr};
            p.process(ctx);
            double max_diff = 0.0;
            for (size_t i = 0; i < n; ++i)
            {
                max_diff = std::max(max_diff, static_cast<double>(std::fabs(buf[i] - in[i])));
            }
            CHECK(max_diff < 1e-6, "pitch unity shift must be exact pass-through");
        }

        // Cents accuracy: a +40 / -40 cent request must move the fundamental by
        // ~40 cents in magnitude (input 200 Hz, no intra-block wrap for |ratio|
        // near unity so the whole block resamples cleanly).
        struct Case
        {
            double semitones;
            double cents;
            double expect_cents;
        };
        const Case cases[] = {
            {0.0, 40.0, 40.0},
            {0.0, -40.0, 40.0},
            {2.0, 0.0, 200.0}, // +2 semitones == 200 cents
        };
        for (const Case &c : cases)
        {
            const size_t n = 8192;
            auto in = make_sine(200.0, n, 0.5);
            auto buf = in;
            PitchShifter p;
            p.prepare(sr, 1);
            p.set_enabled(true);
            PitchParameters pp;
            pp.semitones = static_cast<float>(c.semitones);
            pp.cents = static_cast<float>(c.cents);
            pp.quality = PitchQuality::kLowLatency;
            p.set_parameters(pp);
            ProcessContext ctx{buf.data(), n, 1, sr};
            p.process(ctx);
            CHECK(all_finite(buf), "pitch output must be finite");
            const double f_out = estimate_freq(buf, 150, 460, 0, 6000);
            const double applied = std::fabs(cents(f_out, 200.0));
            // +/- 12 cents tolerance around the requested magnitude.
            CHECK_BETWEEN(applied, c.expect_cents - 12.0, c.expect_cents + 12.0);
            const double requested = c.semitones * 100.0 + c.cents;
            CHECK(requested > 0.0 ? f_out > 200.0 : f_out < 200.0,
                  "pitch shifter must move in the requested direction");
        }
    }

    // --- Formant shifter ---------------------------------------------------
    void test_formant_shifter()
    {
        const uint32_t sr = static_cast<uint32_t>(kSampleRate);

        // Disabled: pass-through untouched.
        {
            const size_t n = 512;
            auto in = make_sine(500.0, n, 0.4);
            auto buf = in;
            FormantShifter f;
            f.prepare(sr, 1);
            f.set_enabled(false);
            f.set_parameters(FormantParameters{});
            ProcessContext ctx{buf.data(), n, 1, sr};
            f.process(ctx);
            double max_diff = 0.0;
            for (size_t i = 0; i < n; ++i)
            {
                max_diff = std::max(max_diff, static_cast<double>(std::fabs(buf[i] - in[i])));
            }
            CHECK(max_diff < 1e-9, "disabled formant shifter must be identity");
        }

        // Zero-cent tilt: the all-pass coefficient is 0, so the stage reduces to
        // a single-sample delay (out[i] == in[i-1], out[0] == 0).
        {
            const size_t n = 512;
            auto in = make_sine(500.0, n, 0.4);
            auto buf = in;
            FormantShifter f;
            f.prepare(sr, 1);
            f.set_enabled(true);
            f.set_parameters(FormantParameters{});
            ProcessContext ctx{buf.data(), n, 1, sr};
            f.process(ctx);
            CHECK(std::fabs(buf[0]) < 1e-9, "formant zero-cent out[0] must be 0");
            double max_diff = 0.0;
            for (size_t i = 1; i < n; ++i)
            {
                max_diff = std::max(max_diff, static_cast<double>(std::fabs(buf[i] - in[i - 1])));
            }
            CHECK(max_diff < 1e-6, "formant zero-cent must be a one-sample delay");
        }

        // Non-zero tilt: first-order all-pass -> energy (RMS) preserved for a
        // steady tone, but the waveform is phase-shifted (differs from the plain
        // one-sample delay), confirming the tilt coefficient took effect.
        {
            const size_t n = 4096;
            auto in = make_sine(500.0, n, 0.4);
            auto buf = in;
            FormantShifter f;
            f.prepare(sr, 1);
            f.set_enabled(true);
            FormantParameters fp;
            fp.cents = 600.0f; // maximum tilt -> largest departure from delay
            f.set_parameters(fp);
            ProcessContext ctx{buf.data(), n, 1, sr};
            f.process(ctx);
            CHECK(all_finite(buf), "formant output must be finite");
            const double r_in = rms(in, 100, n);
            const double r_out = rms(buf, 100, n);
            CHECK_BETWEEN(r_out / r_in, 0.97, 1.03); // all-pass preserves energy
            double max_phase_diff = 0.0;
            for (size_t i = 1; i < n; ++i)
            {
                max_phase_diff =
                    std::max(max_phase_diff, static_cast<double>(std::fabs(buf[i] - in[i - 1])));
            }
            // The all-pass phase term departs from a plain one-sample delay by a
            // small but deterministic amount (~0.011 peak here); confirm it fired.
            CHECK(max_phase_diff > 0.004, "non-zero tilt must alter the waveform");
        }
    }

    // --- Auto-tune ---------------------------------------------------------
    // Snap accuracy is validated on in-tune material: a tone already on the
    // chromatic grid must remain within +/-10 cents of that note after tuning
    // (detection + note selection + correction round-trip is unity). The tracker
    // must also converge to a stable output pitch.
    void test_auto_tune()
    {
        const uint32_t sr = static_cast<uint32_t>(kSampleRate);

        auto configure = [](AutoTune &tuner, uint32_t sample_rate, size_t block_frames)
        {
            tuner.prepare(sample_rate, 1);
            tuner.prepare_realtime(block_frames);
            tuner.set_enabled(true);
            AutoTuneParameters parameters;
            parameters.key = MusicalKey::kC;
            parameters.scale = ScaleType::kChromatic;
            parameters.retune_speed_ms = 1.0f;
            parameters.humanize = 0.0f;
            parameters.flex_tune = 0.0f;
            parameters.snap_strength = 100.0f;
            tuner.set_parameters(parameters);
            tuner.reset();
        };

        auto process_stream = [](AutoTune &tuner,
                                 std::vector<float> &samples,
                                 size_t block_frames,
                                 uint32_t sample_rate)
        {
            for (size_t offset = 0; offset < samples.size(); offset += block_frames)
            {
                const size_t frames = std::min(block_frames, samples.size() - offset);
                ProcessContext ctx{samples.data() + offset, frames, 1, sample_rate};
                tuner.process(ctx);
            }
        };

        auto corrected_stream = [&](double input_hz,
                                    uint32_t sample_rate,
                                    size_t block_frames)
        {
            const size_t analysis_frames = sample_rate / 30;
            const size_t warm_callbacks =
                (analysis_frames + block_frames - 1) / block_frames + 32;
            const size_t stream_callbacks =
                (static_cast<size_t>(sample_rate) * 2 + block_frames - 1) /
                block_frames;
            const size_t callbacks = std::max(warm_callbacks, stream_callbacks);
            auto samples = make_sine_at_rate(
                input_hz, callbacks * block_frames, 0.35, sample_rate);
            AutoTune tuner;
            configure(tuner, sample_rate, block_frames);
            process_stream(tuner, samples, block_frames, sample_rate);
            return samples;
        };

        // Disabled: identity.
        {
            const size_t n = 4096;
            auto in = make_sine(440.0, n, 0.5);
            auto buf = in;
            AutoTune a;
            a.prepare(sr, 1);
            a.set_enabled(false);
            a.set_parameters(AutoTuneParameters{});
            ProcessContext ctx{buf.data(), n, 1, sr};
            a.process(ctx);
            double max_diff = 0.0;
            for (size_t i = 0; i < n; ++i)
            {
                max_diff = std::max(max_diff, static_cast<double>(std::fabs(buf[i] - in[i])));
            }
            CHECK(max_diff < 1e-9, "disabled auto-tune must be identity");
        }

        // In-tune notes: stay within +/-10 cents of the grid note.
        const double notes[] = {440.0, 523.25}; // A4, C5
        for (double note : notes)
        {
            constexpr size_t block_frames = 480;
            const auto output = corrected_stream(note, sr, block_frames);
            const size_t tail_frames = block_frames * 16;
            const double f_out =
                estimate_crossing_frequency(output, sr, output.size() - tail_frames);
            CHECK(all_finite(output), "auto-tune output must be finite");
            CHECK_BETWEEN(std::fabs(cents(f_out, note)), 0.0, 10.0);

            const double previous_hz = estimate_crossing_frequency(
                output, sr, output.size() - tail_frames * 2, output.size() - tail_frames);
            CHECK_BETWEEN(std::fabs(cents(previous_hz, note)), 0.0, 10.0);
        }

        // Stateful history and resampling must correct a continuous stream, not
        // merely the interior of one callback. Cover the callback sizes used by
        // Android low-latency and balanced paths in both correction directions.
        for (size_t block_frames : {size_t{64},
                                    size_t{128},
                                    size_t{240},
                                    size_t{256},
                                    size_t{480},
                                    size_t{960},
                                    size_t{2048}})
        {
            for (double input_hz : {430.0, 450.0})
            {
                const auto output = corrected_stream(input_hz, sr, block_frames);
                const size_t tail_frames = std::min(output.size(), block_frames * 16);
                const double output_hz =
                    estimate_crossing_frequency(output, sr, output.size() - tail_frames);
                CHECK(all_finite(output), "continuous auto-tune output must be finite");
                CHECK_BETWEEN(output_hz, 432.5, 447.5);

                double max_boundary_jump = 0.0;
                const size_t first_boundary = output.size() - tail_frames + block_frames;
                for (size_t boundary = first_boundary;
                     boundary < output.size();
                     boundary += block_frames)
                {
                    max_boundary_jump = std::max(
                        max_boundary_jump,
                        static_cast<double>(std::fabs(output[boundary] - output[boundary - 1])));
                }
                CHECK(max_boundary_jump < 0.05,
                      "callback continuity fade must prevent boundary clicks");
            }
        }

        // Exercise common Android sample rates in both correction directions.
        for (uint32_t sample_rate : {uint32_t{44100}, uint32_t{48000}, uint32_t{96000}})
        {
            for (double input_hz : {430.0, 450.0})
            {
                constexpr size_t block_frames = 480;
                const auto output = corrected_stream(input_hz, sample_rate, block_frames);
                const size_t tail_frames = block_frames * 16;
                const double output_hz = estimate_crossing_frequency(
                    output, sample_rate, output.size() - tail_frames);
                CHECK_BETWEEN(output_hz, 432.5, 447.5);
            }
        }

        // Silence and deterministic aperiodic input are unvoiced and must be
        // exact bypasses. Reset must also restore history, smoothing and phase.
        {
            constexpr size_t block_frames = 480;
            constexpr size_t callbacks = 40;
            std::vector<float> silence(block_frames * callbacks, 0.0f);
            auto silence_output = silence;
            AutoTune tuner;
            configure(tuner, sr, block_frames);
            process_stream(tuner, silence_output, block_frames, sr);
            CHECK(silence_output == silence, "silence must bypass auto-tune exactly");

            std::vector<float> noise(block_frames * callbacks);
            uint32_t state = 0x12345678U;
            for (float &sample : noise)
            {
                state = state * 1664525U + 1013904223U;
                sample = (static_cast<float>(state >> 8) / 8388607.5f - 1.0f) * 0.2f;
            }
            auto noise_output = noise;
            tuner.reset();
            process_stream(tuner, noise_output, block_frames, sr);
            CHECK(noise_output == noise, "aperiodic input must bypass auto-tune exactly");

            const auto input = make_sine_at_rate(450.0, block_frames * callbacks, 0.35, sr);
            auto after_reset = input;
            auto fresh_output = input;
            tuner.reset();
            process_stream(tuner, after_reset, block_frames, sr);
            AutoTune fresh;
            configure(fresh, sr, block_frames);
            process_stream(fresh, fresh_output, block_frames, sr);
            double reset_difference = 0.0;
            for (size_t frame = 0; frame < input.size(); ++frame)
            {
                reset_difference = std::max(
                    reset_difference,
                    static_cast<double>(std::fabs(after_reset[frame] - fresh_output[frame])));
            }
            CHECK(reset_difference < 1e-7,
                  "reset must restore deterministic analysis and resampling state");
        }

        // Canary samples around a prepared callback prove the effect respects
        // the public frame boundary while using its preallocated scratch.
        {
            constexpr size_t block_frames = 480;
            constexpr float guard = 1234.5f;
            auto tone = make_sine_at_rate(450.0, block_frames, 0.35, sr);
            std::vector<float> guarded(block_frames + 2, guard);
            std::copy(tone.begin(), tone.end(), guarded.begin() + 1);
            AutoTune tuner;
            configure(tuner, sr, block_frames);
            ProcessContext ctx{guarded.data() + 1, block_frames, 1, sr};
            tuner.process(ctx);
            CHECK(guarded.front() == guard && guarded.back() == guard,
                  "auto-tune must not write outside the callback buffer");
        }
    }

    // --- Gate --------------------------------------------------------------
    // A loud tone above the open threshold passes unattenuated; the gate is a
    // pure attenuator (gain in [0, 1]) so it must never amplify.
    void test_gate()
    {
        const uint32_t sr = static_cast<uint32_t>(kSampleRate);
        GateParameters gp{-45.0f, 5.0f, 80.0f, 3.0f};

        auto process_stream = [sr](GateProcessor &gate,
                                   std::vector<float> &buffer,
                                   size_t block_frames)
        {
            for (size_t offset = 0; offset < buffer.size(); offset += block_frames)
            {
                const size_t frames = std::min(block_frames, buffer.size() - offset);
                ProcessContext ctx{buffer.data() + offset, frames, 1, sr};
                gate.process(ctx);
            }
        };

        // Disabled: identity.
        {
            const size_t n = 2048;
            auto in = make_sine(300.0, n, 0.5);
            auto buf = in;
            GateProcessor g;
            g.set_parameters(gp);
            g.prepare(sr, 1);
            g.set_enabled(false);
            ProcessContext ctx{buf.data(), n, 1, sr};
            g.process(ctx);
            double max_diff = 0.0;
            for (size_t i = 0; i < n; ++i)
            {
                max_diff = std::max(max_diff, static_cast<double>(std::fabs(buf[i] - in[i])));
            }
            CHECK(max_diff < 1e-9, "disabled gate must be identity");
        }

        // Loud tone (well above threshold) passes through.
        {
            const size_t n = 2048;
            auto in = make_sine(300.0, n, 0.5);
            auto buf = in;
            GateProcessor g;
            g.set_parameters(gp);
            g.prepare(sr, 1);
            g.set_enabled(true);
            g.reset();
            ProcessContext ctx{buf.data(), n, 1, sr};
            g.process(ctx);
            CHECK(all_finite(buf), "gate output must be finite");
            CHECK_BETWEEN(rms(buf, 0, n) / rms(in, 0, n), 0.97, 1.001);
        }

        // Never amplifies: peak-out <= peak-in for a mixed-level signal.
        {
            const size_t n = 2048;
            auto in = make_sine(300.0, n, 0.2);
            for (size_t i = 0; i < n; ++i)
            {
                in[i] += static_cast<float>(0.05 * std::sin(2.0 * kPi * 3000.0 * i / kSampleRate));
            }
            auto buf = in;
            GateProcessor g;
            g.set_parameters(gp);
            g.prepare(sr, 1);
            g.set_enabled(true);
            g.reset();
            ProcessContext ctx{buf.data(), n, 1, sr};
            g.process(ctx);
            CHECK(peak(buf, 0, n) <= peak(in, 0, n) * 1.001, "gate must not amplify");
        }

        // A signal below the close threshold must settle closed independent of
        // callback size. This reproduces the public 48 kHz / 256-frame failure.
        double reference_gain = -1.0;
        for (size_t block_frames : {size_t{64}, size_t{128}, size_t{256}, size_t{960}})
        {
            const size_t n = sr * 2;
            const auto in = make_sine(300.0, n, 0.002);
            auto buf = in;
            GateProcessor g;
            g.set_parameters(gp);
            g.prepare(sr, 1);
            g.set_enabled(true);
            g.reset();
            process_stream(g, buf, block_frames);

            CHECK(all_finite(buf), "sub-threshold gate output must be finite");
            const double settled_gain = rms(buf, n / 2, n) / rms(in, n / 2, n);
            CHECK(settled_gain < 0.1, "sub-threshold signal must settle below 0.1 gain");
            if (reference_gain < 0.0)
            {
                reference_gain = settled_gain;
            }
            else
            {
                CHECK(std::fabs(settled_gain - reference_gain) < 1e-5,
                      "gate timing must be callback-size invariant");
            }
        }

        // Once open, a level inside the hysteresis band remains open. After a
        // genuinely quiet interval closes it, that same mid-level signal cannot
        // reopen the gate; a new above-threshold onset can.
        {
            const size_t segment_frames = sr / 4;
            const auto loud = make_sine(300.0, segment_frames, 0.1);
            const auto mid = make_sine(300.0, segment_frames, 0.006);
            const auto quiet = make_sine(300.0, segment_frames * 2, 0.002);
            auto open_mid = mid;
            auto closed_mid = mid;
            auto reopened = loud;

            GateProcessor g;
            g.set_parameters(gp);
            g.prepare(sr, 1);
            g.set_enabled(true);
            g.reset();

            auto onset = loud;
            process_stream(g, onset, 256);
            process_stream(g, open_mid, 256);
            auto quiet_out = quiet;
            process_stream(g, quiet_out, 256);
            process_stream(g, closed_mid, 256);
            process_stream(g, reopened, 256);

            CHECK(rms(open_mid, segment_frames / 2, segment_frames) /
                          rms(mid, segment_frames / 2, segment_frames) >
                      0.95,
                  "hysteresis band must remain open after a loud onset");
            CHECK(rms(closed_mid, segment_frames / 2, segment_frames) /
                          rms(mid, segment_frames / 2, segment_frames) <
                      0.1,
                  "hysteresis band must not reopen a closed gate");
            CHECK(rms(reopened, segment_frames / 2, segment_frames) /
                          rms(loud, segment_frames / 2, segment_frames) >
                      0.95,
                  "above-threshold signal must reopen the gate");
        }
    }

    // --- Compressor --------------------------------------------------------
    void test_compressor()
    {
        const uint32_t sr = static_cast<uint32_t>(kSampleRate);
        auto ratio_for = [&](double amp)
        {
            const size_t n = 4096;
            Compressor c;
            CompressorParameters cp;
            cp.threshold_db = -24.0f;
            cp.ratio = 3.0f;
            cp.attack_ms = 5.0f;
            cp.release_ms = 120.0f;
            cp.makeup_gain_db = 0.0f;
            c.set_parameters(cp);
            c.prepare(sr, 1);
            c.set_enabled(true);
            c.reset();
            auto in = make_sine(300.0, n, amp);
            auto buf = in;
            ProcessContext ctx{buf.data(), n, 1, sr};
            c.process(ctx);
            // Measure steady state over the final quarter (attack has settled).
            return peak(buf, 3072, n) / peak(in, 3072, n);
        };

        const double r_loud = ratio_for(0.5);   // -6 dBFS, above threshold
        const double r_mid = ratio_for(0.1);    // -20 dBFS, above threshold
        const double r_quiet = ratio_for(0.02); // -34 dBFS, below threshold

        CHECK_BETWEEN(r_quiet, 0.95, 1.05); // below threshold: ~unity
        CHECK(r_loud < 0.4, "loud signal must be compressed");
        CHECK(r_loud < r_mid, "louder input must receive more gain reduction");
        CHECK(r_mid < r_quiet, "compression must increase with level");
    }

    // --- Parametric EQ -----------------------------------------------------
    void test_parametric_eq()
    {
        const uint32_t sr = static_cast<uint32_t>(kSampleRate);

        auto center_ratio = [&](double gain_db)
        {
            const size_t n = 4096;
            ParametricEQ e;
            e.set_bands({EqBand{1000.0f, static_cast<float>(gain_db), 1.0f}});
            e.prepare(sr, 1);
            e.set_enabled(true);
            auto in = make_sine(1000.0, n, 0.3);
            auto buf = in;
            ProcessContext ctx{buf.data(), n, 1, sr};
            e.process(ctx);
            return peak(buf, 3072, n) / peak(in, 3072, n);
        };

        // 0 dB band is an exact identity.
        {
            const size_t n = 4096;
            ParametricEQ e;
            e.set_bands({EqBand{1000.0f, 0.0f, 1.0f}});
            e.prepare(sr, 1);
            e.set_enabled(true);
            auto in = make_sine(700.0, n, 0.3);
            auto buf = in;
            ProcessContext ctx{buf.data(), n, 1, sr};
            e.process(ctx);
            double max_diff = 0.0;
            for (size_t i = 0; i < n; ++i)
            {
                max_diff = std::max(max_diff, static_cast<double>(std::fabs(buf[i] - in[i])));
            }
            CHECK(max_diff < 1e-5, "0 dB EQ band must be identity");
        }

        // +12 dB boost at the band centre ~ x3.98; -12 dB cut ~ x0.251.
        CHECK_BETWEEN(center_ratio(12.0), 3.5, 4.4);
        CHECK_BETWEEN(center_ratio(-12.0), 0.22, 0.29);

        // A tone a decade below a +12 dB peaking band is essentially untouched.
        {
            const size_t n = 4096;
            ParametricEQ e;
            e.set_bands({EqBand{1000.0f, 12.0f, 1.0f}});
            e.prepare(sr, 1);
            e.set_enabled(true);
            auto in = make_sine(100.0, n, 0.3);
            auto buf = in;
            ProcessContext ctx{buf.data(), n, 1, sr};
            e.process(ctx);
            CHECK_BETWEEN(peak(buf, 3072, n) / peak(in, 3072, n), 0.85, 1.2);
        }
    }

} // namespace

int main()
{
    test_pitch_shifter();
    test_formant_shifter();
    test_auto_tune();
    test_gate();
    test_compressor();
    test_parametric_eq();

    if (g_failures != 0)
    {
        std::fprintf(stderr, "effects_test: %d check(s) failed\n", g_failures);
        return 1;
    }
    std::fprintf(stderr, "effects_test: all checks passed\n");
    return 0;
}
