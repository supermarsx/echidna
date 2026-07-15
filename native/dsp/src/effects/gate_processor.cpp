#include "gate_processor.h"

/**
 * @file gate_processor.cpp
 * @brief Gate processor math and runtime implementation.
 */

#include <algorithm>
#include <cmath>

namespace echidna::dsp::effects
{
    namespace
    {
        float db_to_amplitude(float db)
        {
            return std::pow(10.0f, db / 20.0f);
        }

        float ms_to_coeff(float ms, uint32_t sample_rate)
        {
            const float samples = (ms / 1000.0f) * static_cast<float>(sample_rate);
            if (samples <= 1.0f)
            {
                return 0.0f;
            }
            return std::exp(-1.0f / samples);
        }
    } // namespace

    /** Set gate parameters (threshold/attack/release/hysteresis). */
    void GateProcessor::set_parameters(const GateParameters &params)
    {
        params_ = params;
    }

    /** Compute attack/release coefficients for the configured sample rate. */
    void GateProcessor::prepare(uint32_t sample_rate, uint32_t channels)
    {
        EffectProcessor::prepare(sample_rate, channels);
        attack_coeff_ = ms_to_coeff(params_.attack_ms, sample_rate);
        release_coeff_ = ms_to_coeff(params_.release_ms, sample_rate);
    }

    /** Reset internal envelope and gain. */
    void GateProcessor::reset()
    {
        envelope_ = 0.0f;
        gain_ = 1.0f;
        gate_open_ = false;
    }

    /** Apply gating algorithm to the provided buffer in-place. */
    void GateProcessor::process(ProcessContext &ctx)
    {
        if (!enabled_)
        {
            return;
        }

        const float open_amp =
            db_to_amplitude(params_.threshold_db + params_.hysteresis_db);
        const float close_amp =
            db_to_amplitude(params_.threshold_db - params_.hysteresis_db);

        if (ctx.buffer == nullptr || ctx.frames == 0 || ctx.channels == 0)
        {
            return;
        }

        for (size_t frame = 0; frame < ctx.frames; ++frame)
        {
            float level = 0.0f;
            const size_t frame_offset = frame * ctx.channels;
            for (uint32_t channel = 0; channel < ctx.channels; ++channel)
            {
                level = std::max(level, std::abs(ctx.buffer[frame_offset + channel]));
            }

            const float envelope_coeff = level > envelope_ ? attack_coeff_ : release_coeff_;
            envelope_ = envelope_coeff * envelope_ + (1.0f - envelope_coeff) * level;

            if (gate_open_)
            {
                if (envelope_ <= close_amp)
                {
                    gate_open_ = false;
                }
            }
            else if (envelope_ >= open_amp)
            {
                gate_open_ = true;
            }

            const float target_gain = gate_open_ ? 1.0f : 0.0f;
            const float gain_coeff = gate_open_ ? attack_coeff_ : release_coeff_;
            gain_ = gain_coeff * gain_ + (1.0f - gain_coeff) * target_gain;
            gain_ = std::clamp(gain_, 0.0f, 1.0f);

            for (uint32_t channel = 0; channel < ctx.channels; ++channel)
            {
                ctx.buffer[frame_offset + channel] *= gain_;
            }
        }
    }

} // namespace echidna::dsp::effects
