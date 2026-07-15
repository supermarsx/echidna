#include <array>
#include <atomic>
#include <cerrno>
#include <cstdint>
#include <cstring>
#include <filesystem>
#include <fstream>
#include <limits>
#include <string_view>

#ifndef _WIN32
#include <sys/stat.h>
#include <unistd.h>
#endif

#include "effect_context.h"
#include "telemetry_protocol.h"
#include "test_support.h"

using echidna::effects::legacy::EffectContext;
using echidna::effects::legacy::EncodeTelemetryProof;
using echidna::effects::legacy::EncodeTelemetrySnapshot;
using echidna::effects::legacy::IsTelemetryProofParameter;
using echidna::effects::legacy::IsTelemetrySnapshotParameter;
using echidna::effects::legacy::kTelemetryAuthorized;
using echidna::effects::legacy::kTelemetryEnabled;
using echidna::effects::legacy::kTelemetryExpired;
using echidna::effects::legacy::kTelemetryProofKeyUnavailableStatus;
using echidna::effects::legacy::kTelemetryProofNoCapabilityStatus;
using echidna::effects::legacy::kTelemetryProofParameter;
using echidna::effects::legacy::kTelemetryProofQueryBytes;
using echidna::effects::legacy::kTelemetryProofReplyBytes;
using echidna::effects::legacy::kTelemetryProofStaleNonceStatus;
using echidna::effects::legacy::kTelemetryProofValueBytes;
using echidna::effects::legacy::kTelemetrySnapshotParameter;
using echidna::effects::legacy::kTelemetrySnapshotReplyBytes;
using echidna::effects::legacy::kTelemetrySnapshotValueBytes;
using echidna::effects::legacy::ModularAdd;
using echidna::effects::legacy::TelemetryProofKey;
using echidna::effects::legacy::TelemetryProofKeyOptions;
using echidna::effects::legacy::TelemetryProofNonce;
using echidna::effects::legacy::TelemetryProofSigner;
using echidna::effects::legacy::TelemetryProofWireSnapshot;
using echidna::effects::legacy::TelemetryWireSnapshot;
using echidna::effects::legacy::ValidTelemetryProofQueryLayout;
using echidna::effects::legacy::ValidTelemetryQueryLayout;

namespace
{
    constexpr size_t kCommandWords = 5;
    constexpr size_t kReplyWords = kTelemetrySnapshotReplyBytes / sizeof(uint32_t);
    constexpr size_t kProofCommandWords =
        (sizeof(effect_param_t) + kTelemetryProofQueryBytes) / sizeof(uint32_t);
    constexpr size_t kProofReplyWords =
        kTelemetryProofReplyBytes / sizeof(uint32_t);
    std::atomic<uint32_t> g_path_sequence{0};

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

    template <size_t Words>
    void PrepareProofQuery(ParameterBuffer<Words> *buffer,
                           const TelemetryProofNonce &nonce,
                           uint32_t value_capacity)
    {
        buffer->words.fill(0);
        auto *parameter = buffer->parameter();
        parameter->status = 0x12345678;
        parameter->psize = static_cast<uint32_t>(kTelemetryProofQueryBytes);
        parameter->vsize = value_capacity;
        std::memcpy(parameter->data,
                    kTelemetryProofParameter.data(),
                    kTelemetryProofParameter.size());
        std::memcpy(parameter->data + kTelemetryProofParameter.size(),
                    nonce.data(),
                    nonce.size());
    }

    uint32_t EffectiveUid() noexcept
    {
#ifndef _WIN32
        return static_cast<uint32_t>(::geteuid());
#else
        return 0;
#endif
    }

    uint32_t EffectiveGid() noexcept
    {
#ifndef _WIN32
        return static_cast<uint32_t>(::getegid());
#else
        return 0;
#endif
    }

    std::filesystem::path UniquePath(std::string_view label)
    {
        return std::filesystem::temp_directory_path() /
               ("echidna_telemetry_" + std::string(label) + "_" +
                std::to_string(g_path_sequence.fetch_add(1)) + ".key");
    }

    class ScopedPath
    {
    public:
        explicit ScopedPath(std::filesystem::path path) : path_(std::move(path)) {}
        ~ScopedPath()
        {
            std::error_code error;
            std::filesystem::remove_all(path_, error);
        }
        const std::filesystem::path &get() const noexcept { return path_; }

    private:
        std::filesystem::path path_;
    };

