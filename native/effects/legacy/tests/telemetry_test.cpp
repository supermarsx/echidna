#include <array>
#include <cerrno>
#include <cstdint>
#include <cstring>
#include <limits>

#include "effect_context.h"
#include "telemetry_protocol.h"
#include "test_support.h"

using echidna::effects::legacy::EffectContext;
using echidna::effects::legacy::EncodeTelemetrySnapshot;
using echidna::effects::legacy::IsTelemetrySnapshotParameter;
using echidna::effects::legacy::kTelemetryAuthorized;
using echidna::effects::legacy::kTelemetryEnabled;
using echidna::effects::legacy::kTelemetryExpired;
using echidna::effects::legacy::kTelemetrySnapshotParameter;
using echidna::effects::legacy::kTelemetrySnapshotReplyBytes;
using echidna::effects::legacy::kTelemetrySnapshotValueBytes;
using echidna::effects::legacy::ModularAdd;
using echidna::effects::legacy::TelemetryWireSnapshot;
using echidna::effects::legacy::ValidTelemetryQueryLayout;

namespace
{
    constexpr size_t kCommandWords = 5;
    constexpr size_t kReplyWords = kTelemetrySnapshotReplyBytes / sizeof(uint32_t);

    template <size_t Words>
    struct ParameterBuffer
    {
        std::array<uint32_t, Words> words{};

        effect_param_t *parameter() noexcept
        {
            return reinterpret_cast<effect_param_t *>(words.data());
        }
    };

    template <size_t Words>
    void PrepareQuery(ParameterBuffer<Words> *buffer,
                      const std::array<uint8_t, 8> &key,
                      uint32_t value_capacity)
    {
        buffer->words.fill(0);
        auto *parameter = buffer->parameter();
        parameter->status = 0x12345678;
        parameter->psize = static_cast<uint32_t>(key.size());
        parameter->vsize = value_capacity;
        std::memcpy(parameter->data, key.data(), key.size());
    }

    uint16_t ReadUint16(const uint8_t *data)
    {
        return static_cast<uint16_t>(
            (static_cast<uint16_t>(data[0]) << 8U) | data[1]);
    }

    uint32_t ReadUint32(const uint8_t *data)
    {
        return (static_cast<uint32_t>(data[0]) << 24U) |
               (static_cast<uint32_t>(data[1]) << 16U) |
               (static_cast<uint32_t>(data[2]) << 8U) |
               data[3];
    }

    uint64_t ReadUint64(const uint8_t *data)
    {
        return (static_cast<uint64_t>(ReadUint32(data)) << 32U) |
               ReadUint32(data + 4);
    }

    const uint8_t *Value(const effect_param_t &parameter)
    {
        return reinterpret_cast<const uint8_t *>(parameter.data) +
               kTelemetrySnapshotParameter.size();
    }

    int TestFrozenSchemaAndGoldenFixture()
    {
        static_assert(sizeof(effect_param_t) == 12);
        static_assert(offsetof(effect_param_t, data) == 12);
        static_assert(kTelemetrySnapshotParameter.size() == 8);
        static_assert(kTelemetrySnapshotValueBytes == 48);
        static_assert(kTelemetrySnapshotReplyBytes == 68);
        static_assert(kReplyWords == 17);
        static_assert(ModularAdd(std::numeric_limits<uint32_t>::max(), 1) == 0);
        static_assert(ModularAdd(std::numeric_limits<uint32_t>::max() - 1, 3) == 1);

        constexpr std::array<uint8_t, kTelemetrySnapshotValueBytes> expected = {
            0x45,
            0x43,
            0x48,
            0x54,
            0x00,
            0x01,
            0x00,
            0x01,
            0x00,
            0x30,
            0x00,
            0x07,
            0x00,
            0x00,
            0x00,
            0x29,
            0x01,
            0x02,
            0x03,
            0x04,
            0x05,
            0x06,
            0x07,
            0x08,
            0xff,
            0xff,
            0xff,
            0xff,
            0xff,
            0xff,
            0xff,
            0xfe,
            0x00,
            0x00,
            0x00,
            0x05,
            0xff,
            0xff,
            0xff,
            0xff,
            0x80,
            0x00,
            0x00,
            0x00,
            0x00,
            0x00,
            0x00,
            0x00,
        };
        TelemetryWireSnapshot snapshot;
        snapshot.session_id = 41;
        snapshot.generation = 0x0102030405060708ULL;
        snapshot.sequence = std::numeric_limits<uint32_t>::max();
        snapshot.flags = kTelemetryEnabled | kTelemetryAuthorized | kTelemetryExpired;
        snapshot.blocks = 0xfffffffeU;
        snapshot.frames = 5;
        snapshot.failures = std::numeric_limits<uint32_t>::max();
        snapshot.mutations = 0x80000000U;
        CHECK_TRUE(EncodeTelemetrySnapshot(snapshot) == expected);
        return 0;
    }

