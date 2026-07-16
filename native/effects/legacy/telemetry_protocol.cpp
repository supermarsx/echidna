#include "telemetry_protocol.h"

#include <algorithm>
#include <cerrno>
#include <cstring>
#include <fstream>
#include <string_view>
#include <utility>

#ifndef _WIN32
#include <fcntl.h>
#include <sys/stat.h>
#include <unistd.h>
#endif

#ifdef ECHIDNA_HAS_BORINGSSL
#include <openssl/crypto.h>
#include <openssl/evp.h>
#include <openssl/hmac.h>
#include <openssl/sha.h>
#endif

#include "capability_protocol.h"

namespace echidna::effects::legacy
{
    namespace
    {
        inline constexpr std::string_view kTelemetryProofDomain =
            "ECHIDNA_PREPROCESSOR_TELEMETRY_PROOF_V2";

        template <size_t Size>
        void WriteUint16(std::array<uint8_t, Size> &encoded,
                         size_t offset,
                         uint16_t value) noexcept
        {
            encoded[offset] = static_cast<uint8_t>(value >> 8U);
            encoded[offset + 1] = static_cast<uint8_t>(value);
        }

        template <size_t Size>
        void WriteUint32(std::array<uint8_t, Size> &encoded,
                         size_t offset,
                         uint32_t value) noexcept
        {
            encoded[offset] = static_cast<uint8_t>(value >> 24U);
            encoded[offset + 1] = static_cast<uint8_t>(value >> 16U);
            encoded[offset + 2] = static_cast<uint8_t>(value >> 8U);
            encoded[offset + 3] = static_cast<uint8_t>(value);
        }

        template <size_t Size>
        void WriteUint64(std::array<uint8_t, Size> &encoded,
                         size_t offset,
                         uint64_t value) noexcept
        {
            WriteUint32(encoded, offset, static_cast<uint32_t>(value >> 32U));
            WriteUint32(encoded, offset + 4, static_cast<uint32_t>(value));
        }

        uint16_t ReadUint16(const uint8_t *encoded) noexcept
        {
            return static_cast<uint16_t>(
                (static_cast<uint16_t>(encoded[0]) << 8U) | encoded[1]);
        }

        uint32_t ReadUint32(const uint8_t *encoded) noexcept
        {
            return (static_cast<uint32_t>(encoded[0]) << 24U) |
                   (static_cast<uint32_t>(encoded[1]) << 16U) |
                   (static_cast<uint32_t>(encoded[2]) << 8U) |
                   encoded[3];
        }

        uint64_t ReadUint64(const uint8_t *encoded) noexcept
        {
            return (static_cast<uint64_t>(ReadUint32(encoded)) << 32U) |
                   ReadUint32(encoded + 4);
        }

        bool NonZero(const TelemetryProofNonce &nonce) noexcept
        {
            return std::any_of(nonce.begin(), nonce.end(), [](uint8_t value)
                               { return value != 0; });
        }

        void SecureClear(uint8_t *bytes, size_t size) noexcept
        {
#ifdef ECHIDNA_HAS_BORINGSSL
            OPENSSL_cleanse(bytes, size);
#else
            volatile uint8_t *cursor = bytes;
            while (size-- > 0)
            {
                *cursor++ = 0;
            }
#endif
        }

