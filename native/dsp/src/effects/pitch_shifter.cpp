#include "pitch_shifter.h"

/**
 * @file pitch_shifter.cpp
 * @brief Implementations for the PitchShifter and various backends.
 */

#include <algorithm>
#include <cmath>
#include <cstdint>
#include <cstring>
#include <memory>
#include <vector>

#if defined(__ANDROID__) || defined(__linux__)
#include <dlfcn.h>
#endif

namespace echidna::dsp::effects
{
    namespace
    {
        constexpr size_t kDefaultRealtimeFrames = 8192;

        class GranularBackend : public PitchBackend
        {
        public:
            void configure(uint32_t sample_rate,
                           uint32_t channels,
                           float ratio,
                           bool preserve_formants) override
            {
                (void)preserve_formants;
                channels_ = channels;
                ratio_ = std::isfinite(ratio) ? std::clamp(ratio, 0.5f, 2.0f) : 1.0f;
                realtime_ratio_mode_ = false;
                delay_span_frames_ = std::max<size_t>(64, sample_rate / 100);
                delay_capacity_frames_ = delay_span_frames_ + 4;
                delay_buffer_.assign(delay_capacity_frames_ * channels_, 0.0f);
                phases_.assign(channels_, 0.0f);
                wet_step_ = 1.0f /
                            std::max(1.0f, static_cast<float>(sample_rate) * 0.005f);
                reset();
            }

            void set_realtime_ratio(float ratio) override
            {
                ratio_ = std::isfinite(ratio) ? std::clamp(ratio, 0.5f, 2.0f) : 1.0f;
                realtime_ratio_mode_ = true;
            }

            void reset() override
            {
                std::fill(delay_buffer_.begin(), delay_buffer_.end(), 0.0f);
                std::fill(phases_.begin(), phases_.end(), 0.0f);
                write_frame_ = 0;
                wet_mix_ = 0.0f;
            }

            void process(const float *input,
                         float *output,
                         size_t frames) override
            {
                if (frames == 0 || channels_ == 0 || delay_capacity_frames_ == 0)
                {
                    return;
                }

                if (!realtime_ratio_mode_)
                {
                    process_legacy(input, output, frames);
                    return;
                }

                constexpr double kPi = 3.14159265358979323846;
                const bool active = std::abs(ratio_ - 1.0f) >= 1.0e-4f;
                const double phase_step =
                    (1.0 - static_cast<double>(ratio_)) /
                    static_cast<double>(delay_span_frames_);

                for (size_t frame = 0; frame < frames; ++frame)
                {
                    const size_t write_index =
                        static_cast<size_t>(write_frame_ % delay_capacity_frames_);
                    for (uint32_t channel = 0; channel < channels_; ++channel)
                    {
                        delay_buffer_[write_index * channels_ + channel] =
                            input[frame * channels_ + channel];
                    }

                    const bool history_ready = write_frame_ > delay_span_frames_ + 2;
                    if (!active || !history_ready)
                    {
                        for (uint32_t channel = 0; channel < channels_; ++channel)
                        {
                            output[frame * channels_ + channel] =
                                input[frame * channels_ + channel];
                        }
                        wet_mix_ = 0.0f;
                    }
                    else
                    {
                        wet_mix_ = std::min(1.0f, wet_mix_ + wet_step_);
                        for (uint32_t channel = 0; channel < channels_; ++channel)
                        {
                            double phase_a = phases_[channel];
                            double phase_b = phase_a + 0.5;
                            if (phase_b >= 1.0)
                            {
                                phase_b -= 1.0;
                            }

                            const double delay_a = 2.0 +
                                                   phase_a * delay_span_frames_;
                            const double delay_b = 2.0 +
                                                   phase_b * delay_span_frames_;
                            const float sample_a = read_delayed(delay_a, channel);
                            const float sample_b = read_delayed(delay_b, channel);
                            const float weight_a = static_cast<float>(
                                0.5 - 0.5 * std::cos(2.0 * kPi * phase_a));
                            const float shifted = sample_a * weight_a +
                                                  sample_b * (1.0f - weight_a);
                            const float dry = input[frame * channels_ + channel];
                            output[frame * channels_ + channel] =
                                dry + (shifted - dry) * wet_mix_;

                            phase_a += phase_step;
                            phase_a -= std::floor(phase_a);
                            phases_[channel] = static_cast<float>(phase_a);
                        }
                    }
                    ++write_frame_;
                }
            }

