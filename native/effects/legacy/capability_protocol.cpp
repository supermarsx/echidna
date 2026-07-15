#include "capability_protocol.h"

#include <algorithm>
#include <array>
#include <cerrno>
#include <chrono>
#include <cstring>
#include <fstream>
#include <limits>
#include <utility>
#include <vector>

#ifndef _WIN32
#include <fcntl.h>
#include <sys/stat.h>
#include <time.h>
#include <unistd.h>
#endif

#ifdef ECHIDNA_HAS_BORINGSSL
#include <openssl/crypto.h>
#include <openssl/ec.h>
#include <openssl/evp.h>
#include <openssl/obj_mac.h>
#include <openssl/sha.h>
#include <openssl/x509.h>
#endif

namespace echidna::effects::legacy
{
    namespace
    {
        constexpr uint16_t kAuthorizeFlag = 1;
        constexpr size_t kMaximumSpkiBytes = 1024;

        uint16_t ReadU16(const uint8_t *bytes) noexcept
        {
            return static_cast<uint16_t>((static_cast<uint16_t>(bytes[0]) << 8) |
                                         static_cast<uint16_t>(bytes[1]));
        }

        uint32_t ReadU32(const uint8_t *bytes) noexcept
        {
            return (static_cast<uint32_t>(bytes[0]) << 24) |
                   (static_cast<uint32_t>(bytes[1]) << 16) |
                   (static_cast<uint32_t>(bytes[2]) << 8) |
                   static_cast<uint32_t>(bytes[3]);
        }

        uint64_t ReadU64(const uint8_t *bytes) noexcept
        {
            uint64_t value = 0;
            for (size_t index = 0; index < 8; ++index)
            {
                value = (value << 8) | bytes[index];
            }
            return value;
        }

        bool AddWouldOverflow(size_t left, size_t right) noexcept
        {
            return left > std::numeric_limits<size_t>::max() - right;
        }

        bool ValidUtf8(std::string_view value) noexcept
        {
            size_t index = 0;
            while (index < value.size())
            {
                const uint8_t first = static_cast<uint8_t>(value[index]);
                if (first <= 0x7f)
                {
                    ++index;
                    continue;
                }
                size_t extra = 0;
                uint32_t codepoint = 0;
                if (first >= 0xc2 && first <= 0xdf)
                {
                    extra = 1;
                    codepoint = first & 0x1f;
                }
                else if (first >= 0xe0 && first <= 0xef)
                {
                    extra = 2;
                    codepoint = first & 0x0f;
                }
                else if (first >= 0xf0 && first <= 0xf4)
                {
                    extra = 3;
                    codepoint = first & 0x07;
                }
                else
                {
                    return false;
                }
                if (index + extra >= value.size())
                {
                    return false;
                }
                for (size_t offset = 1; offset <= extra; ++offset)
                {
                    const uint8_t continuation =
                        static_cast<uint8_t>(value[index + offset]);
                    if ((continuation & 0xc0) != 0x80)
                    {
                        return false;
                    }
                    codepoint = (codepoint << 6) | (continuation & 0x3f);
                }
                if ((extra == 2 && codepoint < 0x800) ||
                    (extra == 3 && codepoint < 0x10000) ||
                    (codepoint >= 0xd800 && codepoint <= 0xdfff) ||
                    codepoint > 0x10ffff)
                {
                    return false;
                }
                index += extra + 1;
            }
            return true;
        }

        bool ValidProcess(std::string_view process) noexcept
        {
            if (process.empty() || process.size() > kCapabilityMaxProcessBytes ||
                !ValidUtf8(process))
            {
                return false;
            }

            const size_t colon = process.find(':');
            if (colon != std::string_view::npos &&
                (colon == 0 || colon + 1 == process.size() ||
                 process.find(':', colon + 1) != std::string_view::npos))
            {
                return false;
            }

            const auto ascii_alpha = [](char value)
            {
                return (value >= 'a' && value <= 'z') ||
                       (value >= 'A' && value <= 'Z');
            };
            const auto ascii_alnum_or_underscore = [&](char value)
            {
                return ascii_alpha(value) || (value >= '0' && value <= '9') ||
                       value == '_';
            };
            const std::string_view package = process.substr(0, colon);
            bool saw_dot = false;
            size_t component_start = 0;
            while (component_start < package.size())
            {
                const size_t dot = package.find('.', component_start);
                const size_t component_end =
                    dot == std::string_view::npos ? package.size() : dot;
                if (component_end == component_start ||
                    !ascii_alpha(package[component_start]))
                {
                    return false;
                }
                for (size_t index = component_start + 1; index < component_end;
                     ++index)
                {
                    if (!ascii_alnum_or_underscore(package[index]))
                    {
                        return false;
                    }
                }
                if (dot == std::string_view::npos)
                {
                    break;
                }
                saw_dot = true;
                component_start = dot + 1;
            }
            if (!saw_dot || package.back() == '.')
            {
                return false;
            }
            if (colon != std::string_view::npos)
            {
                const std::string_view suffix = process.substr(colon + 1);
                if (!ascii_alpha(suffix.front()) ||
                    !std::all_of(suffix.begin() + 1,
                                 suffix.end(),
                                 ascii_alnum_or_underscore))
                {
                    return false;
                }
            }
            return true;
        }

