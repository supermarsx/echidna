#include "pitch_shifter.h"

/**
 * @file pitch_shifter.cpp
 * @brief Implementations for the PitchShifter and various backends.
 */

#include <algorithm>
#include <cmath>
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

    class GranularBackend : public PitchBackend
    {
    public:
      void configure(uint32_t sample_rate,
                     uint32_t channels,
                     float ratio,
                     bool preserve_formants) override
      {
        (void)sample_rate;
        (void)preserve_formants;
        channels_ = channels;
        ratio_ = ratio;
        phases_.assign(channels_, 0.0f);
      }

      void reset() override { std::fill(phases_.begin(), phases_.end(), 0.0f); }

      void process(const float *input,
                   float *output,
                   size_t frames) override
      {
        if (std::abs(ratio_ - 1.0f) < 1e-4f)
        {
          std::memcpy(output, input, sizeof(float) * frames * channels_);
          return;
        }
        const float inv_ratio = 1.0f / ratio_;
        for (uint32_t ch = 0; ch < channels_; ++ch)
        {
          float phase = phases_[ch];
          for (size_t frame = 0; frame < frames; ++frame)
          {
            const float index = phase;
            const size_t base = static_cast<size_t>(index);
            const float frac = index - static_cast<float>(base);
            const size_t i0 = std::min(base, frames - 1);
            const size_t i1 = std::min(base + 1, frames - 1);
            const float sample0 = input[i0 * channels_ + ch];
            const float sample1 = input[i1 * channels_ + ch];
            const float value = sample0 + (sample1 - sample0) * frac;
            output[frame * channels_ + ch] = value;
            phase += inv_ratio;
            if (phase >= static_cast<float>(frames - 1))
            {
              phase -= static_cast<float>(frames - 1);
            }
          }
          phases_[ch] = phase;
        }
      }

    private:
      uint32_t channels_{1};
      float ratio_{1.0f};
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

      void process(const float *input,
                   float *output,
                   size_t frames) override
      {
        if (std::abs(ratio_ - 1.0f) < 1e-4f)
        {
          std::memcpy(output, input, sizeof(float) * frames * channels_);
          return;
        }
        const float inv_ratio = 1.0f / ratio_;
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
            accumulator += inv_ratio;
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

  /** Compute the current semitone ratio applied by the pitch shifter. */
  float PitchShifter::ratio() const
  {
    const float semitone_offset = params_.semitones + params_.cents / 100.0f;
    return std::pow(2.0f, semitone_offset / 12.0f);
  }

  /** Run processing using the configured backend. */
  void PitchShifter::process(ProcessContext &ctx)
  {
    if (!enabled_ || !backend_)
    {
      return;
    }
    scratch_.assign(ctx.buffer, ctx.buffer + ctx.frames * ctx.channels);
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
