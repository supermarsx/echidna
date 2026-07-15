#include "dsp/stream_handle_registry.h"

#include <bit>
#include <cmath>
#include <cstring>
#include <limits>
#include <memory>
#include <new>
#include <thread>

namespace echidna::dsp_runtime
{
    namespace
    {
        constexpr size_t kMaxRealtimeSamples = 32768;
        constexpr uint64_t kMaxProfileGeneration =
            static_cast<uint64_t>(std::numeric_limits<int64_t>::max());

        echidna_result_t ConvertStatus(ech_dsp_status_t status)
        {
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

        int16_t EncodeSigned16(float sample)
        {
            const float clamped = sample < -1.0f ? -1.0f : (sample > 1.0f ? 1.0f : sample);
            if (clamped <= -1.0f)
            {
                return std::numeric_limits<int16_t>::min();
            }
            return static_cast<int16_t>(std::lround(clamped * 32767.0f));
        }
    } // namespace

    static_assert(std::atomic<uint32_t>::is_always_lock_free,
                  "stream handles require lock-free 32-bit callback atomics");
    static_assert(StreamHandleRegistry::kMaxStreams == 64,
                  "stream token layout reserves exactly six slot bits");

    StreamHandleRegistry::EngineState::~EngineState()
    {
        if (engine && destroy)
        {
            destroy(engine);
        }
    }

    StreamHandleRegistry::MaintenanceGuard::MaintenanceGuard(
        StreamHandleRegistry &registry)
        : registry_(registry)
    {
        registry_.lockMaintenance();
    }

    StreamHandleRegistry::MaintenanceGuard::~MaintenanceGuard()
    {
        registry_.unlockMaintenance();
    }

    void StreamHandleRegistry::lockMaintenance()
    {
        uint32_t expected = 0;
        while (!maintenance_gate_.compare_exchange_weak(expected,
                                                        1,
                                                        std::memory_order_acquire,
                                                        std::memory_order_relaxed))
        {
            expected = 0;
            std::this_thread::yield();
        }
    }

    void StreamHandleRegistry::unlockMaintenance()
    {
        maintenance_gate_.store(0, std::memory_order_release);
    }

    bool StreamHandleRegistry::validConfig(const echidna_stream_config_t &config)
    {
        if (config.struct_size != sizeof(echidna_stream_config_t) ||
            config.sample_rate < 8000 || config.sample_rate > 384000 ||
            config.channel_count == 0 || config.channel_count > 8 ||
            config.max_frames == 0 ||
            config.max_frames > kMaxRealtimeSamples / config.channel_count ||
            (config.format != ECHIDNA_PCM_FORMAT_SIGNED_16 &&
             config.format != ECHIDNA_PCM_FORMAT_FLOAT_32))
        {
            return false;
        }
        for (uint32_t reserved : config.reserved)
        {
            if (reserved != 0)
            {
                return false;
            }
        }
        return true;
    }

    bool StreamHandleRegistry::decodeHandle(echidna_stream_handle_t handle,
                                            size_t *slot,
                                            uint32_t *generation)
    {
        if (handle == 0 || !slot || !generation)
        {
            return false;
        }
        const uint32_t token_generation = handle >> kSlotBits;
        const size_t token_slot = static_cast<size_t>(handle & (kMaxStreams - 1));
        if (token_generation == 0 || token_generation > kMaxHandleGeneration ||
            token_slot >= kMaxStreams)
        {
            return false;
        }
        *slot = token_slot;
        *generation = token_generation;
        return true;
    }

    echidna_stream_handle_t StreamHandleRegistry::makeHandle(size_t slot,
                                                             uint32_t generation)
    {
        if (slot >= kMaxStreams || generation == 0 ||
            generation > kMaxHandleGeneration)
        {
            return 0;
        }
        return static_cast<echidna_stream_handle_t>((generation << kSlotBits) | slot);
    }