        bool NonZero(const CapabilityNonce &nonce) noexcept
        {
            return std::any_of(nonce.begin(), nonce.end(), [](uint8_t value)
                               { return value != 0; });
        }

        uint64_t DefaultClock() noexcept
        {
#ifndef _WIN32
            timespec now{};
            if (::clock_gettime(CLOCK_BOOTTIME, &now) != 0)
            {
                return 0;
            }
            return static_cast<uint64_t>(now.tv_sec) * 1000u +
                   static_cast<uint64_t>(now.tv_nsec / 1000000u);
#else
            return static_cast<uint64_t>(std::chrono::duration_cast<std::chrono::milliseconds>(
                                             std::chrono::steady_clock::now().time_since_epoch())
                                             .count());
#endif
        }

        bool ReadSafeSpki(const std::string &path,
                          uint32_t required_owner_uid,
                          std::vector<uint8_t> *output)
        {
            if (!output)
            {
                return false;
            }
#ifndef _WIN32
            const int fd = ::open(path.c_str(), O_RDONLY | O_CLOEXEC | O_NOFOLLOW);
            if (fd < 0)
            {
                return false;
            }
            struct stat status{};
            const bool safe = ::fstat(fd, &status) == 0 && S_ISREG(status.st_mode) &&
                              status.st_uid == required_owner_uid &&
                              (status.st_mode & 0022) == 0 &&
                              status.st_size > 0 &&
                              status.st_size <= static_cast<off_t>(kMaximumSpkiBytes);
            if (!safe)
            {
                ::close(fd);
                return false;
            }
            output->assign(static_cast<size_t>(status.st_size), 0);
            size_t received = 0;
            while (received < output->size())
            {
                const ssize_t result =
                    ::read(fd, output->data() + received, output->size() - received);
                if (result < 0 && errno == EINTR)
                {
                    continue;
                }
                if (result <= 0)
                {
                    output->clear();
                    ::close(fd);
                    return false;
                }
                received += static_cast<size_t>(result);
            }
            struct stat after{};
            const bool unchanged = ::fstat(fd, &after) == 0 &&
                                   after.st_dev == status.st_dev &&
                                   after.st_ino == status.st_ino &&
                                   after.st_size == status.st_size &&
                                   after.st_uid == status.st_uid &&
                                   (after.st_mode & 0022) == 0;
            ::close(fd);
            return unchanged;
#else
            std::ifstream file(path, std::ios::binary | std::ios::ate);
            if (!file)
            {
                return false;
            }
            const std::streamoff size = file.tellg();
            if (size <= 0 || size > static_cast<std::streamoff>(kMaximumSpkiBytes))
            {
                return false;
            }
            output->assign(static_cast<size_t>(size), 0);
            file.seekg(0);
            file.read(reinterpret_cast<char *>(output->data()), size);
            return file.good();
#endif
        }

#ifdef ECHIDNA_HAS_BORINGSSL
        bool HashMatches(std::string_view preset, const CapabilityHash &expected)
        {
            CapabilityHash actual{};
            if (!SHA256(reinterpret_cast<const uint8_t *>(preset.data()),
                        preset.size(),
                        actual.data()))
            {
                return false;
            }
            return CRYPTO_memcmp(actual.data(), expected.data(), actual.size()) == 0;
        }

