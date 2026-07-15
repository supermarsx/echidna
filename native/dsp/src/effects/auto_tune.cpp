#include "auto_tune.h"

/**
 * @file auto_tune.cpp
 * @brief Implementation of AutoTune detector and correction algorithms.
 */

#include <algorithm>
#include <cmath>
#include <limits>
#include <numeric>

namespace echidna::dsp::effects
{
    namespace
    {
        const std::array<int, 12> kChromatic{0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11};
        const std::array<int, 7> kMajor{0, 2, 4, 5, 7, 9, 11};
        const std::array<int, 7> kMinor{0, 2, 3, 5, 7, 8, 10};
        const std::array<int, 7> kDorian{0, 2, 3, 5, 7, 9, 10};
        const std::array<int, 7> kPhrygian{0, 1, 3, 5, 7, 8, 10};
        const std::array<int, 7> kLydian{0, 2, 4, 6, 7, 9, 11};
        const std::array<int, 7> kMixolydian{0, 2, 4, 5, 7, 9, 10};
        const std::array<int, 7> kAeolian{0, 2, 3, 5, 7, 8, 10};
        const std::array<int, 7> kLocrian{0, 1, 3, 5, 6, 8, 10};

        float midi_to_hz(float midi)
        {
            return 440.0f * std::pow(2.0f, (midi - 69.0f) / 12.0f);
        }

        float hz_to_midi(float hz)
        {
            if (hz <= 0.0f)
            {
                return 0.0f;
            }
            return 69.0f + 12.0f * std::log2(hz / 440.0f);
        }

        float clamp_frequency(float hz)
        {
            return std::clamp(hz, 40.0f, 2000.0f);
        }

        constexpr size_t kDefaultMaxBlockFrames = 4096;

    } // namespace

    /** Set AutoTune configuration parameters. */
    void AutoTune::set_parameters(const AutoTuneParameters &params)
    {
        params_ = params;
        if (sample_rate_ != 0 && channels_ != 0)
        {
            PitchParameters pitch_parameters;
            pitch_parameters.quality = PitchQuality::kLowLatency;
            pitch_parameters.preserve_formants = params_.formant_preserve;
            correction_shifter_.set_parameters(pitch_parameters);
        }
    }

    /** Prepare state and tracking buffers for processing. */
    void AutoTune::prepare(uint32_t sample_rate, uint32_t channels)
    {
        EffectProcessor::prepare(sample_rate, channels);
        last_pitch_.assign(channels, 1.0f);
        detected_pitch_.assign(channels, 0.0f);
        const size_t max_period = sample_rate == 0 ? 0 : sample_rate / 60;
        analysis_window_frames_ = std::max<size_t>(max_period * 2, 1);
        analysis_history_.assign(analysis_window_frames_ * channels, 0.0f);
        analysis_scratch_.assign(analysis_window_frames_, 0.0f);
        analysis_write_frame_ = 0;
        analysis_frames_ = 0;
        analysis_frames_since_detection_ = 0;
        analysis_hop_frames_ = std::max<size_t>(sample_rate / 100, 1);

        max_block_frames_ = std::max(max_block_frames_, kDefaultMaxBlockFrames);
        scratch_.assign(max_block_frames_ * channels, 0.0f);

        PitchParameters pitch_parameters;
        pitch_parameters.quality = PitchQuality::kLowLatency;
        pitch_parameters.preserve_formants = params_.formant_preserve;
        correction_shifter_.set_parameters(pitch_parameters);
        correction_shifter_.prepare(sample_rate, channels);
        correction_shifter_.prepare_realtime(max_block_frames_);
        correction_shifter_.set_enabled(true);
    }

    /** Reset last pitch tracking state to defaults. */
    void AutoTune::reset()
    {
        std::fill(last_pitch_.begin(), last_pitch_.end(), 1.0f);
        std::fill(detected_pitch_.begin(), detected_pitch_.end(), 0.0f);
        std::fill(analysis_history_.begin(), analysis_history_.end(), 0.0f);
        analysis_write_frame_ = 0;
        analysis_frames_ = 0;
        analysis_frames_since_detection_ = 0;
        correction_shifter_.reset();
    }

    void AutoTune::prepare_realtime(size_t max_frames)
    {
        if (max_frames <= max_block_frames_)
        {
            return;
        }
        max_block_frames_ = max_frames;
        scratch_.resize(max_block_frames_ * channels_);
        correction_shifter_.prepare_realtime(max_block_frames_);
    }

