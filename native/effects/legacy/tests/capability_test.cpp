#include <algorithm>
#include <array>
#include <atomic>
#include <cmath>
#include <cstddef>
#include <cstdint>
#include <cstring>
#include <filesystem>
#include <fstream>
#include <iomanip>
#include <iostream>
#include <limits>
#include <sstream>
#include <string>
#include <string_view>
#include <vector>

#ifndef _WIN32
#include <sys/stat.h>
#include <unistd.h>
#endif

#ifdef ECHIDNA_HAS_BORINGSSL
#include <openssl/ec.h>
#include <openssl/evp.h>
#include <openssl/obj_mac.h>
#include <openssl/sha.h>
#include <openssl/x509.h>
#endif

#include "capability_protocol.h"
#include "effect_context.h"
#include "test_support.h"

using echidna::effects::legacy::CapabilityClaims;
using echidna::effects::legacy::CapabilityHash;
using echidna::effects::legacy::CapabilityNonce;
using echidna::effects::legacy::CapabilityStatus;
using echidna::effects::legacy::CapabilityVerifier;
using echidna::effects::legacy::CapabilityVerifierOptions;
using echidna::effects::legacy::EffectContext;
using echidna::effects::legacy::kTelemetryAuthorized;
using echidna::effects::legacy::kTelemetryEnabled;
using echidna::effects::legacy::kTelemetryExpired;
using echidna::effects::legacy::kTelemetrySnapshotParameter;
using echidna::effects::legacy::kTelemetrySnapshotReplyBytes;

#define REQUIRE(condition)                                                      \
    do                                                                          \
    {                                                                           \
        if (!(condition))                                                       \
        {                                                                       \
            std::cerr << __FILE__ << ':' << __LINE__                            \
                      << ": requirement failed: " #condition << '\n';          \
            return false;                                                       \
        }                                                                       \
    } while (false)

namespace
{
    constexpr int32_t kSessionId = 41;
    constexpr uint32_t kTargetUid = 10123;
    uint64_t g_now_ms = 100000;
    std::atomic<uint32_t> g_path_sequence{0};

    uint64_t TestClock() noexcept
    {
        return g_now_ms;
    }

    uint32_t EffectiveUid() noexcept
    {
#ifndef _WIN32
        return static_cast<uint32_t>(::geteuid());
#else
        return 0;
#endif
    }

    std::filesystem::path UniquePath(std::string_view label)
    {
        return std::filesystem::temp_directory_path() /
               ("echidna_" + std::string(label) + "_" +
                std::to_string(g_path_sequence.fetch_add(1)) + ".spki");
    }

    class ScopedPath
    {
    public:
        explicit ScopedPath(std::filesystem::path path) : path_(std::move(path)) {}
        ~ScopedPath()
        {
            std::error_code error;
            std::filesystem::remove(path_, error);
        }
        const std::filesystem::path &get() const noexcept { return path_; }

    private:
        std::filesystem::path path_;
    };

#ifdef ECHIDNA_HAS_BORINGSSL
    class SigningKey
    {
    public:
        explicit SigningKey(int curve = NID_X9_62_prime256v1)
        {
            EVP_PKEY_CTX *context = EVP_PKEY_CTX_new_id(EVP_PKEY_EC, nullptr);
            if (context && EVP_PKEY_keygen_init(context) == 1 &&
                EVP_PKEY_CTX_set_ec_paramgen_curve_nid(context, curve) == 1)
            {
                EVP_PKEY_keygen(context, &key_);
            }
            EVP_PKEY_CTX_free(context);
        }

        ~SigningKey() { EVP_PKEY_free(key_); }
        SigningKey(const SigningKey &) = delete;
        SigningKey &operator=(const SigningKey &) = delete;

        bool valid() const noexcept { return key_ != nullptr; }

        bool WriteSpki(const std::filesystem::path &path) const
        {
            const int encoded_size = i2d_PUBKEY(key_, nullptr);
            if (encoded_size <= 0)
            {
                return false;
            }
            std::vector<uint8_t> encoded(static_cast<size_t>(encoded_size));
            uint8_t *cursor = encoded.data();
            if (i2d_PUBKEY(key_, &cursor) != encoded_size)
            {
                return false;
            }
            std::ofstream output(path, std::ios::binary | std::ios::trunc);
            output.write(reinterpret_cast<const char *>(encoded.data()),
                         static_cast<std::streamsize>(encoded.size()));
            output.close();
            if (!output)
            {
                return false;
            }
#ifndef _WIN32
            if (::chmod(path.c_str(), 0600) != 0)
            {
                return false;
            }
#endif
            return true;
        }

        std::vector<uint8_t> Sign(std::string_view body) const
        {
            EVP_MD_CTX *context = EVP_MD_CTX_new();
            size_t signature_size = 0;
            const bool sized = context &&
                               EVP_DigestSignInit(context,
                                                  nullptr,
                                                  EVP_sha256(),
                                                  nullptr,
                                                  key_) == 1 &&
                               EVP_DigestSign(
                                   context,
                                   nullptr,
                                   &signature_size,
                                   reinterpret_cast<const uint8_t *>(body.data()),
                                   body.size()) == 1;
            std::vector<uint8_t> signature(sized ? signature_size : 0);
            const bool signed_ok = sized &&
                                   EVP_DigestSign(
                                       context,
                                       signature.data(),
                                       &signature_size,
                                       reinterpret_cast<const uint8_t *>(body.data()),
                                       body.size()) == 1;
            EVP_MD_CTX_free(context);
            if (!signed_ok)
            {
                return {};
            }
            signature.resize(signature_size);
            return signature;
        }

