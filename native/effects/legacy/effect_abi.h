/*
 * Copyright (C) 2011 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

#pragma once

// Minimal, standalone subset of Android's legacy audio-effect ABI.  The NDK
// does not ship hardware/audio_effect.h, so the declarations needed by this
// library are pinned to these Android 13 AOSP revisions:
//
//   hardware/libhardware bbd07bd2e60752a665ebc9b0915241f3c1636516
//     include/hardware/audio_effect.h
//   system/media 6bbe66240c65e228ee56e7c9559410f2c14f9b8b
//     audio/include/system/{audio_effect.h,audio_effect-base.h,audio.h}
//
// Only ABI-bearing types/constants used by Echidna are reproduced.  Layout
// assertions below deliberately fail the build if a compiler changes them.

#include <cstddef>
#include <cstdint>
#include <type_traits>

#define EFFECT_MAKE_API_VERSION(major, minor) \
    (((major) << 16) | ((minor) & 0xffff))
#define EFFECT_CONTROL_API_VERSION EFFECT_MAKE_API_VERSION(2, 0)
#define EFFECT_LIBRARY_API_VERSION EFFECT_MAKE_API_VERSION(3, 0)
#define EFFECT_LIBRARY_API_VERSION_3_0 EFFECT_MAKE_API_VERSION(3, 0)
#define AUDIO_EFFECT_LIBRARY_TAG                                               \
    ((static_cast<uint32_t>('A') << 24) | (static_cast<uint32_t>('E') << 16) | \
     (static_cast<uint32_t>('L') << 8) | static_cast<uint32_t>('T'))

#define AUDIO_EFFECT_LIBRARY_INFO_SYM AELI
#define AUDIO_EFFECT_LIBRARY_INFO_SYM_AS_STR "AELI"

using audio_channel_mask_t = uint32_t;

enum : uint8_t
{
    AUDIO_FORMAT_PCM_16_BIT = 0x1,
    AUDIO_FORMAT_PCM_FLOAT = 0x5,
};

enum : uint32_t
{
    AUDIO_CHANNEL_IN_STEREO = 0x0000000c,
    AUDIO_CHANNEL_IN_MONO = 0x00000010,
};

typedef struct audio_uuid_s
{
    uint32_t timeLow;
    uint16_t timeMid;
    uint16_t timeHiAndVersion;
    uint16_t clockSeq;
    uint8_t node[6];
} audio_uuid_t;

using effect_uuid_t = audio_uuid_t;

constexpr size_t EFFECT_STRING_LEN_MAX = 64;

typedef struct effect_descriptor_s
{
    effect_uuid_t type;
    effect_uuid_t uuid;
    uint32_t apiVersion;
    uint32_t flags;
    uint16_t cpuLoad;
    uint16_t memoryUsage;
    char name[EFFECT_STRING_LEN_MAX];
    char implementor[EFFECT_STRING_LEN_MAX];
} effect_descriptor_t;

enum effect_command_e : uint32_t
{
    EFFECT_CMD_INIT = 0,
    EFFECT_CMD_SET_CONFIG,
    EFFECT_CMD_RESET,
    EFFECT_CMD_ENABLE,
    EFFECT_CMD_DISABLE,
    EFFECT_CMD_SET_PARAM,
    EFFECT_CMD_SET_PARAM_DEFERRED,
    EFFECT_CMD_SET_PARAM_COMMIT,
    EFFECT_CMD_GET_PARAM,
    EFFECT_CMD_SET_DEVICE,
    EFFECT_CMD_SET_VOLUME,
    EFFECT_CMD_SET_AUDIO_MODE,
    EFFECT_CMD_SET_CONFIG_REVERSE,
    EFFECT_CMD_SET_INPUT_DEVICE,
    EFFECT_CMD_GET_CONFIG,
    EFFECT_CMD_GET_CONFIG_REVERSE,
    EFFECT_CMD_GET_FEATURE_SUPPORTED_CONFIGS,
    EFFECT_CMD_GET_FEATURE_CONFIG,
    EFFECT_CMD_SET_FEATURE_CONFIG,
    EFFECT_CMD_SET_AUDIO_SOURCE,
    EFFECT_CMD_OFFLOAD,
    EFFECT_CMD_DUMP,
    EFFECT_CMD_FIRST_PROPRIETARY = 0x10000,
};

enum : uint32_t
{
    EFFECT_FLAG_TYPE_MASK = 0x7,
    EFFECT_FLAG_TYPE_PRE_PROC = 0x3,
    EFFECT_FLAG_INSERT_FIRST = 0x8,
};

typedef enum effect_buffer_access_e : uint8_t
{
    EFFECT_BUFFER_ACCESS_WRITE = 0,
    EFFECT_BUFFER_ACCESS_READ = 1,
    EFFECT_BUFFER_ACCESS_ACCUMULATE = 2,
} effect_buffer_access_e;

typedef struct audio_buffer_s
{
    size_t frameCount;
    union
    {
        void *raw;
        float *f32;
        int32_t *s32;
        int16_t *s16;
        uint8_t *u8;
    };
} audio_buffer_t;

using buffer_function_t = int32_t (*)(void *cookie, audio_buffer_t *buffer);

typedef struct buffer_provider_s
{
    buffer_function_t getBuffer;
    buffer_function_t releaseBuffer;
    void *cookie;
} buffer_provider_t;

enum : uint16_t
{
    EFFECT_CONFIG_BUFFER = 1U << 0,
    EFFECT_CONFIG_SMP_RATE = 1U << 1,
    EFFECT_CONFIG_CHANNELS = 1U << 2,
    EFFECT_CONFIG_FORMAT = 1U << 3,
    EFFECT_CONFIG_ACC_MODE = 1U << 4,
    EFFECT_CONFIG_ALL = EFFECT_CONFIG_BUFFER | EFFECT_CONFIG_SMP_RATE |
                        EFFECT_CONFIG_CHANNELS | EFFECT_CONFIG_FORMAT |
                        EFFECT_CONFIG_ACC_MODE,
};

typedef struct buffer_config_s
{
    audio_buffer_t buffer;
    uint32_t samplingRate;
    uint32_t channels;
    buffer_provider_t bufferProvider;
    uint8_t format;
    uint8_t accessMode;
    uint16_t mask;
} buffer_config_t;

typedef struct effect_config_s
{
    buffer_config_t inputCfg;
    buffer_config_t outputCfg;
} effect_config_t;

typedef struct effect_param_s
{
    int32_t status;
    uint32_t psize;
    uint32_t vsize;
    char data[];
} effect_param_t;

static_assert(sizeof(effect_param_t) == 12);
static_assert(offsetof(effect_param_t, data) == 12);

struct effect_interface_s;
using effect_handle_t = effect_interface_s **;

typedef struct effect_interface_s
{
    int32_t (*process)(effect_handle_t self,
                       audio_buffer_t *in_buffer,
                       audio_buffer_t *out_buffer);
    int32_t (*command)(effect_handle_t self,
                       uint32_t command_code,
                       uint32_t command_size,
                       void *command_data,
                       uint32_t *reply_size,
                       void *reply_data);
    int32_t (*get_descriptor)(effect_handle_t self,
                              effect_descriptor_t *descriptor);
    int32_t (*process_reverse)(effect_handle_t self,
                               audio_buffer_t *in_buffer,
                               audio_buffer_t *out_buffer);
} effect_interface_t;

typedef struct audio_effect_library_s
{
    uint32_t tag;
    uint32_t version;
    const char *name;
    const char *implementor;
    int32_t (*create_effect)(const effect_uuid_t *uuid,
                             int32_t session_id,
                             int32_t io_id,
                             effect_handle_t *handle);
    int32_t (*release_effect)(effect_handle_t handle);
    int32_t (*get_descriptor)(const effect_uuid_t *uuid,
                              effect_descriptor_t *descriptor);
    int32_t (*create_effect_3_1)(const effect_uuid_t *uuid,
                                 int32_t session_id,
                                 int32_t io_id,
                                 int32_t device_id,
                                 effect_handle_t *handle);
} audio_effect_library_t;

static_assert(std::is_standard_layout_v<audio_uuid_t>);
static_assert(sizeof(audio_uuid_t) == 16);
static_assert(offsetof(audio_uuid_t, timeLow) == 0);
static_assert(offsetof(audio_uuid_t, clockSeq) == 8);
static_assert(offsetof(audio_uuid_t, node) == 10);
static_assert(sizeof(effect_descriptor_t) == 172);
static_assert(offsetof(effect_descriptor_t, flags) == 36);
static_assert(offsetof(effect_descriptor_t, name) == 44);
static_assert(sizeof(audio_buffer_t) == sizeof(size_t) + sizeof(void *));
static_assert(offsetof(audio_buffer_t, raw) == sizeof(size_t));
static_assert(sizeof(buffer_provider_t) == sizeof(void *) * 3);
static_assert(offsetof(buffer_config_t, samplingRate) == sizeof(audio_buffer_t));
static_assert(offsetof(buffer_config_t, format) ==
              sizeof(audio_buffer_t) + sizeof(uint32_t) * 2 +
                  sizeof(buffer_provider_t));
static_assert(sizeof(buffer_config_t) == (sizeof(void *) == 8 ? 56 : 32));
static_assert(sizeof(effect_config_t) == sizeof(buffer_config_t) * 2);
static_assert(sizeof(effect_interface_t) == sizeof(void *) * 4);
static_assert(sizeof(audio_effect_library_t) == (sizeof(void *) == 8 ? 56 : 32));
static_assert(offsetof(audio_effect_library_t, create_effect) ==
              8 + sizeof(void *) * 2);