        private:
            void process_legacy(const float *input, float *output, size_t frames)
            {
                if (frames == 1)
                {
                    std::memcpy(output, input, sizeof(float) * channels_);
                    return;
                }
                if (std::abs(ratio_ - 1.0f) < 1.0e-4f)
                {
                    std::memcpy(output, input, sizeof(float) * frames * channels_);
                    return;
                }
                for (uint32_t channel = 0; channel < channels_; ++channel)
                {
                    float phase = phases_[channel];
                    for (size_t frame = 0; frame < frames; ++frame)
                    {
                        const size_t frame0 =
                            std::min(static_cast<size_t>(phase), frames - 1);
                        const size_t frame1 = std::min(frame0 + 1, frames - 1);
                        const float fraction = phase - static_cast<float>(frame0);
                        const float sample0 = input[frame0 * channels_ + channel];
                        const float sample1 = input[frame1 * channels_ + channel];
                        output[frame * channels_ + channel] =
                            sample0 + (sample1 - sample0) * fraction;
                        phase += ratio_;
                        if (phase >= static_cast<float>(frames - 1))
                        {
                            phase -= static_cast<float>(frames - 1);
                        }
                    }
                    phases_[channel] = phase;
                }
            }

            float read_delayed(double delay_frames, uint32_t channel) const
            {
                const double position = static_cast<double>(write_frame_) - delay_frames;
                const auto frame0 = static_cast<uint64_t>(std::floor(position));
                const auto frame1 = frame0 + 1;
                const float fraction = static_cast<float>(position - std::floor(position));
                const size_t index0 = static_cast<size_t>(frame0 % delay_capacity_frames_);
                const size_t index1 = static_cast<size_t>(frame1 % delay_capacity_frames_);
                const float sample0 = delay_buffer_[index0 * channels_ + channel];
                const float sample1 = delay_buffer_[index1 * channels_ + channel];
                return sample0 + (sample1 - sample0) * fraction;
            }

            uint32_t channels_{1};
            float ratio_{1.0f};
            size_t delay_span_frames_{0};
            size_t delay_capacity_frames_{0};
            uint64_t write_frame_{0};
            float wet_mix_{0.0f};
            float wet_step_{1.0f};
            bool realtime_ratio_mode_{false};
            std::vector<float> delay_buffer_;
            std::vector<float> phases_;
        };

        class PhaseVocoderBackend : public PitchBackend
        {
        public:
            void configure(uint32_t sample_rate,
                           uint32_t channels,
                           float ratio,
                           bool preserve_formants) override
            {
                sample_rate_ = sample_rate;
                channels_ = channels;
                ratio_ = ratio;
                preserve_formants_ = preserve_formants;
                previous_.assign(channels_, 0.0f);
            }

            void reset() override
            {
                std::fill(previous_.begin(), previous_.end(), 0.0f);
            }

            void set_realtime_ratio(float ratio) override
            {
                ratio_ = std::isfinite(ratio) ? std::clamp(ratio, 0.5f, 2.0f) : 1.0f;
            }

            void process(const float *input,
                         float *output,
                         size_t frames) override
            {
                if (std::abs(ratio_ - 1.0f) < 1e-4f)
                {
                    std::memcpy(output, input, sizeof(float) * frames * channels_);
                    return;
                }
                // Advance the read position by `ratio_` (= 2^(semitones/12)) per
                // output sample so a downward request lowers the pitch. (Previously
                // 1/ratio_, which inverted the shift direction.)
                const float step = ratio_;
                const float smoothing = preserve_formants_ ? 0.35f : 0.2f;
                for (uint32_t ch = 0; ch < channels_; ++ch)
                {
                    float accumulator = 0.0f;
                    float previous = previous_[ch];
                    for (size_t frame = 0; frame < frames; ++frame)
                    {
                        const float index = accumulator;
                        const size_t i0 = static_cast<size_t>(index);
                        const float frac = index - static_cast<float>(i0);
                        const size_t i1 = std::min(i0 + 1, frames - 1);
                        const float sample0 = input[i0 * channels_ + ch];
                        const float sample1 = input[i1 * channels_ + ch];
                        float value = sample0 + (sample1 - sample0) * frac;
                        value = value * (1.0f - smoothing) + previous * smoothing;
                        previous = value;
                        output[frame * channels_ + ch] = value;
                        accumulator += step;
                        if (accumulator >= static_cast<float>(frames - 1))
                        {
                            accumulator -= static_cast<float>(frames - 1);
                        }
                    }
                    previous_[ch] = previous;
                }
            }