    bool WriteKey(const std::filesystem::path &path,
                  const TelemetryProofKey &key,
                  uint32_t mode = 0440)
    {
        std::ofstream output(path, std::ios::binary | std::ios::trunc);
        output.write(reinterpret_cast<const char *>(key.data()),
                     static_cast<std::streamsize>(key.size()));
        output.close();
        if (!output)
        {
            return false;
        }
#ifndef _WIN32
        return ::chmod(path.c_str(), static_cast<mode_t>(mode)) == 0;
#else
        (void)mode;
        return true;
#endif
    }

    TelemetryProofKeyOptions ProofOptionsFor(
        const std::filesystem::path &path)
    {
        return {path.string(), EffectiveUid(), EffectiveGid(), 0440};
    }

    TelemetryProofKey FixtureKey(uint8_t offset = 0)
    {
        TelemetryProofKey key{};
        for (size_t index = 0; index < key.size(); ++index)
        {
            key[index] = static_cast<uint8_t>(index + offset);
        }
        return key;
    }

    consteval uint8_t HexNibble(char value)
    {
        return value >= '0' && value <= '9'
                   ? static_cast<uint8_t>(value - '0')
               : value >= 'a' && value <= 'f'
                   ? static_cast<uint8_t>(value - 'a' + 10)
                   : static_cast<uint8_t>(value - 'A' + 10);
    }

