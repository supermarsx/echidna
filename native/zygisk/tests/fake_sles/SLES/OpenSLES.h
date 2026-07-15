#pragma once

#include <cstdint>

using SLboolean = uint32_t;
using SLresult = uint32_t;
using SLuint32 = uint32_t;

constexpr SLresult SL_RESULT_SUCCESS = 0;
constexpr SLresult SL_RESULT_PRECONDITIONS_VIOLATED = 1;
constexpr SLresult SL_RESULT_INTERNAL_ERROR = 13;

constexpr SLuint32 SL_DATALOCATOR_BUFFERQUEUE = 0x00000006u;
constexpr SLuint32 SL_DATAFORMAT_PCM = 0x00000002u;
constexpr SLuint32 SL_BYTEORDER_LITTLEENDIAN = 0x00000002u;

struct SLInterfaceID_
{
    SLuint32 value;
};
using SLInterfaceID = const SLInterfaceID_ *;

struct SLObjectItf_;
using SLObjectItf = const SLObjectItf_ *const *;

struct SLEngineItf_;
using SLEngineItf = const SLEngineItf_ *const *;

struct SLEngineOption
{
    SLuint32 feature;
    SLuint32 data;
};

struct SLDataLocator_BufferQueue
{
    SLuint32 locatorType;
    SLuint32 numBuffers;
};

struct SLDataFormat_PCM
{
    SLuint32 formatType;
    SLuint32 numChannels;
    SLuint32 samplesPerSec;
    SLuint32 bitsPerSample;
    SLuint32 containerSize;
    SLuint32 channelMask;
    SLuint32 endianness;
};

struct SLDataSource
{
    void *pLocator;
    void *pFormat;
};

struct SLDataSink
{
    void *pLocator;
    void *pFormat;
};

struct SLObjectItf_
{
    SLresult (*Realize)(SLObjectItf self, SLboolean async);
    SLresult (*GetInterface)(SLObjectItf self, SLInterfaceID iid, void *out_interface);
    void (*Destroy)(SLObjectItf self);
};

struct SLEngineItf_
{
    SLresult (*CreateAudioRecorder)(SLEngineItf self,
                                    SLObjectItf *recorder,
                                    SLDataSource *source,
                                    SLDataSink *sink,
                                    SLuint32 interface_count,
                                    const SLInterfaceID *interface_ids,
                                    const SLboolean *required);
};
