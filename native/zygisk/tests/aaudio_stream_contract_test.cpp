#include "hooks/aaudio_stream_contract.h"

#include <cstdint>
#include <cstdio>

namespace
{
    struct FakeStream
    {
        int32_t sample_rate{48000};
        int32_t channels{2};
        int32_t format{2};
        int32_t direction{1};
        int32_t capacity{1920};
        int32_t burst{240};
        uint32_t queries{0};
    };

    int gFailures = 0;
    void Check(bool condition, const char *expression, int line, const char *message)
    {
        if (!condition)
        {
            std::fprintf(stderr, "FAIL: %s [line %d] %s\n", expression, line, message);
            ++gFailures;
        }
    }

#define CHECK(condition, message) Check((condition), #condition, __LINE__, (message))

    int32_t SampleRate(void *stream)
    {
        ++static_cast<FakeStream *>(stream)->queries;
        return static_cast<FakeStream *>(stream)->sample_rate;
    }
    int32_t Channels(void *stream)
    {
        ++static_cast<FakeStream *>(stream)->queries;
        return static_cast<FakeStream *>(stream)->channels;
    }
    int32_t Format(void *stream)
    {
        ++static_cast<FakeStream *>(stream)->queries;
        return static_cast<FakeStream *>(stream)->format;
    }
    int32_t Direction(void *stream)
    {
        ++static_cast<FakeStream *>(stream)->queries;
        return static_cast<FakeStream *>(stream)->direction;
    }
    int32_t Capacity(void *stream)
    {
        ++static_cast<FakeStream *>(stream)->queries;
        return static_cast<FakeStream *>(stream)->capacity;
    }
    int32_t Burst(void *stream)
    {
        ++static_cast<FakeStream *>(stream)->queries;
        return static_cast<FakeStream *>(stream)->burst;
    }

    echidna::hooks::AAudioStreamQueryApi Queries()
    {
        return {SampleRate, Channels, Format, Direction, Capacity, Burst};
    }

    bool Query(FakeStream &stream, echidna_stream_config_t *config)
    {
        return echidna::hooks::QueryAAudioStreamConfig(&stream, Queries(), config);
    }

    void TestTypicalCapacityAndMemorySizing()
    {
        FakeStream stream;
        echidna_stream_config_t config{};
        CHECK(Query(stream, &config), "typical AAudio metadata is accepted");
        CHECK(stream.queries == 6, "each immutable metadata getter is called once");
        CHECK(config.struct_size == sizeof(config) && config.sample_rate == 48000 &&
                  config.channel_count == 2 &&
                  config.format == ECHIDNA_PCM_FORMAT_FLOAT_32,
              "exact sample/channel/format metadata is retained");
        CHECK(config.max_frames == 1920,
              "actual buffer capacity is stored unchanged in frames");
        CHECK(config.max_frames * config.channel_count == 3840,
              "memory sizing uses capacity frames times exact channels");
    }

    void TestBurstAndCapacityBoundaries()
    {
        echidna_stream_config_t config{};
        FakeStream equal;
        equal.capacity = 256;
        equal.burst = 256;
        CHECK(Query(equal, &config) && config.max_frames == 256,
              "burst equal to capacity is valid");

        FakeStream zero_capacity;
        zero_capacity.capacity = 0;
        CHECK(!Query(zero_capacity, &config) && config.struct_size == 0,
              "zero capacity fails closed");
        FakeStream negative_capacity;
        negative_capacity.capacity = -1;
        CHECK(!Query(negative_capacity, &config), "negative capacity fails closed");
        FakeStream zero_burst;
        zero_burst.burst = 0;
        CHECK(!Query(zero_burst, &config), "zero burst fails closed");
        FakeStream negative_burst;
        negative_burst.burst = -1;
        CHECK(!Query(negative_burst, &config), "negative burst fails closed");
        FakeStream burst_over_capacity;
        burst_over_capacity.capacity = 240;
        burst_over_capacity.burst = 241;
        CHECK(!Query(burst_over_capacity, &config),
              "burst larger than capacity fails closed");
    }

    void TestHardSafetyCeiling()
    {
        echidna_stream_config_t config{};
        FakeStream mono;
        mono.channels = 1;
        mono.capacity = static_cast<int32_t>(
            echidna::hooks::kAAudioMaxPreparedSamples);
        mono.burst = mono.capacity;
        CHECK(Query(mono, &config), "mono exact hard ceiling is valid");
        ++mono.capacity;
        CHECK(!Query(mono, &config), "mono frame count above ceiling is rejected");

        FakeStream stereo;
        stereo.capacity = static_cast<int32_t>(
            echidna::hooks::kAAudioMaxPreparedSamples / 2U);
        stereo.burst = stereo.capacity;
        CHECK(Query(stereo, &config) &&
                  config.max_frames ==
                      echidna::hooks::kAAudioMaxPreparedSamples / 2U,
              "stereo exact interleaved-sample ceiling is valid");
        ++stereo.capacity;
        CHECK(!Query(stereo, &config),
              "capacity exceeding bounded interleaved memory is rejected");
    }

    void TestInvalidMetadataAndQueryFailure()
    {
        echidna_stream_config_t config{};
        FakeStream stream;
        stream.channels = 0;
        CHECK(!Query(stream, &config), "zero channels fail closed");
        stream = {};
        stream.channels = 9;
        CHECK(!Query(stream, &config), "unsupported channel count fails closed");
        stream = {};
        stream.format = 99;
        CHECK(!Query(stream, &config), "unknown PCM format fails closed");
        stream = {};
        stream.direction = 0;
        CHECK(!Query(stream, &config), "output direction fails closed");
        stream = {};
        stream.sample_rate = 7999;
        CHECK(!Query(stream, &config), "sample rate below contract fails closed");

        auto incomplete = Queries();
        incomplete.get_frames_per_burst = nullptr;
        stream = {};
        CHECK(!echidna::hooks::QueryAAudioStreamConfig(
                  &stream, incomplete, &config) &&
                  config.struct_size == 0 && stream.queries == 0,
              "missing query symbol fails before touching the stream");
        CHECK(!echidna::hooks::QueryAAudioStreamConfig(
                  &stream, Queries(), nullptr),
              "null configuration destination fails closed");
    }
} // namespace

int main()
{
    TestTypicalCapacityAndMemorySizing();
    TestBurstAndCapacityBoundaries();
    TestHardSafetyCeiling();
    TestInvalidMetadataAndQueryFailure();
    if (gFailures != 0)
    {
        std::fprintf(stderr, "aaudio_stream_contract_test: %d failure(s)\n", gFailures);
        return 1;
    }
    std::fprintf(stderr, "aaudio_stream_contract_test: all checks passed\n");
    return 0;
}
