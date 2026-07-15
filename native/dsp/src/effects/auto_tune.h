#pragma once

/**
 * @file auto_tune.h
 * @brief Auto-tune effect which detects pitch and snaps notes to a musical
 * scale with smoothing, humanize and formant-preserving options.
 */

#include <array>
#include <cstdint>
#include <vector>

#include "effect_base.h"
#include "pitch_shifter.h"

namespace echidna::dsp::effects
{

    /** Musical key enumeration for AutoTune. */
    enum class MusicalKey
    {
        kC = 0,
        kCSharp,
        kD,
        kDSharp,
        kE,
        kF,
        kFSharp,
        kG,
        kGSharp,
        kA,
        kASharp,
        kB
    };

    /** Musical scale selection used to pick target pitches. */
    enum class ScaleType
    {
        kMajor,
        kMinor,
        kChromatic,
        kDorian,
        kPhrygian,
        kLydian,
        kMixolydian,
        kAeolian,
        kLocrian
    };

    /** Parameters controlling auto-tune behaviour and heuristics. */
    struct AutoTuneParameters
    {
        MusicalKey key{MusicalKey::kC};
        ScaleType scale{ScaleType::kChromatic};
        float retune_speed_ms{50.0f};
        float humanize{50.0f};
        float flex_tune{0.0f};
        bool formant_preserve{false};
        float snap_strength{100.0f};
    };

    /** AutoTune effect implementation. */
    class AutoTune : public EffectProcessor
    {
    public:
        /** Configure auto-tune parameters. */
        void set_parameters(const AutoTuneParameters &params);

        /** Prepare internal buffers/state for the sample rate and channels. */
        void prepare(uint32_t sample_rate, uint32_t channels) override;
        /** Reset filter and tracking state. */
        void reset() override;
        /** Reserve callback scratch outside the audio thread. */
        void prepare_realtime(size_t max_frames);
        /** Perform pitch detection + correction across `ctx.frames`. */
        void process(ProcessContext &ctx) override;

    private:
        /** Detect the dominant pitch (Hz) from samples for a given channel. */
        float detect_pitch(const float *samples, size_t frames, uint32_t channel);
        /** Append an interleaved callback to the preallocated analysis ring. */
        void append_analysis_history(const float *samples, size_t frames);
        /** Copy one channel's available history into chronological order. */
        size_t copy_analysis_channel(uint32_t channel);
        /** Map an input frequency to a target pitch based on selected key/scale. */
        float target_pitch(float input_hz) const;

        AutoTuneParameters params_{};
        std::vector<float> scratch_;
        std::vector<float> analysis_history_;
        std::vector<float> analysis_scratch_;
        std::vector<float> last_pitch_;
        std::vector<float> detected_pitch_;
        PitchShifter correction_shifter_;
        size_t analysis_window_frames_{0};
        size_t analysis_write_frame_{0};
        size_t analysis_frames_{0};
        size_t analysis_frames_since_detection_{0};
        size_t analysis_hop_frames_{1};
        size_t max_block_frames_{0};
    };

} // namespace echidna::dsp::effects