        bool ConstantTimeEqual(const uint8_t *left,
                               const uint8_t *right,
                               size_t size) noexcept
        {
#ifdef ECHIDNA_HAS_BORINGSSL
            return CRYPTO_memcmp(left, right, size) == 0;
#else
            uint8_t difference = 0;
            for (size_t index = 0; index < size; ++index)
            {
                difference |= left[index] ^ right[index];
            }
            return difference == 0;
#endif
        }

#ifdef ECHIDNA_HAS_BORINGSSL
        bool ReadSafeKey(const TelemetryProofKeyOptions &options,
                         TelemetryProofKey *key) noexcept
        {
            if (key == nullptr || options.key_path.empty() ||
                (options.required_mode & (0222U | 0007U)) != 0)
            {
                return false;
            }
#ifndef _WIN32
            const int fd = ::open(options.key_path.c_str(),
                                  O_RDONLY | O_CLOEXEC | O_NOFOLLOW);
            if (fd < 0)
            {
                return false;
            }
            struct stat before
            {
            };
            const bool safe =
                ::fstat(fd, &before) == 0 && S_ISREG(before.st_mode) &&
                before.st_size == static_cast<off_t>(key->size()) &&
                before.st_uid == options.required_owner_uid &&
                before.st_gid == options.required_group_gid &&
                (static_cast<uint32_t>(before.st_mode) & 0777U) ==
                    options.required_mode;
            if (!safe)
            {
                ::close(fd);
                return false;
            }

            size_t received = 0;
            while (received < key->size())
            {
                const ssize_t result =
                    ::read(fd, key->data() + received, key->size() - received);
                if (result < 0 && errno == EINTR)
                {
                    continue;
                }
                if (result <= 0)
                {
                    SecureClear(key->data(), key->size());
                    ::close(fd);
                    return false;
                }
                received += static_cast<size_t>(result);
            }

            struct stat after
            {
            };
            const bool unchanged =
                ::fstat(fd, &after) == 0 && after.st_dev == before.st_dev &&
                after.st_ino == before.st_ino && after.st_size == before.st_size &&
                after.st_uid == before.st_uid && after.st_gid == before.st_gid &&
                (static_cast<uint32_t>(after.st_mode) & 0777U) ==
                    options.required_mode;
            ::close(fd);
            if (!unchanged)
            {
                SecureClear(key->data(), key->size());
            }
            return unchanged;
#else
            std::ifstream input(options.key_path, std::ios::binary | std::ios::ate);
            if (!input || input.tellg() != static_cast<std::streamoff>(key->size()))
            {
                return false;
            }
            input.seekg(0);
            input.read(reinterpret_cast<char *>(key->data()),
                       static_cast<std::streamsize>(key->size()));
            if (input.gcount() != static_cast<std::streamsize>(key->size()))
            {
                SecureClear(key->data(), key->size());
                return false;
            }
            return true;
#endif
        }
#endif

        bool ValidTelemetryValueHeader(
            const std::array<uint8_t, kTelemetryProofValueBytes> &value) noexcept
        {
            TelemetryProofNonce nonce{};
            std::copy_n(value.begin() + 24, nonce.size(), nonce.begin());
            return std::equal(kTelemetryProofParameter.begin(),
                              kTelemetryProofParameter.begin() + 4,
                              value.begin()) &&
                   ReadUint16(value.data() + 4) == kTelemetryProofSchema &&
                   ReadUint16(value.data() + 6) == kTelemetryProofParameterId &&
                   ReadUint16(value.data() + 8) == kTelemetryProofValueBytes &&
                   (ReadUint16(value.data() + 10) &
                    ~(kTelemetryEnabled | kTelemetryAuthorized |
                      kTelemetryExpired)) == 0 &&
                   ReadUint64(value.data() + 16) != 0 && NonZero(nonce) &&
                   ReadUint32(value.data() + 76) == 0;
        }
    } // namespace

    TelemetryProofSigner::TelemetryProofSigner(TelemetryProofKeyOptions options)
        : options_(std::move(options))
    {
    }

    TelemetryProofSigner::~TelemetryProofSigner()
    {
        Clear();
    }

    void TelemetryProofSigner::Clear() noexcept
    {
        SecureClear(key_.data(), key_.size());
        SecureClear(key_id_.data(), key_id_.size());
        available_ = false;
    }

    bool TelemetryProofSigner::Load() noexcept
    {
        Clear();
#ifndef ECHIDNA_HAS_BORINGSSL
        return false;
#else
        TelemetryProofKey candidate{};
        if (!ReadSafeKey(options_, &candidate))
        {
            return false;
        }
        std::array<uint8_t, SHA256_DIGEST_LENGTH> digest{};
        const bool hashed = SHA256(candidate.data(), candidate.size(), digest.data()) !=
                            nullptr;
        if (!hashed)
        {
            SecureClear(candidate.data(), candidate.size());
            return false;
        }
        key_ = candidate;
        std::copy_n(digest.begin(), key_id_.size(), key_id_.begin());
        SecureClear(candidate.data(), candidate.size());
        SecureClear(digest.data(), digest.size());
        available_ = true;
        return true;
#endif
    }

