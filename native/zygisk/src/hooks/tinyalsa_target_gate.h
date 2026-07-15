#pragma once

#include <cstdint>
#include <string_view>

namespace echidna::hooks
{
    inline bool IsTinyAlsaExecutableMapping(std::string_view path,
                                            std::string_view permissions)
    {
        const size_t slash = path.find_last_of("/\\");
        const std::string_view base =
            slash == std::string_view::npos ? path : path.substr(slash + 1);
        return base == "libtinyalsa.so" &&
               permissions.find('x') != std::string_view::npos;
    }

    struct TinyAlsaSymbolEvidence
    {
        bool open{false};
        bool close{false};
        bool is_ready{false};
        bool format_to_bits{false};
        bool read{false};
        bool readi{false};
        bool mmap_read{false};
    };

    inline bool IsCompatibleTinyAlsaTarget(
        bool library_mapped,
        const TinyAlsaSymbolEvidence &symbols)
    {
        return library_mapped && symbols.open && symbols.close && symbols.is_ready &&
               symbols.format_to_bits &&
               (symbols.read || symbols.readi || symbols.mmap_read);
    }

    enum class TinyAlsaHookRole : uint8_t
    {
        kClose,
        kRead,
        kReadi,
        kMmapRead,
        kOpen,
    };

    struct TinyAlsaHookInstallation
    {
        bool close{false};
        bool read{false};
        bool readi{false};
        bool mmap_read{false};
        bool open{false};

        [[nodiscard]] bool complete() const
        {
            return open && close && (read || readi || mmap_read);
        }
    };

    template <typename Attempt>
    TinyAlsaHookInstallation InstallTinyAlsaHookSet(
        const TinyAlsaSymbolEvidence &available,
        Attempt &&attempt)
    {
        TinyAlsaHookInstallation installed;
        if (!available.open || !available.close ||
            (!available.read && !available.readi && !available.mmap_read))
        {
            return installed;
        }
        installed.close = attempt(TinyAlsaHookRole::kClose);
        if (!installed.close)
        {
            return installed;
        }
        if (available.read)
        {
            installed.read = attempt(TinyAlsaHookRole::kRead);
        }
        if (available.readi)
        {
            installed.readi = attempt(TinyAlsaHookRole::kReadi);
        }
        if (available.mmap_read)
        {
            installed.mmap_read = attempt(TinyAlsaHookRole::kMmapRead);
        }
        if (!installed.read && !installed.readi && !installed.mmap_read)
        {
            return installed;
        }
        installed.open = attempt(TinyAlsaHookRole::kOpen);
        return installed;
    }
} // namespace echidna::hooks