        private:
            uint32_t sample_rate_{0};
            uint32_t channels_{1};
            float ratio_{1.0f};
            bool preserve_formants_{false};
            std::vector<float> previous_;
        };

#if defined(__ANDROID__) || defined(__linux__)
        class SoundTouchBackend : public PitchBackend
        {
        public:
            SoundTouchBackend()
            {
                handle_ = dlopen("libsoundtouch.so", RTLD_LAZY);
                if (!handle_)
                {
                    handle_ = dlopen("libSoundTouch.so", RTLD_LAZY);
                }
                if (!handle_)
                {
                    return;
                }
                create_ = reinterpret_cast<create_fn>(dlsym(handle_, "soundtouch_createInstance"));
                destroy_ = reinterpret_cast<destroy_fn>(dlsym(handle_, "soundtouch_destroyInstance"));
                set_rate_ = reinterpret_cast<set_rate_fn>(dlsym(handle_, "soundtouch_setSampleRate"));
                set_channels_ = reinterpret_cast<set_channels_fn>(dlsym(handle_, "soundtouch_setChannels"));
                set_pitch_semitones_ = reinterpret_cast<set_pitch_fn>(dlsym(handle_, "soundtouch_setPitchSemiTones"));
                put_samples_ = reinterpret_cast<put_samples_fn>(dlsym(handle_, "soundtouch_putSamples"));
                receive_samples_ =
                    reinterpret_cast<receive_samples_fn>(dlsym(handle_, "soundtouch_receiveSamples"));
                if (create_ && destroy_)
                {
                    instance_ = create_();
                }
            }

            ~SoundTouchBackend() override
            {
                if (instance_ && destroy_)
                {
                    destroy_(instance_);
                }
                if (handle_)
                {
                    dlclose(handle_);
                }
            }

            bool available() const { return instance_ && set_rate_ && set_channels_ && set_pitch_semitones_ &&
                                            put_samples_ && receive_samples_; }

            void set_realtime_ratio(float ratio) override
            {
                ratio_ = std::isfinite(ratio) ? std::clamp(ratio, 0.5f, 2.0f) : 1.0f;
                if (available())
                {
                    set_pitch_semitones_(instance_, std::log2(ratio_) * 12.0f);
                }
            }

            void configure(uint32_t sample_rate,
                           uint32_t channels,
                           float ratio,
                           bool preserve_formants) override
            {
                (void)preserve_formants;
                if (!available())
                {
                    channels_ = channels;
                    ratio_ = ratio;
                    return;
                }
                channels_ = channels;
                ratio_ = ratio;
                if (set_rate_)
                {
                    set_rate_(instance_, sample_rate);
                }
                if (set_channels_)
                {
                    set_channels_(instance_, channels);
                }
                if (set_pitch_semitones_)
                {
                    const float semitones = std::log2(ratio) * 12.0f;
                    set_pitch_semitones_(instance_, semitones);
                }
            }

            void reset() override
            {
                // The SoundTouch API clears its state when rate/tempo is set; nothing to do.
            }

            void process(const float *input,
                         float *output,
                         size_t frames) override
            {
                if (!available())
                {
                    std::memcpy(output, input, sizeof(float) * frames * channels_);
                    return;
                }
                put_samples_(instance_, input, frames);
                size_t produced = receive_samples_(instance_, output, frames);
                if (produced < frames)
                {
                    const size_t remaining = (frames - produced) * channels_;
                    std::fill(output + produced * channels_, output + produced * channels_ + remaining, 0.0f);
                }
            }