    void AutoTune::append_analysis_history(const float *samples, size_t frames)
    {
        if (analysis_window_frames_ == 0 || channels_ == 0)
        {
            return;
        }
        for (size_t frame = 0; frame < frames; ++frame)
        {
            const size_t history_offset = analysis_write_frame_ * channels_;
            const size_t input_offset = frame * channels_;
            for (uint32_t channel = 0; channel < channels_; ++channel)
            {
                analysis_history_[history_offset + channel] = samples[input_offset + channel];
            }
            analysis_write_frame_ = (analysis_write_frame_ + 1) % analysis_window_frames_;
            analysis_frames_ = std::min(analysis_frames_ + 1, analysis_window_frames_);
        }
    }

    size_t AutoTune::copy_analysis_channel(uint32_t channel)
    {
        if (channel >= channels_ || analysis_frames_ == 0)
        {
            return 0;
        }
        const size_t oldest_frame = analysis_frames_ == analysis_window_frames_
                                        ? analysis_write_frame_
                                        : 0;
        for (size_t frame = 0; frame < analysis_frames_; ++frame)
        {
            const size_t history_frame = (oldest_frame + frame) % analysis_window_frames_;
            analysis_scratch_[frame] = analysis_history_[history_frame * channels_ + channel];
        }
        return analysis_frames_;
    }

    /** Estimate the fundamental frequency from the provided mono samples. */
    float AutoTune::detect_pitch(const float *samples, size_t frames, uint32_t channel)
    {
        (void)channel;
        const size_t min_period = static_cast<size_t>(sample_rate_ / 1000.0f);
        const size_t max_period = static_cast<size_t>(sample_rate_ / 60.0f);
        if (max_period >= frames || min_period == 0)
        {
            return 0.0f;
        }

        const float mean =
            std::accumulate(samples, samples + frames, 0.0f) / static_cast<float>(frames);
        float signal_energy = 0.0f;
        for (size_t frame = 0; frame < frames; ++frame)
        {
            const float centered = samples[frame] - mean;
            signal_energy += centered * centered;
        }
        if (signal_energy / static_cast<float>(frames) < 1.0e-8f)
        {
            return 0.0f;
        }

        auto correlation_at = [&](size_t period)
        {
            float correlation = 0.0f;
            for (size_t frame = 0; frame + period < frames; ++frame)
            {
                correlation += (samples[frame] - mean) * (samples[frame + period] - mean);
            }
            return correlation;
        };

        float best_corr = 0.0f;
        size_t best_period = 0;
        for (size_t period = min_period; period <= max_period; ++period)
        {
            const float corr = correlation_at(period);
            if (corr > best_corr)
            {
                best_corr = corr;
                best_period = period;
            }
        }
        if (best_period == 0)
        {
            return 0.0f;
        }

        float leading_energy = 0.0f;
        float trailing_energy = 0.0f;
        for (size_t frame = 0; frame + best_period < frames; ++frame)
        {
            const float leading = samples[frame] - mean;
            const float trailing = samples[frame + best_period] - mean;
            leading_energy += leading * leading;
            trailing_energy += trailing * trailing;
        }
        const float normalization = std::sqrt(leading_energy * trailing_energy);
        if (normalization <= 0.0f || best_corr / normalization < 0.65f)
        {
            return 0.0f;
        }

        float refined_period = static_cast<float>(best_period);
        if (best_period > min_period && best_period < max_period)
        {
            const float previous = correlation_at(best_period - 1);
            const float next = correlation_at(best_period + 1);
            const float denominator = previous - 2.0f * best_corr + next;
            if (std::abs(denominator) > 1.0e-12f)
            {
                const float offset = 0.5f * (previous - next) / denominator;
                refined_period += std::clamp(offset, -0.5f, 0.5f);
            }
        }
        return static_cast<float>(sample_rate_) / refined_period;
    }