    private:
        EVP_PKEY *key_{nullptr};
    };

    void AppendU16(std::vector<uint8_t> *output, uint16_t value)
    {
        output->push_back(static_cast<uint8_t>(value >> 8));
        output->push_back(static_cast<uint8_t>(value));
    }

    void AppendU32(std::vector<uint8_t> *output, uint32_t value)
    {
        for (int shift = 24; shift >= 0; shift -= 8)
        {
            output->push_back(static_cast<uint8_t>(value >> shift));
        }
    }

    void AppendU64(std::vector<uint8_t> *output, uint64_t value)
    {
        for (int shift = 56; shift >= 0; shift -= 8)
        {
            output->push_back(static_cast<uint8_t>(value >> shift));
        }
    }

    struct ClaimsSpec
    {
        int32_t session_id{kSessionId};
        uint32_t target_uid{kTargetUid};
        uint64_t generation{1};
        uint64_t issued_ms{99990};
        uint64_t expires_ms{101000};
        CapabilityNonce nonce{1, 2, 3, 4, 5, 6, 7, 8,
                              9, 10, 11, 12, 13, 14, 15, 16};
        std::array<uint8_t, 16> implementation_uuid =
            echidna::effects::legacy::kEffectImplementationUuidBytes;
        std::string process{"com.example.recorder"};
        std::string preset{
            R"({"name":"Capability","engine":{"latencyMode":"LL","blockMs":10},"modules":[{"id":"mix","wet":100,"outGain":-12}]})"};
        bool corrupt_hash{false};
    };

    std::vector<uint8_t> BuildBody(const ClaimsSpec &spec)
    {
        CapabilityHash hash{};
        SHA256(reinterpret_cast<const uint8_t *>(spec.preset.data()),
               spec.preset.size(),
               hash.data());
        if (spec.corrupt_hash)
        {
            hash[0] ^= 0x80;
        }

        std::vector<uint8_t> body;
        body.reserve(echidna::effects::legacy::kCapabilityFixedBodyBytes +
                     spec.process.size() + spec.preset.size());
        body.insert(body.end(), {'E', 'C', 'H', 'C'});
        AppendU16(&body, echidna::effects::legacy::kCapabilitySchema);
        AppendU16(&body, 1);
        body.insert(body.end(),
                    spec.implementation_uuid.begin(),
                    spec.implementation_uuid.end());
        AppendU32(&body, static_cast<uint32_t>(spec.session_id));
        AppendU32(&body, spec.target_uid);
        AppendU64(&body, spec.generation);
        AppendU64(&body, spec.issued_ms);
        AppendU64(&body, spec.expires_ms);
        body.insert(body.end(), spec.nonce.begin(), spec.nonce.end());
        body.insert(body.end(), hash.begin(), hash.end());
        AppendU16(&body, static_cast<uint16_t>(spec.process.size()));
        AppendU32(&body, static_cast<uint32_t>(spec.preset.size()));
        body.insert(body.end(), spec.process.begin(), spec.process.end());
        body.insert(body.end(), spec.preset.begin(), spec.preset.end());
        return body;
    }

    std::vector<uint8_t> SignBody(const SigningKey &key,
                                  std::vector<uint8_t> body)
    {
        const std::string_view body_view(
            reinterpret_cast<const char *>(body.data()), body.size());
        const std::vector<uint8_t> signature = key.Sign(body_view);
        AppendU16(&body, static_cast<uint16_t>(signature.size()));
        body.insert(body.end(), signature.begin(), signature.end());
        return body;
    }

    std::vector<uint8_t> BuildEnvelope(const SigningKey &key,
                                       const ClaimsSpec &spec)
    {
        return SignBody(key, BuildBody(spec));
    }

    std::string_view BytesView(const std::vector<uint8_t> &bytes)
    {
        return {reinterpret_cast<const char *>(bytes.data()), bytes.size()};
    }

    std::string Hex(const std::vector<uint8_t> &bytes)
    {
        std::ostringstream output;
        output << std::hex << std::setfill('0');
        for (uint8_t byte : bytes)
        {
            output << std::setw(2) << static_cast<unsigned>(byte);
        }
        return output.str();
    }

    CapabilityVerifierOptions OptionsFor(const std::filesystem::path &path)
    {
        return {path.string(), TestClock, EffectiveUid()};
    }

    struct ParameterBuffer
    {
        std::vector<uint32_t> words;
        size_t size{0};

        effect_param_t *parameter() noexcept
        {
            return reinterpret_cast<effect_param_t *>(words.data());
        }
    };

    ParameterBuffer BuildParameter(const std::array<uint8_t, 8> &key,
                                   const std::vector<uint8_t> &value)
    {
        constexpr size_t kAlignedKeyBytes = 8;
        const size_t size = sizeof(effect_param_t) + kAlignedKeyBytes + value.size();
        ParameterBuffer result;
        result.words.assign((size + sizeof(uint32_t) - 1) / sizeof(uint32_t), 0);
        result.size = size;
        effect_param_t *parameter = result.parameter();
        parameter->status = 0;
        parameter->psize = static_cast<uint32_t>(key.size());
        parameter->vsize = static_cast<uint32_t>(value.size());
        std::memcpy(parameter->data, key.data(), key.size());
        std::memcpy(parameter->data + kAlignedKeyBytes, value.data(), value.size());
        return result;
    }