    bool TelemetryProofSigner::Sign(
        const std::array<uint8_t, kTelemetryProofAuthenticatedBodyBytes> &body,
        TelemetryProofTag *tag) const noexcept
    {
        if (!available_ || tag == nullptr)
        {
            return false;
        }
#ifndef ECHIDNA_HAS_BORINGSSL
        return false;
#else
        std::array<uint8_t,
                   kTelemetryProofDomain.size() +
                       kTelemetryProofAuthenticatedBodyBytes>
            message{};
        std::copy(kTelemetryProofDomain.begin(),
                  kTelemetryProofDomain.end(),
                  message.begin());
        std::copy(body.begin(), body.end(),
                  message.begin() + kTelemetryProofDomain.size());
        unsigned int tag_size = 0;
        const uint8_t *result = HMAC(EVP_sha256(),
                                     key_.data(),
                                     static_cast<int>(key_.size()),
                                     message.data(),
                                     message.size(),
                                     tag->data(),
                                     &tag_size);
        SecureClear(message.data(), message.size());
        if (result == nullptr || tag_size != tag->size())
        {
            SecureClear(tag->data(), tag->size());
            return false;
        }
        return true;
#endif
    }

    bool TelemetryProofSigner::Verify(
        const std::array<uint8_t, kTelemetryProofValueBytes> &value) const noexcept
    {
        if (!available_ || !ValidTelemetryValueHeader(value) ||
            !ConstantTimeEqual(value.data() + 60,
                               key_id_.data(),
                               key_id_.size()))
        {
            return false;
        }
        std::array<uint8_t, kTelemetryProofAuthenticatedBodyBytes> body{};
        std::copy_n(value.begin(), body.size(), body.begin());
        TelemetryProofTag expected{};
        const bool signed_ok = Sign(body, &expected);
        const bool matches = signed_ok &&
                             ConstantTimeEqual(value.data() + body.size(),
                                               expected.data(),
                                               expected.size());
        SecureClear(expected.data(), expected.size());
        return matches;
    }

    bool ValidTelemetryQueryLayout(const effect_param_t &parameter,
                                   size_t command_size) noexcept
    {
        constexpr size_t kCommandBytes =
            sizeof(effect_param_t) + kTelemetrySnapshotParameter.size();
        return parameter.psize == kTelemetrySnapshotParameter.size() &&
               command_size == kCommandBytes &&
               parameter.vsize <= kEffectParamMaxBytes - kCommandBytes;
    }

    bool IsTelemetrySnapshotParameter(const effect_param_t &parameter,
                                      size_t command_size) noexcept
    {
        return ValidTelemetryQueryLayout(parameter, command_size) &&
               std::equal(kTelemetrySnapshotParameter.begin(),
                          kTelemetrySnapshotParameter.end(),
                          reinterpret_cast<const uint8_t *>(parameter.data));
    }

    bool ValidTelemetryProofQueryLayout(const effect_param_t &parameter,
                                        size_t command_size) noexcept
    {
        constexpr size_t kCommandBytes =
            sizeof(effect_param_t) + kTelemetryProofQueryBytes;
        return parameter.psize == kTelemetryProofQueryBytes &&
               command_size == kCommandBytes &&
               parameter.vsize <= kEffectParamMaxBytes - kCommandBytes;
    }

    bool IsTelemetryProofParameter(const effect_param_t &parameter,
                                   size_t command_size) noexcept
    {
        return ValidTelemetryProofQueryLayout(parameter, command_size) &&
               std::equal(kTelemetryProofParameter.begin(),
                          kTelemetryProofParameter.end(),
                          reinterpret_cast<const uint8_t *>(parameter.data));
    }