    /** Compute the nearest target pitch (Hz) from input frequency using
     * configured key and scale. */
    float AutoTune::target_pitch(float input_hz) const
    {
        const float midi = hz_to_midi(input_hz);
        const int key = static_cast<int>(params_.key);
        float best_midi = midi;
        float best_diff = std::numeric_limits<float>::max();

        auto evaluate_pattern = [&](const auto &pattern)
        {
            for (int interval : pattern)
            {
                float candidate = static_cast<float>(key + interval);
                float octave = std::round((midi - candidate) / 12.0f);
                candidate += 12.0f * octave;
                const float diff = std::abs(candidate - midi);
                if (diff < best_diff)
                {
                    best_diff = diff;
                    best_midi = candidate;
                }
            }
        };

        switch (params_.scale)
        {
        case ScaleType::kMajor:
            evaluate_pattern(kMajor);
            break;
        case ScaleType::kMinor:
            evaluate_pattern(kMinor);
            break;
        case ScaleType::kChromatic:
            evaluate_pattern(kChromatic);
            break;
        case ScaleType::kDorian:
            evaluate_pattern(kDorian);
            break;
        case ScaleType::kPhrygian:
            evaluate_pattern(kPhrygian);
            break;
        case ScaleType::kLydian:
            evaluate_pattern(kLydian);
            break;
        case ScaleType::kMixolydian:
            evaluate_pattern(kMixolydian);
            break;
        case ScaleType::kAeolian:
            evaluate_pattern(kAeolian);
            break;
        case ScaleType::kLocrian:
            evaluate_pattern(kLocrian);
            break;
        }

        return clamp_frequency(midi_to_hz(best_midi));
    }

    /** Run pitch detection + correction across ctx.frames for each channel. */
    void AutoTune::process(ProcessContext &ctx)
    {
        if (!enabled_)
        {
            return;
        }
        if (ctx.buffer == nullptr || ctx.frames == 0 || ctx.channels != channels_ ||
            ctx.frames > max_block_frames_)
        {
            return;
        }

        append_analysis_history(ctx.buffer, ctx.frames);
        analysis_frames_since_detection_ += ctx.frames;
        const size_t required_history = sample_rate_ / 60 + 1;
        const bool update_detection = analysis_frames_ >= required_history &&
                                      analysis_frames_since_detection_ >= analysis_hop_frames_;
        if (update_detection)
        {
            analysis_frames_since_detection_ %= analysis_hop_frames_;
        }
        const float snap = std::clamp(params_.snap_strength, 0.0f, 100.0f) / 100.0f;
        const float flex = std::clamp(params_.flex_tune, 0.0f, 100.0f) / 100.0f;
        const float humanize = std::clamp(params_.humanize, 0.0f, 100.0f) / 100.0f;
        const float retune_ms = std::clamp(params_.retune_speed_ms, 1.0f, 200.0f);
        const float retune_frames =
            (retune_ms / 1000.0f) * static_cast<float>(sample_rate_);
        const float coeff = std::exp(-static_cast<float>(ctx.frames) /
                                     std::max(retune_frames, 1.0f));

        float correction_ratio = 0.0f;
        uint32_t voiced_channels = 0;
        for (uint32_t ch = 0; ch < channels_; ++ch)
        {
            if (update_detection)
            {
                const size_t analysis_frames = copy_analysis_channel(ch);
                detected_pitch_[ch] =
                    detect_pitch(analysis_scratch_.data(), analysis_frames, ch);
            }
            const float detected = detected_pitch_[ch];
            if (detected <= 0.0f)
            {
                continue;
            }
            const float target = target_pitch(detected);
            float ratio = target / detected;
            const float correction_cents = 1200.0f * std::log2(ratio);
            if (std::abs(correction_cents) <= 10.0f)
            {
                ratio = 1.0f;
            }
            ratio = 1.0f + (ratio - 1.0f) * snap;
            ratio = ratio * (1.0f - flex) + 1.0f * flex;
            ratio = ratio * (1.0f - humanize) + 1.0f * humanize;
            float smoothed = last_pitch_[ch];
            smoothed += (ratio - smoothed) * (1.0f - coeff);
            last_pitch_[ch] = smoothed;
            correction_ratio += smoothed;
            ++voiced_channels;
        }

        correction_ratio = voiced_channels == 0
                               ? 1.0f
                               : correction_ratio / static_cast<float>(voiced_channels);
        if (params_.formant_preserve)
        {
            std::copy_n(ctx.buffer, ctx.frames * ctx.channels, scratch_.data());
        }
        correction_shifter_.set_realtime_ratio(correction_ratio);
        correction_shifter_.process(ctx);
        if (params_.formant_preserve)
        {
            const size_t samples = ctx.frames * ctx.channels;
            for (size_t sample = 0; sample < samples; ++sample)
            {
                ctx.buffer[sample] = ctx.buffer[sample] * 0.85f + scratch_[sample] * 0.15f;
            }
        }
    }

} // namespace echidna::dsp::effects