    uint16_t ReadU16(const uint8_t *data)
    {
        return static_cast<uint16_t>(
            (static_cast<uint16_t>(data[0]) << 8U) | data[1]);
    }

    uint32_t ReadU32(const uint8_t *data)
    {
        return (static_cast<uint32_t>(data[0]) << 24U) |
               (static_cast<uint32_t>(data[1]) << 16U) |
               (static_cast<uint32_t>(data[2]) << 8U) |
               data[3];
    }

    uint64_t ReadU64(const uint8_t *data)
    {
        return (static_cast<uint64_t>(ReadU32(data)) << 32U) |
               ReadU32(data + 4);
    }

    struct TelemetryView
    {
        int32_t session_id{0};
        uint64_t generation{0};
        uint32_t sequence{0};
        uint16_t flags{0};
        uint32_t blocks{0};
        uint32_t frames{0};
        uint32_t failures{0};
        uint32_t mutations{0};
    };

    bool ReadTelemetry(EffectContext *context, TelemetryView *telemetry)
    {
        if (context == nullptr || telemetry == nullptr)
        {
            return false;
        }
        static_assert(kTelemetrySnapshotReplyBytes % sizeof(uint32_t) == 0);
        std::array<uint32_t,
                   kTelemetrySnapshotReplyBytes / sizeof(uint32_t)>
            storage{};
        auto *parameter = reinterpret_cast<effect_param_t *>(storage.data());
        parameter->psize = static_cast<uint32_t>(kTelemetrySnapshotParameter.size());
        parameter->vsize = 48;
        std::memcpy(parameter->data,
                    kTelemetrySnapshotParameter.data(),
                    kTelemetrySnapshotParameter.size());
        uint32_t reply_size = kTelemetrySnapshotReplyBytes;
        if (context->Command(EFFECT_CMD_GET_PARAM,
                             sizeof(effect_param_t) +
                                 kTelemetrySnapshotParameter.size(),
                             parameter,
                             &reply_size,
                             parameter) != 0 ||
            reply_size != kTelemetrySnapshotReplyBytes || parameter->status != 0 ||
            parameter->psize != 8 || parameter->vsize != 48)
        {
            return false;
        }
        const auto *value = reinterpret_cast<const uint8_t *>(parameter->data) + 8;
        if (std::memcmp(value, "ECHT", 4) != 0 || ReadU16(value + 4) != 1 ||
            ReadU16(value + 6) != 1 || ReadU16(value + 8) != 48)
        {
            return false;
        }
        telemetry->flags = ReadU16(value + 10);
        telemetry->session_id = static_cast<int32_t>(ReadU32(value + 12));
        telemetry->generation = ReadU64(value + 16);
        telemetry->sequence = ReadU32(value + 24);
        telemetry->blocks = ReadU32(value + 28);
        telemetry->frames = ReadU32(value + 32);
        telemetry->failures = ReadU32(value + 36);
        telemetry->mutations = ReadU32(value + 40);
        return ReadU32(value + 44) == 0;
    }

    bool Prepare(EffectContext *context)
    {
        return context && context->Initialize() == 0 &&
               context->SetConfig(MakeEffectConfig()) == 0;
    }

    bool ProcessProbe(EffectContext *context,
                      std::array<float, 8> *output,
                      int32_t expected_status = 0)
    {
        if (!context || !output)
        {
            return false;
        }
        std::array<float, 8> input{0.25f, -0.25f, 0.5f, -0.5f,
                                   0.1f, -0.1f, 0.8f, -0.8f};
        output->fill(0.0f);
        audio_buffer_t input_buffer{input.size(), {.f32 = input.data()}};
        audio_buffer_t output_buffer{output->size(), {.f32 = output->data()}};
        return context->Process(&input_buffer, &output_buffer) == expected_status;
    }

    double Magnitude(const std::array<float, 8> &samples)
    {
        double magnitude = 0.0;
        for (float sample : samples)
        {
            magnitude += std::abs(sample);
        }
        return magnitude;
    }

    bool OutputDiffers(EffectContext *context, bool expected_difference)
    {
        std::array<float, 8> input{0.25f, -0.25f, 0.5f, -0.5f,
                                   0.1f, -0.1f, 0.8f, -0.8f};
        std::array<float, 8> output{};
        if (!ProcessProbe(context, &output))
        {
            return false;
        }
        const bool differs = !std::equal(input.begin(),
                                         input.end(),
                                         output.begin(),
                                         [](float left, float right)
                                         { return std::abs(left - right) < 0.0001f; });
        return differs == expected_difference;
    }