    TelemetryProofNonce TelemetryProofQueryNonce(
        const effect_param_t &parameter,
        size_t command_size) noexcept
    {
        TelemetryProofNonce nonce{};
        if (IsTelemetryProofParameter(parameter, command_size))
        {
            std::copy_n(reinterpret_cast<const uint8_t *>(parameter.data) +
                            kTelemetryProofParameter.size(),
                        nonce.size(),
                        nonce.begin());
        }
        return nonce;
    }

    std::array<uint8_t, kTelemetrySnapshotValueBytes>
    EncodeTelemetrySnapshot(const TelemetryWireSnapshot &snapshot) noexcept
    {
        std::array<uint8_t, kTelemetrySnapshotValueBytes> encoded{};
        std::copy_n(kTelemetrySnapshotParameter.begin(), 4, encoded.begin());
        WriteUint16(encoded, 4, kTelemetrySchema);
        WriteUint16(encoded, 6, kTelemetrySnapshotParameterId);
        WriteUint16(encoded, 8,
                    static_cast<uint16_t>(kTelemetrySnapshotValueBytes));
        WriteUint16(encoded, 10, snapshot.flags);
        WriteUint32(encoded, 12, static_cast<uint32_t>(snapshot.session_id));
        WriteUint64(encoded, 16, snapshot.generation);
        WriteUint32(encoded, 24, snapshot.sequence);
        WriteUint32(encoded, 28, snapshot.blocks);
        WriteUint32(encoded, 32, snapshot.frames);
        WriteUint32(encoded, 36, snapshot.failures);
        WriteUint32(encoded, 40, snapshot.mutations);
        WriteUint32(encoded, 44, 0);
        return encoded;
    }

    bool EncodeTelemetryProof(
        const TelemetryProofWireSnapshot &snapshot,
        const TelemetryProofSigner &signer,
        std::array<uint8_t, kTelemetryProofValueBytes> *encoded) noexcept
    {
        if (encoded == nullptr || !signer.available() || snapshot.generation == 0 ||
            !NonZero(snapshot.nonce) ||
            (snapshot.flags & ~(kTelemetryEnabled | kTelemetryAuthorized |
                                kTelemetryExpired)) != 0)
        {
            return false;
        }

        encoded->fill(0);
        std::copy_n(kTelemetryProofParameter.begin(), 4, encoded->begin());
        WriteUint16(*encoded, 4, kTelemetryProofSchema);
        WriteUint16(*encoded, 6, kTelemetryProofParameterId);
        WriteUint16(*encoded, 8,
                    static_cast<uint16_t>(kTelemetryProofValueBytes));
        WriteUint16(*encoded, 10, snapshot.flags);
        WriteUint32(*encoded, 12, static_cast<uint32_t>(snapshot.session_id));
        WriteUint64(*encoded, 16, snapshot.generation);
        std::copy(snapshot.nonce.begin(), snapshot.nonce.end(),
                  encoded->begin() + 24);
        WriteUint32(*encoded, 40, snapshot.sequence);
        WriteUint32(*encoded, 44, snapshot.blocks);
        WriteUint32(*encoded, 48, snapshot.frames);
        WriteUint32(*encoded, 52, snapshot.failures);
        WriteUint32(*encoded, 56, snapshot.mutations);
        std::copy(signer.key_id().begin(), signer.key_id().end(),
                  encoded->begin() + 60);
        WriteUint32(*encoded, 76, 0);

        std::array<uint8_t, kTelemetryProofAuthenticatedBodyBytes> body{};
        std::copy_n(encoded->begin(), body.size(), body.begin());
        TelemetryProofTag tag{};
        if (!signer.Sign(body, &tag))
        {
            encoded->fill(0);
            return false;
        }
        std::copy(tag.begin(), tag.end(),
                  encoded->begin() + kTelemetryProofAuthenticatedBodyBytes);
        SecureClear(tag.data(), tag.size());
        return true;
    }

} // namespace echidna::effects::legacy