        bool VerifySignature(const std::vector<uint8_t> &spki,
                             std::string_view body,
                             std::string_view signature)
        {
            const uint8_t *cursor = spki.data();
            EVP_PKEY *key = d2i_PUBKEY(nullptr, &cursor, spki.size());
            if (!key || cursor != spki.data() + spki.size() ||
                EVP_PKEY_id(key) != EVP_PKEY_EC || EVP_PKEY_bits(key) != 256)
            {
                EVP_PKEY_free(key);
                return false;
            }
#if defined(OPENSSL_VERSION_MAJOR) && OPENSSL_VERSION_MAJOR >= 3 && \
    !defined(OPENSSL_IS_BORINGSSL)
            std::array<char, 32> group_name{};
            size_t group_name_size = 0;
            const bool is_p256 =
                EVP_PKEY_get_group_name(key,
                                        group_name.data(),
                                        group_name.size(),
                                        &group_name_size) == 1 &&
                std::string_view(group_name.data()) == "prime256v1";
#else
            const EC_KEY *ec_key = EVP_PKEY_get0_EC_KEY(key);
            const EC_GROUP *group = ec_key ? EC_KEY_get0_group(ec_key) : nullptr;
            const bool is_p256 =
                group && EC_GROUP_get_curve_name(group) == NID_X9_62_prime256v1;
#endif
            if (!is_p256)
            {
                EVP_PKEY_free(key);
                return false;
            }
            EVP_MD_CTX *context = EVP_MD_CTX_new();
            const bool valid = context &&
                               EVP_DigestVerifyInit(context,
                                                    nullptr,
                                                    EVP_sha256(),
                                                    nullptr,
                                                    key) == 1 &&
                               EVP_DigestVerify(
                                   context,
                                   reinterpret_cast<const uint8_t *>(signature.data()),
                                   signature.size(),
                                   reinterpret_cast<const uint8_t *>(body.data()),
                                   body.size()) == 1;
            EVP_MD_CTX_free(context);
            EVP_PKEY_free(key);
            return valid;
        }
#endif

        size_t AlignedParameterSize(uint32_t size) noexcept
        {
            if (size > std::numeric_limits<uint32_t>::max() - 3u)
            {
                return 0;
            }
            return static_cast<size_t>((size + 3u) & ~3u);
        }

        bool ValidParameterLayout(const effect_param_t &parameter,
                                  size_t command_size) noexcept
        {
            const size_t aligned = AlignedParameterSize(parameter.psize);
            if (aligned == 0 || parameter.psize == 0 || parameter.vsize == 0 ||
                command_size > kEffectParamMaxBytes ||
                AddWouldOverflow(sizeof(effect_param_t), aligned) ||
                AddWouldOverflow(sizeof(effect_param_t) + aligned, parameter.vsize))
            {
                return false;
            }
            return sizeof(effect_param_t) + aligned + parameter.vsize == command_size;
        }
    } // namespace

    CapabilityVerifier::CapabilityVerifier(CapabilityVerifierOptions options)
        : options_(std::move(options))
    {
        if (!options_.clock)
        {
            options_.clock = DefaultClock;
        }
    }

    uint64_t CapabilityVerifier::nowBoottimeMs() const noexcept
    {
        return options_.clock ? options_.clock() : 0;
    }

    bool CapabilityVerifier::IsActiveDeadline(uint32_t now_ms,
                                              uint32_t deadline_ms) noexcept
    {
        return deadline_ms != 0 && static_cast<int32_t>(deadline_ms - now_ms) > 0;
    }