    bool TestCanonicalVerificationAndAudio()
    {
        g_now_ms = 100000;
        SigningKey key;
        REQUIRE(key.valid());
        ScopedPath key_path(UniquePath("valid"));
        REQUIRE(key.WriteSpki(key_path.get()));
        ClaimsSpec spec;
        const auto envelope = BuildEnvelope(key, spec);

        CapabilityClaims claims;
        CapabilityVerifier verifier(OptionsFor(key_path.get()));
        REQUIRE(verifier.verify(BytesView(envelope), kSessionId, &claims) ==
                CapabilityStatus::kOk);
        REQUIRE(claims.session_id == kSessionId);
        REQUIRE(claims.target_uid == kTargetUid);
        REQUIRE(claims.process == spec.process);
        REQUIRE(claims.preset_json == spec.preset);

        EffectContext context(kSessionId, 7, OptionsFor(key_path.get()));
        REQUIRE(Prepare(&context));
        REQUIRE(context.Enable() == -EPERM);
        auto parameter = BuildParameter(
            echidna::effects::legacy::kCapabilityAuthorizeParameter, envelope);
        int32_t reply = 1;
        uint32_t reply_size = sizeof(reply);
        REQUIRE(context.Command(EFFECT_CMD_SET_PARAM,
                                static_cast<uint32_t>(parameter.size),
                                parameter.parameter(),
                                &reply_size,
                                &reply) == 0);
        REQUIRE(reply == 0 && reply_size == sizeof(reply));
        REQUIRE(context.Enable() == 0);
        REQUIRE(OutputDiffers(&context, true));

        g_now_ms = spec.expires_ms;
        REQUIRE(OutputDiffers(&context, false));
        REQUIRE(context.Reset() == 0);
        REQUIRE(OutputDiffers(&context, false));
        REQUIRE(context.Disable() == 0);
        REQUIRE(context.Enable() == -EPERM);
        return true;
    }

    bool TestVerifiedPresetSurvivesReauthorizationLifecycle()
    {
        g_now_ms = 600000;
        SigningKey key;
        REQUIRE(key.valid());
        ScopedPath key_path(UniquePath("preset-lifecycle"));
        REQUIRE(key.WriteSpki(key_path.get()));
        EffectContext context(kSessionId, 12, OptionsFor(key_path.get()));
        REQUIRE(Prepare(&context));

        ClaimsSpec initial;
        initial.generation = 7;
        initial.issued_ms = 599990;
        initial.expires_ms = 600900;
        REQUIRE(context.ApplyCapability(BytesView(BuildEnvelope(key, initial))) == 0);
        REQUIRE(context.Enable() == 0);
        std::array<float, 8> initial_output{};
        REQUIRE(ProcessProbe(&context, &initial_output));
        REQUIRE(OutputDiffers(&context, true));
        const double retained_magnitude = Magnitude(initial_output);
        REQUIRE(retained_magnitude > 0.1);

        REQUIRE(context.Disable() == 0);
        REQUIRE(context.Enable() == -EPERM);
        REQUIRE(context.SetConfig(MakeEffectConfig(
                    44100, AUDIO_CHANNEL_IN_MONO, AUDIO_FORMAT_PCM_FLOAT)) == 0);
        REQUIRE(context.Enable() == -EPERM);

        ClaimsSpec after_config = initial;
        after_config.nonce[0] = 31;
        after_config.issued_ms = 600010;
        after_config.expires_ms = 601500;
        REQUIRE(context.ApplyCapability(BytesView(BuildEnvelope(key, after_config))) == 0);
        REQUIRE(context.Enable() == 0);
        REQUIRE(OutputDiffers(&context, true));

        REQUIRE(context.Disable() == 0);
        REQUIRE(context.Reset() == 0);
        REQUIRE(context.Enable() == -EPERM);
        ClaimsSpec after_reset = after_config;
        after_reset.nonce[0] = 32;
        after_reset.issued_ms = 600020;
        after_reset.expires_ms = 602000;
        REQUIRE(context.ApplyCapability(BytesView(BuildEnvelope(key, after_reset))) == 0);
        REQUIRE(context.Enable() == 0);
        REQUIRE(OutputDiffers(&context, true));

        context.RevokeAuthorization();
        const auto before_revoke = context.telemetry();
        REQUIRE(OutputDiffers(&context, false));
        const auto after_revoke_telemetry = context.telemetry();
        REQUIRE(after_revoke_telemetry.processed_calls ==
                before_revoke.processed_calls);
        REQUIRE(after_revoke_telemetry.bypass_calls ==
                before_revoke.bypass_calls + 1);
        ClaimsSpec after_revoke = after_reset;
        after_revoke.nonce[0] = 33;
        after_revoke.issued_ms = 600030;
        after_revoke.expires_ms = 602500;
        REQUIRE(context.ApplyCapability(BytesView(BuildEnvelope(key, after_revoke))) == 0);
        REQUIRE(OutputDiffers(&context, true));

        g_now_ms = after_revoke.expires_ms;
        const auto before_expiry = context.telemetry();
        REQUIRE(OutputDiffers(&context, false));
        const auto after_expiry_telemetry = context.telemetry();
        REQUIRE(after_expiry_telemetry.processed_calls ==
                before_expiry.processed_calls);
        REQUIRE(after_expiry_telemetry.bypass_calls ==
                before_expiry.bypass_calls + 1);
        ClaimsSpec after_expiry = after_revoke;
        after_expiry.nonce[0] = 34;
        after_expiry.issued_ms = g_now_ms;
        after_expiry.expires_ms = 603500;
        REQUIRE(context.ApplyCapability(BytesView(BuildEnvelope(key, after_expiry))) == 0);
        REQUIRE(OutputDiffers(&context, true));

        REQUIRE(context.Disable() == 0);
        ClaimsSpec higher_generation = after_expiry;
        higher_generation.generation = 8;
        higher_generation.nonce[0] = 35;
        higher_generation.expires_ms = 604000;
        higher_generation.preset =
            R"({"name":"Higher","engine":{"latencyMode":"LL","blockMs":10},"modules":[{"id":"mix","wet":100,"outGain":-3}]})";
        REQUIRE(context.ApplyCapability(
                    BytesView(BuildEnvelope(key, higher_generation))) == 0);
        REQUIRE(context.Enable() == 0);
        std::array<float, 8> higher_output{};
        REQUIRE(ProcessProbe(&context, &higher_output));
        REQUIRE(Magnitude(higher_output) > retained_magnitude * 2.0);
        return true;
    }

