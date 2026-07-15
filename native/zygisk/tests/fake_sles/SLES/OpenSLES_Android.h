#pragma once

#include "OpenSLES.h"

constexpr SLuint32 SL_ANDROID_DATAFORMAT_PCM_EX = 0x00000004u;
constexpr SLuint32 SL_ANDROID_PCM_REPRESENTATION_SIGNED_INT = 0x00000001u;
constexpr SLuint32 SL_ANDROID_PCM_REPRESENTATION_UNSIGNED_INT = 0x00000002u;
constexpr SLuint32 SL_ANDROID_PCM_REPRESENTATION_FLOAT = 0x00000003u;
constexpr SLuint32 SL_DATALOCATOR_ANDROIDSIMPLEBUFFERQUEUE = 0x800007BDu;

struct SLAndroidDataFormat_PCM_EX
{
    SLuint32 formatType;
    SLuint32 numChannels;
    SLuint32 sampleRate;
    SLuint32 bitsPerSample;
    SLuint32 containerSize;
    SLuint32 channelMask;
    SLuint32 endianness;
    SLuint32 representation;
};

struct SLDataLocator_AndroidSimpleBufferQueue
{
    SLuint32 locatorType;
    SLuint32 numBuffers;
};

struct SLAndroidSimpleBufferQueueItf_;
using SLAndroidSimpleBufferQueueItf =
    const SLAndroidSimpleBufferQueueItf_ *const *;
using slAndroidSimpleBufferQueueCallback =
    void (*)(SLAndroidSimpleBufferQueueItf caller, void *context);

struct SLAndroidSimpleBufferQueueState
{
    SLuint32 count;
    SLuint32 index;
};

struct SLAndroidSimpleBufferQueueItf_
{
    SLresult (*Enqueue)(SLAndroidSimpleBufferQueueItf self,
                        const void *buffer,
                        SLuint32 bytes);
    SLresult (*Clear)(SLAndroidSimpleBufferQueueItf self);
    SLresult (*GetState)(SLAndroidSimpleBufferQueueItf self,
                         SLAndroidSimpleBufferQueueState *state);
    SLresult (*RegisterCallback)(SLAndroidSimpleBufferQueueItf self,
                                 slAndroidSimpleBufferQueueCallback callback,
                                 void *context);
};
