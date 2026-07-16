#pragma once

#include <cstdint>
#include <cstring>
#include <iostream>

#include "effect_abi.h"

#define CHECK_TRUE(condition)                                   \
    do                                                          \
    {                                                           \
        if (!(condition))                                       \
        {                                                       \
            std::cerr << __FILE__ << ':' << __LINE__            \
                      << ": check failed: " #condition << '\n'; \
            return 1;                                           \
        }                                                       \
    } while (false)

inline effect_config_t MakeEffectConfig(
    uint32_t sample_rate = 48000,
    uint32_t channel_mask = AUDIO_CHANNEL_IN_MONO,
    uint8_t format = AUDIO_FORMAT_PCM_FLOAT,
    uint8_t output_access = EFFECT_BUFFER_ACCESS_WRITE)
{
    effect_config_t config{};
    constexpr uint16_t kFormatMask = EFFECT_CONFIG_SMP_RATE |
                                     EFFECT_CONFIG_CHANNELS |
                                     EFFECT_CONFIG_FORMAT |
                                     EFFECT_CONFIG_ACC_MODE;
    config.inputCfg.samplingRate = sample_rate;
    config.inputCfg.channels = channel_mask;
    config.inputCfg.format = format;
    config.inputCfg.accessMode = EFFECT_BUFFER_ACCESS_READ;
    config.inputCfg.mask = kFormatMask;
    config.outputCfg.samplingRate = sample_rate;
    config.outputCfg.channels = channel_mask;
    config.outputCfg.format = format;
    config.outputCfg.accessMode = output_access;
    config.outputCfg.mask = kFormatMask;
    return config;
}

inline int32_t SendStatusCommand(effect_handle_t handle,
                                 uint32_t command,
                                 uint32_t command_size,
                                 void *command_data,
                                 int32_t *status)
{
    uint32_t reply_size = sizeof(*status);
    *status = 0x7fffffff;
    return (*handle)->command(handle,
                              command,
                              command_size,
                              command_data,
                              &reply_size,
                              status);
}