    bool TestSignedTelemetryStateAndCumulativeCounters()
    {
        g_now_ms = 700000;
        SigningKey key;
        REQUIRE(key.valid());
        ScopedPath key_path(UniquePath("telemetry-state"));
        REQUIRE(key.WriteSpki(key_path.get()));
        EffectContext context(kSessionId, 17, OptionsFor(key_path.get()));
        REQUIRE(Prepare(&context));

        ClaimsSpec initial;
        initial.generation = 11;
        initial.issued_ms = 699990;
        initial.expires_ms = 701000;
        REQUIRE(context.ApplyCapability(BytesView(BuildEnvelope(key, initial))) == 0);

        TelemetryView snapshot;
        REQUIRE(ReadTelemetry(&context, &snapshot));
        REQUIRE(snapshot.session_id == kSessionId);
        REQUIRE(snapshot.generation == 11);
        REQUIRE(snapshot.sequence == 1);
        REQUIRE(snapshot.flags == kTelemetryAuthorized);
        REQUIRE(snapshot.blocks == 0 && snapshot.frames == 0 &&
                snapshot.failures == 0 && snapshot.mutations == 0);

        REQUIRE(context.Enable() == 0);
        REQUIRE(OutputDiffers(&context, true));
        REQUIRE(context.Process(nullptr, nullptr) == -EINVAL);
        REQUIRE(ReadTelemetry(&context, &snapshot));
        REQUIRE(snapshot.sequence == 2);
        REQUIRE(snapshot.flags == (kTelemetryEnabled | kTelemetryAuthorized));
        REQUIRE(snapshot.blocks == 2 && snapshot.frames == 8 &&
                snapshot.failures == 1 && snapshot.mutations == 1);
        const TelemetryView cumulative = snapshot;

        context.RevokeAuthorization();
        REQUIRE(ReadTelemetry(&context, &snapshot));
        REQUIRE(snapshot.sequence == 3);
        REQUIRE(snapshot.flags == kTelemetryEnabled);
        REQUIRE(snapshot.generation == 11 && snapshot.blocks == cumulative.blocks &&
                snapshot.frames == cumulative.frames &&
                snapshot.failures == cumulative.failures &&
                snapshot.mutations == cumulative.mutations);

        ClaimsSpec renewal = initial;
        renewal.nonce[0] = 61;
        renewal.issued_ms = 700010;
        renewal.expires_ms = 702000;
        REQUIRE(context.ApplyCapability(BytesView(BuildEnvelope(key, renewal))) == 0);
        REQUIRE(ReadTelemetry(&context, &snapshot));
        REQUIRE(snapshot.sequence == 4);
        REQUIRE(snapshot.flags == (kTelemetryEnabled | kTelemetryAuthorized));
        REQUIRE(snapshot.generation == 11);

        g_now_ms = renewal.expires_ms;
        REQUIRE(ReadTelemetry(&context, &snapshot));
        REQUIRE(snapshot.sequence == 5);
        REQUIRE(snapshot.flags == (kTelemetryEnabled | kTelemetryExpired));
        REQUIRE(snapshot.generation == 11);
        REQUIRE(context.Reset() == 0);
        REQUIRE(ReadTelemetry(&context, &snapshot));
        REQUIRE(snapshot.sequence == 6);
        REQUIRE(snapshot.flags == (kTelemetryEnabled | kTelemetryExpired));
        REQUIRE(snapshot.blocks == cumulative.blocks &&
                snapshot.frames == cumulative.frames &&
                snapshot.failures == cumulative.failures &&
                snapshot.mutations == cumulative.mutations);

        REQUIRE(context.Disable() == 0);
        REQUIRE(ReadTelemetry(&context, &snapshot));
        REQUIRE(snapshot.sequence == 7);
        REQUIRE(snapshot.flags == kTelemetryExpired);
        REQUIRE(snapshot.generation == 11 && snapshot.blocks == cumulative.blocks);

        ClaimsSpec higher = renewal;
        higher.generation = 12;
        higher.nonce[0] = 62;
        higher.issued_ms = g_now_ms;
        higher.expires_ms = 703000;
        REQUIRE(context.ApplyCapability(BytesView(BuildEnvelope(key, higher))) == 0);
        REQUIRE(ReadTelemetry(&context, &snapshot));
        REQUIRE(snapshot.sequence == 8);
        REQUIRE(snapshot.flags == kTelemetryAuthorized);
        REQUIRE(snapshot.generation == 12);
        REQUIRE(snapshot.blocks == cumulative.blocks &&
                snapshot.frames == cumulative.frames &&
                snapshot.failures == cumulative.failures &&
                snapshot.mutations == cumulative.mutations);
        return true;
    }