    int TestQueryFramingAndCapacity()
    {
        EffectContext context(41, 7);
        ParameterBuffer<kCommandWords> command;
        ParameterBuffer<kReplyWords> reply;
        PrepareQuery(&command, kTelemetrySnapshotParameter, 48);
        CHECK_TRUE(ValidTelemetryQueryLayout(*command.parameter(), 20));
        CHECK_TRUE(IsTelemetrySnapshotParameter(*command.parameter(), 20));

        uint32_t reply_size = kTelemetrySnapshotReplyBytes;
        CHECK_TRUE(context.Command(EFFECT_CMD_GET_PARAM,
                                   20,
                                   command.parameter(),
                                   &reply_size,
                                   reply.parameter()) == 0);
        CHECK_TRUE(reply_size == kTelemetrySnapshotReplyBytes);
        CHECK_TRUE(reply.parameter()->status == 0);
        CHECK_TRUE(reply.parameter()->psize == 8);
        CHECK_TRUE(reply.parameter()->vsize == 48);
        CHECK_TRUE(std::memcmp(reply.parameter()->data,
                               kTelemetrySnapshotParameter.data(),
                               kTelemetrySnapshotParameter.size()) == 0);
        const uint8_t *value = Value(*reply.parameter());
        CHECK_TRUE(std::memcmp(value, "ECHT", 4) == 0);
        CHECK_TRUE(ReadUint16(value + 4) == 1);
        CHECK_TRUE(ReadUint16(value + 6) == 1);
        CHECK_TRUE(ReadUint16(value + 8) == 48);
        CHECK_TRUE(ReadUint16(value + 10) == 0);
        CHECK_TRUE(static_cast<int32_t>(ReadUint32(value + 12)) == 41);
        CHECK_TRUE(ReadUint64(value + 16) == 0);
        CHECK_TRUE(ReadUint32(value + 24) == 1);

        reply.words.fill(0xa5a5a5a5U);
        reply_size = 11;
        CHECK_TRUE(context.Command(EFFECT_CMD_GET_PARAM,
                                   20,
                                   command.parameter(),
                                   &reply_size,
                                   reply.parameter()) == -ENOSPC);
        CHECK_TRUE(reply_size == kTelemetrySnapshotReplyBytes);
        CHECK_TRUE(reply.words[0] == 0xa5a5a5a5U);

        reply.words.fill(0);
        reply_size = 19;
        CHECK_TRUE(context.Command(EFFECT_CMD_GET_PARAM,
                                   20,
                                   command.parameter(),
                                   &reply_size,
                                   reply.parameter()) == -ENOSPC);
        CHECK_TRUE(reply_size == kTelemetrySnapshotReplyBytes);
        CHECK_TRUE(reply.parameter()->status == -ENOSPC);
        CHECK_TRUE(reply.parameter()->psize == 8);
        CHECK_TRUE(reply.parameter()->vsize == 48);

        PrepareQuery(&command, kTelemetrySnapshotParameter, 47);
        reply_size = kTelemetrySnapshotReplyBytes;
        CHECK_TRUE(context.Command(EFFECT_CMD_GET_PARAM,
                                   20,
                                   command.parameter(),
                                   &reply_size,
                                   reply.parameter()) == -ENOSPC);
        CHECK_TRUE(reply_size == kTelemetrySnapshotReplyBytes);
        CHECK_TRUE(reply.parameter()->status == -ENOSPC);
        CHECK_TRUE(reply.parameter()->vsize == 48);

        reply_size = 0;
        CHECK_TRUE(context.Command(EFFECT_CMD_GET_PARAM,
                                   20,
                                   command.parameter(),
                                   &reply_size,
                                   nullptr) == -ENOSPC);
        CHECK_TRUE(reply_size == kTelemetrySnapshotReplyBytes);
        return 0;
    }