    StreamHandleRegistry::EngineState *StreamHandleRegistry::buildState(
        const echidna_stream_config_t &config,
        const char *profile_json,
        size_t length,
        const StreamDspBackend &backend,
        echidna_result_t *result)
    {
        if (result)
        {
            *result = ECHIDNA_RESULT_ERROR;
        }
        if (!validConfig(config) || !backend.complete() ||
            (profile_json == nullptr) != (length == 0))
        {
            if (result)
            {
                *result = ECHIDNA_RESULT_INVALID_ARGUMENT;
            }
            return nullptr;
        }
        try
        {
            auto state = std::make_unique<EngineState>();
            const size_t samples = static_cast<size_t>(config.max_frames) *
                                   static_cast<size_t>(config.channel_count);
            state->input_scratch.resize(samples);
            state->output_scratch.resize(samples);
            const ech_dsp_status_t status = backend.create(config.sample_rate,
                                                           config.channel_count,
                                                           ECH_DSP_QUALITY_BALANCED,
                                                           config.max_frames,
                                                           profile_json,
                                                           length,
                                                           &state->engine);
            if (status != ECH_DSP_STATUS_OK || !state->engine)
            {
                if (result)
                {
                    *result = ConvertStatus(status);
                }
                return nullptr;
            }
            state->process = backend.process;
            state->destroy = backend.destroy;
            state->sample_rate = config.sample_rate;
            state->channels = config.channel_count;
            state->max_frames = config.max_frames;
            state->format = config.format;
            if (result)
            {
                *result = ECHIDNA_RESULT_OK;
            }
            return state.release();
        }
        catch (...)
        {
            return nullptr;
        }
    }

    bool StreamHandleRegistry::acquire(Slot &slot, uint32_t generation)
    {
        uint32_t usage = slot.usage.load(std::memory_order_acquire);
        while ((usage & kActiveMask) != 0)
        {
            if ((usage & kInFlightMask) == kInFlightMask)
            {
                return false;
            }
            if (slot.usage.compare_exchange_weak(usage,
                                                 usage + 1,
                                                 std::memory_order_acq_rel,
                                                 std::memory_order_acquire))
            {
                if (slot.generation.load(std::memory_order_acquire) != generation)
                {
                    release(slot);
                    return false;
                }
                return true;
            }
        }
        return false;
    }

    void StreamHandleRegistry::release(Slot &slot)
    {
        slot.usage.fetch_sub(1, std::memory_order_acq_rel);
    }

    echidna_result_t StreamHandleRegistry::create(const echidna_stream_config_t &config,
                                                  const StreamDspBackend &backend,
                                                  echidna_stream_handle_t *handle)
    {
        if (!handle || !validConfig(config) || !backend.complete())
        {
            return ECHIDNA_RESULT_INVALID_ARGUMENT;
        }
        *handle = 0;
        MaintenanceGuard guard(*this);
        for (size_t index = 0; index < slots_.size(); ++index)
        {
            Slot &slot = slots_[index];
            if (slot.allocated || slot.usage.load(std::memory_order_acquire) != 0)
            {
                continue;
            }
            const uint32_t previous_generation =
                slot.generation.load(std::memory_order_relaxed);
            if (previous_generation >= kMaxHandleGeneration)
            {
                slot.usage.store(kExhaustedMask, std::memory_order_release);
                continue;
            }
            echidna_result_t build_result = ECHIDNA_RESULT_ERROR;
            std::unique_ptr<EngineState> state(
                buildState(config, nullptr, 0, backend, &build_result));
            if (!state)
            {
                return build_result;
            }
            const uint32_t generation = previous_generation + 1;
            slot.allocated = true;
            slot.state = state.release();
            slot.profile_generation = 0;
            slot.callback_gate.store(0, std::memory_order_relaxed);
            slot.policy_enabled.store(1, std::memory_order_relaxed);
            slot.generation.store(generation, std::memory_order_relaxed);
            slot.usage.store(kActiveMask, std::memory_order_release);
            *handle = makeHandle(index, generation);
            return *handle == 0 ? ECHIDNA_RESULT_ERROR : ECHIDNA_RESULT_OK;
        }
        return ECHIDNA_RESULT_NOT_AVAILABLE;
    }

    void StreamHandleRegistry::copyBypass(const EngineState &state,
                                          const void *input,
                                          void *output,
                                          uint32_t frames)
    {
        if (input == output)
        {
            return;
        }
        const size_t samples = static_cast<size_t>(frames) * state.channels;
        const size_t bytes_per_sample =
            state.format == ECHIDNA_PCM_FORMAT_SIGNED_16 ? sizeof(int16_t) : sizeof(float);
        std::memcpy(output, input, samples * bytes_per_sample);
    }