    CapabilityStatus CapabilityVerifier::verify(std::string_view value,
                                                int32_t expected_session,
                                                CapabilityClaims *claims) const
    {
        if (!claims || value.size() < kCapabilityFixedBodyBytes + 2 ||
            value.size() > kEffectParamMaxBytes - sizeof(effect_param_t) - 8)
        {
            return CapabilityStatus::kMalformed;
        }
        const auto *bytes = reinterpret_cast<const uint8_t *>(value.data());
        if (value.substr(0, 4) != kCapabilityMagic ||
            ReadU16(bytes + 4) != kCapabilitySchema ||
            ReadU16(bytes + 6) != kAuthorizeFlag ||
            !std::equal(kEffectImplementationUuidBytes.begin(),
                        kEffectImplementationUuidBytes.end(),
                        bytes + 8))
        {
            return CapabilityStatus::kMalformed;
        }

        CapabilityClaims candidate;
        candidate.session_id = static_cast<int32_t>(ReadU32(bytes + 24));
        candidate.target_uid = ReadU32(bytes + 28);
        candidate.generation = ReadU64(bytes + 32);
        candidate.issued_boottime_ms = ReadU64(bytes + 40);
        candidate.expires_boottime_ms = ReadU64(bytes + 48);
        std::copy_n(bytes + 56, candidate.nonce.size(), candidate.nonce.begin());
        std::copy_n(bytes + 72, candidate.preset_hash.size(), candidate.preset_hash.begin());
        const size_t process_size = ReadU16(bytes + 104);
        const size_t preset_size = ReadU32(bytes + 106);
        if (candidate.session_id <= 0 || candidate.session_id != expected_session ||
            candidate.target_uid < 10000 || candidate.target_uid >= 100000 ||
            candidate.generation == 0 ||
            candidate.generation > static_cast<uint64_t>(std::numeric_limits<int64_t>::max()) ||
            process_size == 0 || process_size > kCapabilityMaxProcessBytes ||
            preset_size == 0 || preset_size > kCapabilityMaxPresetBytes ||
            !NonZero(candidate.nonce) ||
            AddWouldOverflow(kCapabilityFixedBodyBytes, process_size) ||
            AddWouldOverflow(kCapabilityFixedBodyBytes + process_size, preset_size))
        {
            return candidate.session_id != expected_session
                       ? CapabilityStatus::kWrongSession
                       : CapabilityStatus::kMalformed;
        }
        const size_t body_size = kCapabilityFixedBodyBytes + process_size + preset_size;
        if (AddWouldOverflow(body_size, 2) || body_size + 2 > value.size())
        {
            return CapabilityStatus::kMalformed;
        }
        const size_t signature_size = ReadU16(bytes + body_size);
        if (signature_size < 64 || signature_size > 80 ||
            body_size + 2 + signature_size != value.size())
        {
            return CapabilityStatus::kMalformed;
        }
        candidate.process.assign(value.substr(kCapabilityFixedBodyBytes, process_size));
        candidate.preset_json.assign(
            value.substr(kCapabilityFixedBodyBytes + process_size, preset_size));
        if (!ValidProcess(candidate.process) || !ValidUtf8(candidate.preset_json))
        {
            return CapabilityStatus::kMalformed;
        }

        const uint64_t now = nowBoottimeMs();
        const bool issued_too_far_in_future =
            candidate.issued_boottime_ms > now &&
            candidate.issued_boottime_ms - now > kCapabilityFutureSkewMs;
        if (now == 0 || issued_too_far_in_future ||
            candidate.expires_boottime_ms <= now ||
            candidate.expires_boottime_ms <= candidate.issued_boottime_ms ||
            candidate.expires_boottime_ms - candidate.issued_boottime_ms >
                kCapabilityMaxLifetimeMs)
        {
            return CapabilityStatus::kExpired;
        }

#ifdef ECHIDNA_HAS_BORINGSSL
        if (!HashMatches(candidate.preset_json, candidate.preset_hash))
        {
            return CapabilityStatus::kMalformed;
        }
        std::vector<uint8_t> spki;
        if (!ReadSafeSpki(options_.spki_path,
                          options_.required_spki_owner_uid,
                          &spki))
        {
            return CapabilityStatus::kKeyUnavailable;
        }
        const std::string_view body = value.substr(0, body_size);
        const std::string_view signature = value.substr(body_size + 2, signature_size);
        if (!VerifySignature(spki, body, signature))
        {
            return CapabilityStatus::kSignatureInvalid;
        }
#else
        (void)body_size;
        return CapabilityStatus::kKeyUnavailable;
#endif
        *claims = std::move(candidate);
        return CapabilityStatus::kOk;
    }

    bool IsAuthorizeParameter(const effect_param_t &parameter,
                              size_t command_size) noexcept
    {
        return ValidParameterLayout(parameter, command_size) &&
               parameter.psize == kCapabilityAuthorizeParameter.size() &&
               std::memcmp(parameter.data,
                           kCapabilityAuthorizeParameter.data(),
                           kCapabilityAuthorizeParameter.size()) == 0;
    }

    bool IsRevokeParameter(const effect_param_t &parameter,
                           size_t command_size) noexcept
    {
        if (!ValidParameterLayout(parameter, command_size) ||
            parameter.psize != kCapabilityRevokeParameter.size() ||
            parameter.vsize != 1 || parameter.data[8] != 0)
        {
            return false;
        }
        return std::memcmp(parameter.data,
                           kCapabilityRevokeParameter.data(),
                           kCapabilityRevokeParameter.size()) == 0;
    }

    std::string_view EffectParameterValue(const effect_param_t &parameter,
                                          size_t command_size) noexcept
    {
        if (!ValidParameterLayout(parameter, command_size))
        {
            return {};
        }
        const size_t offset = AlignedParameterSize(parameter.psize);
        return {parameter.data + offset, parameter.vsize};
    }

} // namespace echidna::effects::legacy
