#pragma once

#include <atomic>
#include <cstdint>

namespace echidna::hooks
{
    enum class AAudioHookRole : uint8_t
    {
        kClose,
        kDeleteBuilder,
        kSetDataCallback,
        kRead,
        kOpen,
    };

    struct AAudioHookAvailability
    {
        bool open{false};
        bool close{false};
        bool delete_builder{false};
        bool set_data_callback{false};
        bool read{false};
    };

    struct AAudioHookInstallation
    {
        bool open{false};
        bool close{false};
        bool delete_builder{false};
        bool set_data_callback{false};
        bool read{false};

        [[nodiscard]] bool readRouteComplete() const
        {
            return open && close && read;
        }

        [[nodiscard]] bool callbackRouteComplete() const
        {
            return open && close && delete_builder && set_data_callback;
        }

        [[nodiscard]] bool anyRouteComplete() const
        {
            return readRouteComplete() || callbackRouteComplete();
        }
    };

    /**
     * Installs irreversible support hooks before the open hook. The open hook
     * is never attempted unless at least one transform route has all of its
     * lifecycle support installed.
     */
    template <typename Attempt>
    AAudioHookInstallation InstallAAudioHookSet(const AAudioHookAvailability &available,
                                                Attempt &&attempt)
    {
        AAudioHookInstallation installed;
        const bool callback_available =
            available.delete_builder && available.set_data_callback;
        if (!available.open || !available.close ||
            (!available.read && !callback_available))
        {
            return installed;
        }

        installed.close = attempt(AAudioHookRole::kClose);
        if (!installed.close)
        {
            return installed;
        }

        if (callback_available)
        {
            installed.delete_builder = attempt(AAudioHookRole::kDeleteBuilder);
            if (installed.delete_builder)
            {
                installed.set_data_callback =
                    attempt(AAudioHookRole::kSetDataCallback);
            }
        }
        if (available.read)
        {
            installed.read = attempt(AAudioHookRole::kRead);
        }

        const bool read_support = installed.close && installed.read;
        const bool callback_support = installed.close && installed.delete_builder &&
                                      installed.set_data_callback;
        if (!read_support && !callback_support)
        {
            return installed;
        }

        // Open is the activation boundary and must always be patched last.
        installed.open = attempt(AAudioHookRole::kOpen);
        return installed;
    }

    class AAudioHookReadiness
    {
    public:
        static constexpr uint32_t kReadRoute = 1U << 0U;
        static constexpr uint32_t kCallbackRoute = 1U << 1U;

        void clear();
        void publish(const AAudioHookInstallation &installed);

        [[nodiscard]] uint32_t snapshot() const;
        [[nodiscard]] bool readReady() const;
        [[nodiscard]] bool callbackReady() const;
        [[nodiscard]] bool lifecycleReady() const;

    private:
        std::atomic<uint32_t> ready_routes_{0};
    };
} // namespace echidna::hooks