    echidna_result_t StreamHandleRegistry::process(echidna_stream_handle_t handle,
                                                   const void *input,
                                                   void *output,
                                                   uint32_t frames,
                                                   uint32_t format,
                                                   bool globally_bypassed,
                                                   bool *mutated,
                                                   bool *bypassed)
    {
        if (mutated)
        {
            *mutated = false;
        }
        if (bypassed)
        {
            *bypassed = false;
        }
        size_t index = 0;
        uint32_t generation = 0;
        if (!input || !output || frames == 0 ||
            !decodeHandle(handle, &index, &generation))
        {
            return ECHIDNA_RESULT_INVALID_ARGUMENT;
        }
        Slot &slot = slots_[index];
        if (!acquire(slot, generation))
        {
            return ECHIDNA_RESULT_NOT_INITIALISED;
        }

        uint32_t expected = 0;
        if (!slot.callback_gate.compare_exchange_strong(expected,
                                                        kCallbackProcessing,
                                                        std::memory_order_acq_rel,
                                                        std::memory_order_relaxed))
        {
            release(slot);
            return ECHIDNA_RESULT_NOT_AVAILABLE;
        }
        if (slot.generation.load(std::memory_order_acquire) != generation)
        {
            slot.callback_gate.store(0, std::memory_order_release);
            release(slot);
            return ECHIDNA_RESULT_NOT_AVAILABLE;
        }

        EngineState *state = slot.state;
        echidna_result_t result = ECHIDNA_RESULT_OK;
        if (!state || format != state->format || frames > state->max_frames)
        {
            result = ECHIDNA_RESULT_INVALID_ARGUMENT;
        }
        else if (globally_bypassed ||
                 slot.policy_enabled.load(std::memory_order_acquire) == 0)
        {
            copyBypass(*state, input, output, frames);
            if (bypassed)
            {
                *bypassed = true;
            }
        }
        else
        {
            const size_t samples = static_cast<size_t>(frames) * state->channels;
            if (format == ECHIDNA_PCM_FORMAT_SIGNED_16)
            {
                const auto *source = static_cast<const int16_t *>(input);
                for (size_t i = 0; i < samples; ++i)
                {
                    state->input_scratch[i] = static_cast<float>(source[i]) / 32768.0f;
                }
            }
            else
            {
                const auto *source = static_cast<const float *>(input);
                for (size_t i = 0; i < samples; ++i)
                {
                    if (!std::isfinite(source[i]))
                    {
                        result = ECHIDNA_RESULT_INVALID_ARGUMENT;
                        break;
                    }
                    state->input_scratch[i] = source[i];
                }
            }

            if (result == ECHIDNA_RESULT_OK)
            {
                result = ConvertStatus(state->process(state->engine,
                                                      state->input_scratch.data(),
                                                      state->output_scratch.data(),
                                                      frames));
            }
            if (result == ECHIDNA_RESULT_OK)
            {
                bool changed = false;
                if (format == ECHIDNA_PCM_FORMAT_SIGNED_16)
                {
                    const auto *source = static_cast<const int16_t *>(input);
                    auto *destination = static_cast<int16_t *>(output);
                    for (size_t i = 0; i < samples; ++i)
                    {
                        const float processed = state->output_scratch[i];
                        if (!std::isfinite(processed))
                        {
                            result = ECHIDNA_RESULT_ERROR;
                            break;
                        }
                        const bool float_changed =
                            std::bit_cast<uint32_t>(processed) !=
                            std::bit_cast<uint32_t>(state->input_scratch[i]);
                        const int16_t encoded =
                            float_changed ? EncodeSigned16(processed) : source[i];
                        destination[i] = encoded;
                        changed = changed || encoded != source[i];
                    }
                }
                else
                {
                    const auto *source = static_cast<const float *>(input);
                    auto *destination = static_cast<float *>(output);
                    for (size_t i = 0; i < samples; ++i)
                    {
                        const float processed = state->output_scratch[i];
                        if (!std::isfinite(processed))
                        {
                            result = ECHIDNA_RESULT_ERROR;
                            break;
                        }
                        destination[i] = processed;
                        changed = changed ||
                                  std::bit_cast<uint32_t>(processed) !=
                                      std::bit_cast<uint32_t>(source[i]);
                    }
                }
                if (mutated && result == ECHIDNA_RESULT_OK)
                {
                    *mutated = changed;
                }
            }
            if (result != ECHIDNA_RESULT_OK)
            {
                copyBypass(*state, input, output, frames);
            }
        }

        slot.callback_gate.store(0, std::memory_order_release);
        release(slot);
        return result;
    }