    template <size_t Size>
    consteval auto HexBytes(const char (&text)[Size])
    {
        static_assert((Size - 1) % 2 == 0);
        std::array<uint8_t, (Size - 1) / 2> bytes{};
        for (size_t index = 0; index < bytes.size(); ++index)
        {
            bytes[index] = static_cast<uint8_t>(
                (HexNibble(text[index * 2]) << 4U) |
                HexNibble(text[index * 2 + 1]));
        }
        return bytes;
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

    int TestFrozenProofSchemaAndGoldenFixture()
    {
        static_assert(kTelemetryProofParameter.size() == 8);
        static_assert(kTelemetryProofQueryBytes == 24);
        static_assert(kTelemetryProofValueBytes == 112);
        static_assert(kTelemetryProofReplyBytes == 148);
        static_assert(kProofCommandWords == 9);
        static_assert(kProofReplyWords == 37);
#ifndef ECHIDNA_HAS_BORINGSSL
        return 0;
#else
        const TelemetryProofKey key = FixtureKey();
        ScopedPath path(UniquePath("golden"));
        CHECK_TRUE(WriteKey(path.get(), key));
        TelemetryProofSigner signer(ProofOptionsFor(path.get()));
        CHECK_TRUE(signer.Load());

        TelemetryProofWireSnapshot snapshot;
        snapshot.session_id = 41;
        snapshot.generation = 0x0102030405060708ULL;
        for (size_t index = 0; index < snapshot.nonce.size(); ++index)
        {
            snapshot.nonce[index] = static_cast<uint8_t>(index + 1);
        }
        snapshot.sequence = std::numeric_limits<uint32_t>::max();
        snapshot.flags = kTelemetryEnabled | kTelemetryAuthorized |
                         kTelemetryExpired;
        snapshot.blocks = 0xfffffffeU;
        snapshot.frames = 5;
        snapshot.failures = std::numeric_limits<uint32_t>::max();
        snapshot.mutations = 0x80000000U;

        constexpr auto expected = HexBytes(
            "4543485400020002007000070000002901020304050607080102030405060708"
            "090a0b0c0d0e0f10fffffffffffffffe00000005ffffffff80000000630dcd29"
            "66c4336691125448bbb25b4f00000000416d73deaf6412d3bd08f9f905d69f07"
            "aace87ae5f58ab1a73935083f8581a0d");
        static_assert(expected.size() == kTelemetryProofValueBytes);
        std::array<uint8_t, kTelemetryProofValueBytes> encoded{};
        CHECK_TRUE(EncodeTelemetryProof(snapshot, signer, &encoded));
        CHECK_TRUE(encoded == expected);
        CHECK_TRUE(signer.Verify(encoded));

        for (const size_t offset : {size_t{0}, size_t{10}, size_t{24},
                                    size_t{40}, size_t{60}, size_t{79},
                                    size_t{80}, size_t{111}})
        {
            auto tampered = encoded;
            tampered[offset] ^= 0x01;
            CHECK_TRUE(!signer.Verify(tampered));
        }

        ScopedPath wrong_path(UniquePath("wrong"));
        CHECK_TRUE(WriteKey(wrong_path.get(), FixtureKey(1)));
        TelemetryProofSigner wrong(ProofOptionsFor(wrong_path.get()));
        CHECK_TRUE(wrong.Load());
        CHECK_TRUE(!wrong.Verify(encoded));

        snapshot.generation = 0;
        CHECK_TRUE(!EncodeTelemetryProof(snapshot, signer, &encoded));
        snapshot.generation = 1;
        snapshot.nonce.fill(0);
        CHECK_TRUE(!EncodeTelemetryProof(snapshot, signer, &encoded));
        snapshot.nonce[0] = 1;
        snapshot.flags = 0x8000;
        CHECK_TRUE(!EncodeTelemetryProof(snapshot, signer, &encoded));
        return 0;
#endif
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

    int TestProofKeySafety()
    {
#ifndef ECHIDNA_HAS_BORINGSSL
        TelemetryProofSigner unavailable;
        CHECK_TRUE(!unavailable.Load());
        CHECK_TRUE(!unavailable.available());
        return 0;
#else
        const TelemetryProofKey key = FixtureKey();
        ScopedPath safe_path(UniquePath("safe"));
        CHECK_TRUE(WriteKey(safe_path.get(), key));
        TelemetryProofSigner safe(ProofOptionsFor(safe_path.get()));
        CHECK_TRUE(safe.Load());
        CHECK_TRUE(safe.available());

        ScopedPath missing_path(UniquePath("missing"));
        TelemetryProofSigner missing(ProofOptionsFor(missing_path.get()));
        CHECK_TRUE(!missing.Load());
        CHECK_TRUE(!missing.available());

        ScopedPath short_path(UniquePath("short"));
        {
            std::ofstream short_key(short_path.get(),
                                    std::ios::binary | std::ios::trunc);
            short_key.write(reinterpret_cast<const char *>(key.data()),
                            static_cast<std::streamsize>(key.size() - 1));
        }
#ifndef _WIN32
        CHECK_TRUE(::chmod(short_path.get().c_str(), 0440) == 0);
#endif
        TelemetryProofSigner short_key(ProofOptionsFor(short_path.get()));
        CHECK_TRUE(!short_key.Load());

#ifndef _WIN32
        ScopedPath writable_path(UniquePath("writable"));
        CHECK_TRUE(WriteKey(writable_path.get(), key, 0640));
        TelemetryProofSigner writable(ProofOptionsFor(writable_path.get()));
        CHECK_TRUE(!writable.Load());

        ScopedPath world_path(UniquePath("world"));
        CHECK_TRUE(WriteKey(world_path.get(), key, 0444));
        TelemetryProofSigner world(ProofOptionsFor(world_path.get()));
        CHECK_TRUE(!world.Load());

        TelemetryProofKeyOptions wrong_owner = ProofOptionsFor(safe_path.get());
        wrong_owner.required_owner_uid++;
        TelemetryProofSigner owner_mismatch(std::move(wrong_owner));
        CHECK_TRUE(!owner_mismatch.Load());
        TelemetryProofKeyOptions wrong_group = ProofOptionsFor(safe_path.get());
        wrong_group.required_group_gid++;
        TelemetryProofSigner group_mismatch(std::move(wrong_group));
        CHECK_TRUE(!group_mismatch.Load());

        ScopedPath link_path(UniquePath("link"));
        std::filesystem::create_symlink(safe_path.get(), link_path.get());
        TelemetryProofSigner symlink(ProofOptionsFor(link_path.get()));
        CHECK_TRUE(!symlink.Load());

        ScopedPath directory_path(UniquePath("directory"));
        std::filesystem::create_directory(directory_path.get());
        TelemetryProofSigner directory(ProofOptionsFor(directory_path.get()));
        CHECK_TRUE(!directory.Load());
#endif
        return 0;
#endif
    }

    int TestProofQueryFramingCapacityAliasAndV1Isolation()
    {
        TelemetryProofNonce nonce{};
        for (size_t index = 0; index < nonce.size(); ++index)
        {
            nonce[index] = static_cast<uint8_t>(index + 1);
        }
        ParameterBuffer<kProofCommandWords> command;
        ParameterBuffer<kProofReplyWords> reply;
        PrepareProofQuery(&command, nonce, kTelemetryProofValueBytes);
        CHECK_TRUE(ValidTelemetryProofQueryLayout(*command.parameter(), 36));
        CHECK_TRUE(IsTelemetryProofParameter(*command.parameter(), 36));

        ScopedPath key_path(UniquePath("query"));
        TelemetryProofKeyOptions proof_options = ProofOptionsFor(key_path.get());
#ifdef ECHIDNA_HAS_BORINGSSL
        CHECK_TRUE(WriteKey(key_path.get(), FixtureKey()));
#endif
        EffectContext context(41, 7, {}, proof_options);
        CHECK_TRUE(context.Initialize() == 0);

        uint32_t reply_size = kTelemetryProofReplyBytes;
        CHECK_TRUE(context.Command(EFFECT_CMD_GET_PARAM,
                                   36,
                                   command.parameter(),
                                   &reply_size,
                                   reply.parameter()) == 0);
#ifdef ECHIDNA_HAS_BORINGSSL
        CHECK_TRUE(reply_size == sizeof(effect_param_t) +
                                     kTelemetryProofQueryBytes);
        CHECK_TRUE(reply.parameter()->status ==
                   kTelemetryProofNoCapabilityStatus);
#else
        CHECK_TRUE(reply.parameter()->status ==
                   kTelemetryProofKeyUnavailableStatus);
#endif
        CHECK_TRUE(reply.parameter()->psize == kTelemetryProofQueryBytes);
        CHECK_TRUE(reply.parameter()->vsize == 0);
        CHECK_TRUE(std::memcmp(reply.parameter()->data,
                               command.parameter()->data,
                               kTelemetryProofQueryBytes) == 0);

        PrepareProofQuery(&command, nonce, kTelemetryProofValueBytes - 1);
        reply_size = kTelemetryProofReplyBytes;
        CHECK_TRUE(context.Command(EFFECT_CMD_GET_PARAM,
                                   36,
                                   command.parameter(),
                                   &reply_size,
                                   reply.parameter()) == -ENOSPC);
        CHECK_TRUE(reply_size == kTelemetryProofReplyBytes);
        CHECK_TRUE(reply.parameter()->status == -ENOSPC);
        CHECK_TRUE(reply.parameter()->psize == kTelemetryProofQueryBytes);
        CHECK_TRUE(reply.parameter()->vsize == kTelemetryProofValueBytes);

        PrepareProofQuery(&command, nonce, kTelemetryProofValueBytes);
        reply_size = kTelemetryProofReplyBytes - 1;
        CHECK_TRUE(context.Command(EFFECT_CMD_GET_PARAM,
                                   36,
                                   command.parameter(),
                                   &reply_size,
                                   reply.parameter()) == -ENOSPC);
        CHECK_TRUE(reply_size == kTelemetryProofReplyBytes);
        CHECK_TRUE(reply.parameter()->status == -ENOSPC);

        reply_size = 0;
        CHECK_TRUE(context.Command(EFFECT_CMD_GET_PARAM,
                                   36,
                                   command.parameter(),
                                   &reply_size,
                                   nullptr) == -ENOSPC);
        CHECK_TRUE(reply_size == kTelemetryProofReplyBytes);

        PrepareProofQuery(&command, nonce, kTelemetryProofValueBytes);
        command.parameter()->data[3] = 'X';
        reply_size = sizeof(effect_param_t) + kTelemetryProofQueryBytes;
        CHECK_TRUE(context.Command(EFFECT_CMD_GET_PARAM,
                                   36,
                                   command.parameter(),
                                   &reply_size,
                                   reply.parameter()) == 0);
        CHECK_TRUE(reply_size == sizeof(effect_param_t) +
                                     kTelemetryProofQueryBytes);
        CHECK_TRUE(reply.parameter()->status == -EINVAL);
        CHECK_TRUE(reply.parameter()->vsize == 0);

        PrepareProofQuery(&command, nonce, kTelemetryProofValueBytes);
        command.parameter()->psize = kTelemetryProofQueryBytes - 1;
        reply_size = kTelemetryProofReplyBytes;
        CHECK_TRUE(context.Command(EFFECT_CMD_GET_PARAM,
                                   35,
                                   command.parameter(),
                                   &reply_size,
                                   reply.parameter()) == -EINVAL);
        command.parameter()->psize = kTelemetryProofQueryBytes;
        command.parameter()->vsize = std::numeric_limits<uint32_t>::max();
        CHECK_TRUE(context.Command(EFFECT_CMD_GET_PARAM,
                                   36,
                                   command.parameter(),
                                   &reply_size,
                                   reply.parameter()) == -EINVAL);

        ParameterBuffer<kProofReplyWords> aliased;
        PrepareProofQuery(&aliased, nonce, kTelemetryProofValueBytes);
        std::array<uint8_t, kTelemetryProofQueryBytes> expected_query{};
        std::memcpy(expected_query.data(),
                    aliased.parameter()->data,
                    expected_query.size());
        reply_size = kTelemetryProofReplyBytes;
        CHECK_TRUE(context.Command(EFFECT_CMD_GET_PARAM,
                                   36,
                                   aliased.parameter(),
                                   &reply_size,
                                   aliased.parameter()) == 0);
        CHECK_TRUE(std::memcmp(aliased.parameter()->data,
                               expected_query.data(),
                               expected_query.size()) == 0);

        // Failed v2 reads have an independent sequence and cannot perturb the
        // frozen v1 diagnostic contract.
        ParameterBuffer<kCommandWords> v1_command;
        ParameterBuffer<kReplyWords> v1_reply;
        PrepareQuery(&v1_command, kTelemetrySnapshotParameter, 48);
        reply_size = kTelemetrySnapshotReplyBytes;
        CHECK_TRUE(context.Command(EFFECT_CMD_GET_PARAM,
                                   20,
                                   v1_command.parameter(),
                                   &reply_size,
                                   v1_reply.parameter()) == 0);
        CHECK_TRUE(ReadUint32(Value(*v1_reply.parameter()) + 24) == 1);
        return 0;
    }

    int TestMissingProofKeyDoesNotDisableV1OrAudio()
    {
        ScopedPath missing_path(UniquePath("audio-missing"));
        EffectContext context(91, 9, {}, ProofOptionsFor(missing_path.get()));
        CHECK_TRUE(context.Initialize() == 0);
        CHECK_TRUE(context.SetConfig(MakeEffectConfig()) == 0);
        echidna::dsp::config::PresetDefinition preset;
        preset.name = "proof-key-isolation";
        preset.mix.params.output_gain_db = -6.0f;
        CHECK_TRUE(context.SetPolicyPreset(true, &preset) == 0);
        CHECK_TRUE(context.Enable() == 0);

        std::array<float, 8> input{0.25f, -0.25f, 0.5f, -0.5f,
                                   0.1f, -0.1f, 0.8f, -0.8f};
        std::array<float, 8> output{};
        audio_buffer_t input_buffer{input.size(), {.f32 = input.data()}};
        audio_buffer_t output_buffer{output.size(), {.f32 = output.data()}};
        CHECK_TRUE(context.Process(&input_buffer, &output_buffer) == 0);
        CHECK_TRUE(!std::equal(input.begin(), input.end(), output.begin()));
        CHECK_TRUE(context.telemetry().mutations == 1);

        ParameterBuffer<kCommandWords> v1_command;
        ParameterBuffer<kReplyWords> v1_reply;
        PrepareQuery(&v1_command, kTelemetrySnapshotParameter, 48);
        uint32_t reply_size = kTelemetrySnapshotReplyBytes;
        CHECK_TRUE(context.Command(EFFECT_CMD_GET_PARAM,
                                   20,
                                   v1_command.parameter(),
                                   &reply_size,
                                   v1_reply.parameter()) == 0);
        CHECK_TRUE(v1_reply.parameter()->status == 0);
        CHECK_TRUE(ReadUint32(Value(*v1_reply.parameter()) + 40) == 1);

        TelemetryProofNonce nonce{};
        nonce[0] = 1;
        ParameterBuffer<kProofCommandWords> proof_command;
        ParameterBuffer<kProofReplyWords> proof_reply;
        PrepareProofQuery(&proof_command, nonce, kTelemetryProofValueBytes);
        reply_size = kTelemetryProofReplyBytes;
        CHECK_TRUE(context.Command(EFFECT_CMD_GET_PARAM,
                                   36,
                                   proof_command.parameter(),
                                   &reply_size,
                                   proof_reply.parameter()) == 0);
        CHECK_TRUE(proof_reply.parameter()->status ==
                   kTelemetryProofKeyUnavailableStatus);
        CHECK_TRUE(context.Disable() == 0);
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
    CHECK_TRUE(TestFrozenProofSchemaAndGoldenFixture() == 0);
    CHECK_TRUE(TestQueryFramingAndCapacity() == 0);
    CHECK_TRUE(TestProofKeySafety() == 0);
    CHECK_TRUE(TestProofQueryFramingCapacityAliasAndV1Isolation() == 0);
    CHECK_TRUE(TestMissingProofKeyDoesNotDisableV1OrAudio() == 0);
    CHECK_TRUE(TestUnknownMalformedAndAlignment() == 0);
    CHECK_TRUE(TestAliasedCommandReplyAndState() == 0);
    return 0;
}