    int TestUnknownMalformedAndAlignment()
    {
        EffectContext context(-17, 3);
        ParameterBuffer<kCommandWords> command;
        ParameterBuffer<kReplyWords> reply;
        auto unknown = kTelemetrySnapshotParameter;
        unknown[3] = 'X';
        PrepareQuery(&command, unknown, 48);
        uint32_t reply_size = 20;
        CHECK_TRUE(context.Command(EFFECT_CMD_GET_PARAM,
                                   20,
                                   command.parameter(),
                                   &reply_size,
                                   reply.parameter()) == 0);
        CHECK_TRUE(reply_size == 20);
        CHECK_TRUE(reply.parameter()->status == -EINVAL);
        CHECK_TRUE(reply.parameter()->vsize == 0);
        CHECK_TRUE(std::memcmp(reply.parameter()->data,
                               unknown.data(),
                               unknown.size()) == 0);

        reply_size = 12;
        CHECK_TRUE(context.Command(EFFECT_CMD_GET_PARAM,
                                   20,
                                   command.parameter(),
                                   &reply_size,
                                   reply.parameter()) == -ENOSPC);
        CHECK_TRUE(reply_size == 20);
        CHECK_TRUE(reply.parameter()->status == -ENOSPC);

        PrepareQuery(&command, kTelemetrySnapshotParameter, 48);
        command.parameter()->psize = 7;
        reply_size = kTelemetrySnapshotReplyBytes;
        CHECK_TRUE(context.Command(EFFECT_CMD_GET_PARAM,
                                   20,
                                   command.parameter(),
                                   &reply_size,
                                   reply.parameter()) == -EINVAL);
        command.parameter()->psize = 8;
        command.parameter()->vsize = std::numeric_limits<uint32_t>::max();
        CHECK_TRUE(context.Command(EFFECT_CMD_GET_PARAM,
                                   20,
                                   command.parameter(),
                                   &reply_size,
                                   reply.parameter()) == -EINVAL);
        command.parameter()->vsize = 48;
        CHECK_TRUE(context.Command(EFFECT_CMD_GET_PARAM,
                                   19,
                                   command.parameter(),
                                   &reply_size,
                                   reply.parameter()) == -EINVAL);
        CHECK_TRUE(context.Command(EFFECT_CMD_GET_PARAM,
                                   20,
                                   nullptr,
                                   &reply_size,
                                   reply.parameter()) == -EINVAL);
        CHECK_TRUE(context.Command(EFFECT_CMD_GET_PARAM,
                                   20,
                                   command.parameter(),
                                   nullptr,
                                   reply.parameter()) == -EINVAL);

        alignas(effect_param_t) std::array<uint8_t, 21> misaligned_command{};
        std::memcpy(misaligned_command.data() + 1, command.words.data(), 20);
        CHECK_TRUE(context.Command(EFFECT_CMD_GET_PARAM,
                                   20,
                                   misaligned_command.data() + 1,
                                   &reply_size,
                                   reply.parameter()) == -EINVAL);
        alignas(effect_param_t) std::array<uint8_t, 69> misaligned_reply{};
        CHECK_TRUE(context.Command(EFFECT_CMD_GET_PARAM,
                                   20,
                                   command.parameter(),
                                   &reply_size,
                                   misaligned_reply.data() + 1) == -EINVAL);
        return 0;
    }

    int TestAliasedCommandReplyAndState()
    {
        EffectContext context(73, 11);
        CHECK_TRUE(context.Initialize() == 0);
        CHECK_TRUE(context.SetConfig(MakeEffectConfig()) == 0);
        echidna::dsp::config::PresetDefinition preset;
        preset.name = "telemetry-state";
        preset.mix.params.output_gain_db = -6.0f;
        CHECK_TRUE(context.SetPolicyPreset(true, &preset) == 0);
        CHECK_TRUE(context.Enable() == 0);

        ParameterBuffer<kReplyWords> aliased;
        PrepareQuery(&aliased, kTelemetrySnapshotParameter, 48);
        uint32_t reply_size = kTelemetrySnapshotReplyBytes;
        CHECK_TRUE(context.Command(EFFECT_CMD_GET_PARAM,
                                   20,
                                   aliased.parameter(),
                                   &reply_size,
                                   aliased.parameter()) == 0);
        const uint8_t *value = Value(*aliased.parameter());
        CHECK_TRUE(ReadUint16(value + 10) ==
                   (kTelemetryEnabled | kTelemetryAuthorized));
        CHECK_TRUE(ReadUint32(value + 24) == 1);

        context.RevokeAuthorization();
        PrepareQuery(&aliased, kTelemetrySnapshotParameter, 48);
        reply_size = kTelemetrySnapshotReplyBytes;
        CHECK_TRUE(context.Command(EFFECT_CMD_GET_PARAM,
                                   20,
                                   aliased.parameter(),
                                   &reply_size,
                                   aliased.parameter()) == 0);
        value = Value(*aliased.parameter());
        CHECK_TRUE(ReadUint16(value + 10) == kTelemetryEnabled);
        CHECK_TRUE(ReadUint32(value + 24) == 2);
        CHECK_TRUE(context.Disable() == 0);
        return 0;
    }
} // namespace

int main()
{
    CHECK_TRUE(TestFrozenSchemaAndGoldenFixture() == 0);
    CHECK_TRUE(TestQueryFramingAndCapacity() == 0);
    CHECK_TRUE(TestUnknownMalformedAndAlignment() == 0);
    CHECK_TRUE(TestAliasedCommandReplyAndState() == 0);
    return 0;
}