        private:
            using create_fn = void *(*)();
            using destroy_fn = void (*)(void *);
            using set_rate_fn = void (*)(void *, unsigned int);
            using set_channels_fn = void (*)(void *, unsigned int);
            using set_pitch_fn = void (*)(void *, float);
            using put_samples_fn = void (*)(void *, const float *, unsigned long);
            using receive_samples_fn = unsigned long (*)(void *, float *, unsigned long);

            void *handle_{nullptr};
            void *instance_{nullptr};
            create_fn create_{nullptr};
            destroy_fn destroy_{nullptr};
            set_rate_fn set_rate_{nullptr};
            set_channels_fn set_channels_{nullptr};
            set_pitch_fn set_pitch_semitones_{nullptr};
            put_samples_fn put_samples_{nullptr};
            receive_samples_fn receive_samples_{nullptr};
            uint32_t channels_{1};
            float ratio_{1.0f};
        };
#endif

    } // namespace

    /** Construct a pitch shifter instance. */
    PitchShifter::PitchShifter() = default;
    /** Destroy and clean up resources. */
    PitchShifter::~PitchShifter() = default;

    /** Update the current PitchParameters and rebuild backend as needed. */
    void PitchShifter::set_parameters(const PitchParameters &params)
    {
        params_ = params;
        rebuild_backend();
    }

    /** Prepare the effect and configure the chosen backend. */
    void PitchShifter::prepare(uint32_t sample_rate, uint32_t channels)
    {
        EffectProcessor::prepare(sample_rate, channels);
        scratch_.reserve(kDefaultRealtimeFrames * static_cast<size_t>(channels));
        rebuild_backend();
    }

    /** Reset the active backend to its initial state. */
    void PitchShifter::reset()
    {
        if (backend_)
        {
            backend_->reset();
        }
    }

    void PitchShifter::prepare_realtime(size_t max_frames)
    {
        scratch_.reserve(max_frames * static_cast<size_t>(channels_));
    }

    void PitchShifter::set_realtime_ratio(float ratio)
    {
        if (backend_)
        {
            backend_->set_realtime_ratio(ratio);
        }
    }

    /** Compute the current semitone ratio applied by the pitch shifter. */
    float PitchShifter::ratio() const
    {
        const float semitone_offset = params_.semitones + params_.cents / 100.0f;
        return std::pow(2.0f, semitone_offset / 12.0f);
    }

    /** Run processing using the configured backend. */
    void PitchShifter::process(ProcessContext &ctx)
    {
        if (!enabled_ || !backend_ || ctx.buffer == nullptr || ctx.frames == 0 ||
            ctx.channels != channels_)
        {
            return;
        }
        const size_t samples = ctx.frames * ctx.channels;
        if (samples > scratch_.capacity())
        {
            return;
        }
        scratch_.resize(samples);
        std::copy_n(ctx.buffer, samples, scratch_.data());
        backend_->process(scratch_.data(), ctx.buffer, ctx.frames);
    }

    /** Select and reconfigure an appropriate backend implementation based on
     * parameters and available libraries. */
    void PitchShifter::rebuild_backend()
    {
        if (sample_rate_ == 0 || channels_ == 0)
        {
            return;
        }
        const float ratio_value = ratio();
        if (std::abs(ratio_value - 1.0f) < 1e-4f)
        {
            backend_ = std::make_unique<GranularBackend>();
            backend_->configure(sample_rate_, channels_, 1.0f, params_.preserve_formants);
            return;
        }
        if (params_.quality == PitchQuality::kHighQuality)
        {
#if defined(__ANDROID__) || defined(__linux__)
            auto soundtouch = std::make_unique<SoundTouchBackend>();
            if (soundtouch->available())
            {
                backend_ = std::move(soundtouch);
            }
            else
            {
                backend_ = std::make_unique<PhaseVocoderBackend>();
            }
#else
            backend_ = std::make_unique<PhaseVocoderBackend>();
#endif
        }
        else
        {
            backend_ = std::make_unique<GranularBackend>();
        }
        if (backend_)
        {
            backend_->configure(sample_rate_, channels_, ratio_value,
                                params_.preserve_formants);
        }
    }

} // namespace echidna::dsp::effects