    echidna_result_t StreamHandleRegistry::update(echidna_stream_handle_t handle,
                                                  const char *profile_json,
                                                  size_t length,
                                                  uint64_t profile_generation,
                                                  const StreamDspBackend &backend)
    {
        if ((profile_json == nullptr) != (length == 0) || profile_generation == 0 ||
            profile_generation > kMaxProfileGeneration || !backend.complete())
        {
            return ECHIDNA_RESULT_INVALID_ARGUMENT;
        }
        size_t index = 0;
        uint32_t generation = 0;
        if (!decodeHandle(handle, &index, &generation))
        {
            return ECHIDNA_RESULT_INVALID_ARGUMENT;
        }

        MaintenanceGuard guard(*this);
        Slot &slot = slots_[index];
        if (!slot.allocated ||
            slot.generation.load(std::memory_order_acquire) != generation ||
            (slot.usage.load(std::memory_order_acquire) & kActiveMask) == 0)
        {
            return ECHIDNA_RESULT_NOT_INITIALISED;
        }
        if (profile_generation <= slot.profile_generation)
        {
            return ECHIDNA_RESULT_INVALID_ARGUMENT;
        }
        if (!profile_json)
        {
            slot.profile_generation = profile_generation;
            slot.policy_enabled.store(0, std::memory_order_release);
            return ECHIDNA_RESULT_OK;
        }

        EngineState *current = slot.state;
        if (!current)
        {
            return ECHIDNA_RESULT_NOT_INITIALISED;
        }
        echidna_stream_config_t config{};
        config.struct_size = sizeof(config);
        config.sample_rate = current->sample_rate;
        config.channel_count = current->channels;
        config.max_frames = current->max_frames;
        config.format = current->format;
        echidna_result_t build_result = ECHIDNA_RESULT_ERROR;
        std::unique_ptr<EngineState> replacement(
            buildState(config, profile_json, length, backend, &build_result));
        if (!replacement)
        {
            return build_result;
        }

        uint32_t expected = 0;
        while (!slot.callback_gate.compare_exchange_weak(expected,
                                                         kCallbackMaintenance,
                                                         std::memory_order_acq_rel,
                                                         std::memory_order_relaxed))
        {
            expected = 0;
            std::this_thread::yield();
        }
        slot.state = replacement.release();
        slot.profile_generation = profile_generation;
        slot.policy_enabled.store(1, std::memory_order_release);
        slot.callback_gate.store(0, std::memory_order_release);
        delete current;
        return ECHIDNA_RESULT_OK;
    }

    echidna_result_t StreamHandleRegistry::destroy(echidna_stream_handle_t handle)
    {
        size_t index = 0;
        uint32_t generation = 0;
        if (!decodeHandle(handle, &index, &generation))
        {
            return ECHIDNA_RESULT_INVALID_ARGUMENT;
        }
        MaintenanceGuard guard(*this);
        Slot &slot = slots_[index];
        if (!slot.allocated ||
            slot.generation.load(std::memory_order_acquire) != generation)
        {
            return ECHIDNA_RESULT_NOT_INITIALISED;
        }

        slot.policy_enabled.store(0, std::memory_order_release);
        slot.usage.fetch_and(~kActiveMask, std::memory_order_acq_rel);
        uint32_t expected = 0;
        while (!slot.callback_gate.compare_exchange_weak(expected,
                                                         kCallbackMaintenance,
                                                         std::memory_order_acq_rel,
                                                         std::memory_order_relaxed))
        {
            expected = 0;
            std::this_thread::yield();
        }
        while ((slot.usage.load(std::memory_order_acquire) & kInFlightMask) != 0)
        {
            std::this_thread::yield();
        }
        delete slot.state;
        slot.state = nullptr;
        slot.profile_generation = 0;
        slot.allocated = false;
        slot.policy_enabled.store(0, std::memory_order_relaxed);
        if (generation >= kMaxHandleGeneration)
        {
            slot.usage.store(kExhaustedMask, std::memory_order_release);
        }
        else
        {
            slot.callback_gate.store(0, std::memory_order_relaxed);
            slot.usage.store(0, std::memory_order_release);
        }
        return ECHIDNA_RESULT_OK;
    }

#if defined(ECHIDNA_STREAM_REGISTRY_TESTING)
    bool StreamHandleRegistry::setGenerationForTesting(size_t index,
                                                       uint32_t generation)
    {
        if (index >= slots_.size() || generation > kMaxHandleGeneration)
        {
            return false;
        }
        MaintenanceGuard guard(*this);
        Slot &slot = slots_[index];
        if (slot.allocated || slot.usage.load(std::memory_order_acquire) != 0)
        {
            return false;
        }
        slot.generation.store(generation, std::memory_order_relaxed);
        return true;
    }
#endif

} // namespace echidna::dsp_runtime