    bool TestCanonicalBodyFixture()
    {
        ClaimsSpec fixture;
        fixture.session_id = 0x01020304;
        fixture.target_uid = 10000;
        fixture.generation = 1;
        fixture.issued_ms = 100000;
        fixture.expires_ms = 105000;
        fixture.process = "com.example.recorder";
        fixture.preset =
            R"({"name":"F","engine":{"latencyMode":"LL","blockMs":10},"modules":[{"id":"mix","wet":100,"outGain":-12}]})";
        const auto body = BuildBody(fixture);
        REQUIRE(body.size() == 234);
        REQUIRE(Hex(body) ==
                "45434843000100013e66a36edee95d81a0d649fc3b86353001020304"
                "00002710000000000000000100000000000186a00000000000019a28"
                "0102030405060708090a0b0c0d0e0f1024f6e2d400359721a2066c75"
                "5d48e5deb83171e07d429b7c26c82bca8d7fa69a001400000068636f"
                "6d2e6578616d706c652e7265636f726465727b226e616d65223a2246"
                "222c22656e67696e65223a7b226c6174656e63794d6f6465223a224c"
                "4c222c22626c6f636b4d73223a31307d2c226d6f64756c6573223a5b"
                "7b226964223a226d6978222c22776574223a3130302c226f7574476169"
                "6e223a2d31327d5d7d");
        CapabilityHash digest{};
        SHA256(body.data(), body.size(), digest.data());
        REQUIRE(Hex(std::vector<uint8_t>(digest.begin(), digest.end())) ==
                "b1c81db05b2c941cfae0ae8c2f403c83585a3bd8431f76a78627c000"
                "4841c2af");
        return true;
    }

    bool TestStateTransitionsAndRevoke()
    {
        g_now_ms = 200000;
        SigningKey key;
        REQUIRE(key.valid());
        ScopedPath key_path(UniquePath("state"));
        REQUIRE(key.WriteSpki(key_path.get()));
        EffectContext context(kSessionId, 8, OptionsFor(key_path.get()));
        REQUIRE(Prepare(&context));

        ClaimsSpec initial;
        initial.generation = 2;
        initial.issued_ms = 199990;
        initial.expires_ms = 201000;
        const auto initial_envelope = BuildEnvelope(key, initial);
        REQUIRE(context.ApplyCapability(BytesView(initial_envelope)) == 0);
        REQUIRE(context.ApplyCapability(BytesView(initial_envelope)) ==
                echidna::effects::legacy::kCapabilityReplayStatus);

        ClaimsSpec renewal = initial;
        renewal.nonce[0] = 22;
        renewal.issued_ms = 200000;
        renewal.expires_ms = 202000;
        REQUIRE(context.ApplyCapability(BytesView(BuildEnvelope(key, renewal))) == 0);

        ClaimsSpec conflict = renewal;
        conflict.nonce[0] = 23;
        conflict.expires_ms = 203000;
        conflict.process = "com.example.other";
        REQUIRE(context.ApplyCapability(BytesView(BuildEnvelope(key, conflict))) ==
                echidna::effects::legacy::kCapabilityConflictStatus);
        conflict = renewal;
        conflict.nonce[0] = 24;
        conflict.expires_ms = 203000;
        conflict.target_uid++;
        REQUIRE(context.ApplyCapability(BytesView(BuildEnvelope(key, conflict))) ==
                echidna::effects::legacy::kCapabilityConflictStatus);

        ClaimsSpec rollback = renewal;
        rollback.generation = 1;
        rollback.nonce[0] = 25;
        rollback.expires_ms = 203000;
        REQUIRE(context.ApplyCapability(BytesView(BuildEnvelope(key, rollback))) ==
                echidna::effects::legacy::kCapabilityRollbackStatus);

        REQUIRE(context.Enable() == 0);
        ClaimsSpec next = renewal;
        next.generation = 3;
        next.nonce[0] = 26;
        next.expires_ms = 203000;
        REQUIRE(context.ApplyCapability(BytesView(BuildEnvelope(key, next))) ==
                echidna::effects::legacy::kCapabilityBusyStatus);

        auto revoke = BuildParameter(
            echidna::effects::legacy::kCapabilityRevokeParameter,
            std::vector<uint8_t>{0});
        int32_t reply = 1;
        uint32_t reply_size = sizeof(reply);
        REQUIRE(context.Command(EFFECT_CMD_SET_PARAM,
                                static_cast<uint32_t>(revoke.size),
                                revoke.parameter(),
                                &reply_size,
                                &reply) == 0);
        REQUIRE(reply == 0);
        REQUIRE(OutputDiffers(&context, false));

        ClaimsSpec after_revoke = renewal;
        after_revoke.nonce[0] = 27;
        after_revoke.issued_ms = 200010;
        after_revoke.expires_ms = 204000;
        REQUIRE(context.ApplyCapability(BytesView(BuildEnvelope(key, after_revoke))) == 0);
        REQUIRE(OutputDiffers(&context, true));
        REQUIRE(context.Disable() == 0);
        REQUIRE(context.Enable() == -EPERM);
        return true;
    }

    bool TestVerifierRejections()
    {
        g_now_ms = 300000;
        SigningKey key;
        REQUIRE(key.valid());
        ScopedPath key_path(UniquePath("reject"));
        REQUIRE(key.WriteSpki(key_path.get()));
        CapabilityVerifier verifier(OptionsFor(key_path.get()));
        CapabilityClaims claims;
        ClaimsSpec base;
        base.issued_ms = 299990;
        base.expires_ms = 301000;

        ClaimsSpec invalid = base;
        invalid.session_id++;
        REQUIRE(verifier.verify(BytesView(BuildEnvelope(key, invalid)),
                                kSessionId,
                                &claims) == CapabilityStatus::kWrongSession);
        invalid = base;
        invalid.implementation_uuid[0] ^= 1;
        REQUIRE(verifier.verify(BytesView(BuildEnvelope(key, invalid)),
                                kSessionId,
                                &claims) == CapabilityStatus::kMalformed);
        for (const uint32_t uid : {9999u, 100000u})
        {
            invalid = base;
            invalid.target_uid = uid;
            REQUIRE(verifier.verify(BytesView(BuildEnvelope(key, invalid)),
                                    kSessionId,
                                    &claims) == CapabilityStatus::kMalformed);
        }
        for (const std::string process : {"single", "com..bad", "com.ok:",
                                          "1com.example"})
        {
            invalid = base;
            invalid.process = process;
            REQUIRE(verifier.verify(BytesView(BuildEnvelope(key, invalid)),
                                    kSessionId,
                                    &claims) == CapabilityStatus::kMalformed);
        }
        invalid = base;
        invalid.generation = 0;
        REQUIRE(verifier.verify(BytesView(BuildEnvelope(key, invalid)),
                                kSessionId,
                                &claims) == CapabilityStatus::kMalformed);
        invalid = base;
        invalid.nonce.fill(0);
        REQUIRE(verifier.verify(BytesView(BuildEnvelope(key, invalid)),
                                kSessionId,
                                &claims) == CapabilityStatus::kMalformed);
        invalid = base;
        invalid.corrupt_hash = true;
        REQUIRE(verifier.verify(BytesView(BuildEnvelope(key, invalid)),
                                kSessionId,
                                &claims) == CapabilityStatus::kMalformed);

        for (const auto &[issued, expires] :
             std::array<std::array<uint64_t, 2>, 4>{{
                 {300251, 301000},
                 {299000, 300000},
                 {299000, 304001},
                 {301000, 301000},
             }})
        {
            invalid = base;
            invalid.issued_ms = issued;
            invalid.expires_ms = expires;
            REQUIRE(verifier.verify(BytesView(BuildEnvelope(key, invalid)),
                                    kSessionId,
                                    &claims) == CapabilityStatus::kExpired);
        }

        auto tampered = BuildEnvelope(key, base);
        tampered.back() ^= 1;
        REQUIRE(verifier.verify(BytesView(tampered), kSessionId, &claims) ==
                CapabilityStatus::kSignatureInvalid);

        invalid = base;
        invalid.preset.assign(echidna::effects::legacy::kCapabilityMaxPresetBytes,
                              ' ');
        REQUIRE(verifier.verify(BytesView(BuildEnvelope(key, invalid)),
                                kSessionId,
                                &claims) == CapabilityStatus::kOk);
        invalid.preset.push_back(' ');
        REQUIRE(verifier.verify(BytesView(BuildEnvelope(key, invalid)),
                                kSessionId,
                                &claims) == CapabilityStatus::kMalformed);
        return true;
    }

    bool TestPermanentKeyFailure()
    {
        g_now_ms = 400000;
        SigningKey trusted;
        SigningKey other;
        REQUIRE(trusted.valid() && other.valid());
        ScopedPath trusted_path(UniquePath("trusted"));
        REQUIRE(trusted.WriteSpki(trusted_path.get()));
        ClaimsSpec spec;
        spec.issued_ms = 399990;
        spec.expires_ms = 401000;

        EffectContext wrong_key(kSessionId, 9, OptionsFor(trusted_path.get()));
        REQUIRE(Prepare(&wrong_key));
        REQUIRE(wrong_key.ApplyCapability(BytesView(BuildEnvelope(other, spec))) ==
                static_cast<int32_t>(CapabilityStatus::kSignatureInvalid));
        spec.nonce[0]++;
        REQUIRE(wrong_key.ApplyCapability(BytesView(BuildEnvelope(trusted, spec))) ==
                static_cast<int32_t>(CapabilityStatus::kSignatureInvalid));
        REQUIRE(wrong_key.Enable() == -EPERM);

        ScopedPath missing_path(UniquePath("missing"));
        EffectContext missing(kSessionId, 10, OptionsFor(missing_path.get()));
        REQUIRE(Prepare(&missing));
        REQUIRE(missing.ApplyCapability(BytesView(BuildEnvelope(trusted, spec))) ==
                static_cast<int32_t>(CapabilityStatus::kKeyUnavailable));
        REQUIRE(trusted.WriteSpki(missing_path.get()));
        spec.nonce[0]++;
        REQUIRE(missing.ApplyCapability(BytesView(BuildEnvelope(trusted, spec))) ==
                static_cast<int32_t>(CapabilityStatus::kKeyUnavailable));

        ScopedPath malformed_path(UniquePath("malformed"));
        {
            std::ofstream malformed(malformed_path.get(),
                                    std::ios::binary | std::ios::trunc);
            malformed << "not-spki";
        }
#ifndef _WIN32
        REQUIRE(::chmod(malformed_path.get().c_str(), 0600) == 0);
#endif
        CapabilityVerifier malformed(OptionsFor(malformed_path.get()));
        CapabilityClaims claims;
        REQUIRE(malformed.verify(BytesView(BuildEnvelope(trusted, spec)),
                                 kSessionId,
                                 &claims) == CapabilityStatus::kSignatureInvalid);

#ifndef _WIN32
        ScopedPath unsafe_path(UniquePath("unsafe"));
        REQUIRE(trusted.WriteSpki(unsafe_path.get()));
        REQUIRE(::chmod(unsafe_path.get().c_str(), 0666) == 0);
        CapabilityVerifier unsafe(OptionsFor(unsafe_path.get()));
        REQUIRE(unsafe.verify(BytesView(BuildEnvelope(trusted, spec)),
                              kSessionId,
                              &claims) == CapabilityStatus::kKeyUnavailable);

        ScopedPath target_path(UniquePath("target"));
        ScopedPath link_path(UniquePath("link"));
        REQUIRE(trusted.WriteSpki(target_path.get()));
        std::filesystem::create_symlink(target_path.get(), link_path.get());
        CapabilityVerifier symlink(OptionsFor(link_path.get()));
        REQUIRE(symlink.verify(BytesView(BuildEnvelope(trusted, spec)),
                               kSessionId,
                               &claims) == CapabilityStatus::kKeyUnavailable);
#endif
        return true;
    }

    bool TestFramingAndDeadlineWrap()
    {
        static_assert(sizeof(effect_param_t) == 12);
        static_assert(offsetof(effect_param_t, data) == 12);
        static_assert(alignof(effect_param_t) == alignof(uint32_t));
        REQUIRE(CapabilityVerifier::IsActiveDeadline(0xfffffff0u, 0x10u));
        REQUIRE(!CapabilityVerifier::IsActiveDeadline(0x20u, 0x10u));
        REQUIRE(!CapabilityVerifier::IsActiveDeadline(100u, 0u));
        REQUIRE(!CapabilityVerifier::IsActiveDeadline(0u, 0x80000000u));

        g_now_ms = 500000;
        SigningKey key;
        REQUIRE(key.valid());
        ScopedPath key_path(UniquePath("framing"));
        REQUIRE(key.WriteSpki(key_path.get()));
        ClaimsSpec spec;
        spec.issued_ms = 499990;
        spec.expires_ms = 501000;
        const auto envelope = BuildEnvelope(key, spec);
        auto parameter = BuildParameter(
            echidna::effects::legacy::kCapabilityAuthorizeParameter, envelope);
        REQUIRE(echidna::effects::legacy::IsAuthorizeParameter(
            *parameter.parameter(), parameter.size));
        REQUIRE(echidna::effects::legacy::EffectParameterValue(
                    *parameter.parameter(), parameter.size) == BytesView(envelope));
        REQUIRE(!echidna::effects::legacy::IsAuthorizeParameter(
            *parameter.parameter(), parameter.size - 1));
        parameter.parameter()->vsize++;
        REQUIRE(!echidna::effects::legacy::IsAuthorizeParameter(
            *parameter.parameter(), parameter.size));
        parameter.parameter()->vsize--;
        parameter.parameter()->psize = 7;
        REQUIRE(!echidna::effects::legacy::IsAuthorizeParameter(
            *parameter.parameter(), parameter.size));

        EffectContext context(kSessionId, 11, OptionsFor(key_path.get()));
        REQUIRE(Prepare(&context));
        std::vector<uint8_t> misaligned(parameter.size + 1);
        std::memcpy(misaligned.data() + 1, parameter.parameter(), parameter.size);
        int32_t reply = 0;
        uint32_t reply_size = sizeof(reply);
        REQUIRE(context.Command(EFFECT_CMD_SET_PARAM,
                                static_cast<uint32_t>(parameter.size),
                                misaligned.data() + 1,
                                &reply_size,
                                &reply) == -EINVAL);

        std::vector<uint32_t> oversized(
            (echidna::effects::legacy::kEffectParamMaxBytes + 4) / 4, 0);
        auto *oversized_parameter =
            reinterpret_cast<effect_param_t *>(oversized.data());
        oversized_parameter->psize = 8;
        oversized_parameter->vsize = static_cast<uint32_t>(
            echidna::effects::legacy::kEffectParamMaxBytes -
            sizeof(effect_param_t) - 8 + 1);
        REQUIRE(!echidna::effects::legacy::IsAuthorizeParameter(
            *oversized_parameter,
            echidna::effects::legacy::kEffectParamMaxBytes + 1));

        auto revoke = BuildParameter(
            echidna::effects::legacy::kCapabilityRevokeParameter,
            std::vector<uint8_t>{0});
        REQUIRE(echidna::effects::legacy::IsRevokeParameter(
            *revoke.parameter(), revoke.size));
        revoke.parameter()->data[8] = 1;
        REQUIRE(!echidna::effects::legacy::IsRevokeParameter(
            *revoke.parameter(), revoke.size));
        return true;
    }
#endif
} // namespace

int main()
{
#ifndef ECHIDNA_HAS_BORINGSSL
    std::cout << "SKIP: capability crypto unavailable (production verifier fails closed)\n";
    return 0;
#else
    CHECK_TRUE(TestCanonicalVerificationAndAudio());
    CHECK_TRUE(TestVerifiedPresetSurvivesReauthorizationLifecycle());
    CHECK_TRUE(TestSignedTelemetryStateAndCumulativeCounters());
    CHECK_TRUE(TestCanonicalBodyFixture());
    CHECK_TRUE(TestStateTransitionsAndRevoke());
    CHECK_TRUE(TestVerifierRejections());
    CHECK_TRUE(TestPermanentKeyFailure());
    CHECK_TRUE(TestFramingAndDeadlineWrap());
    return 0;
#endif
}
