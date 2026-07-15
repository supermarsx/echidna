#include "state/shared_state.h"

/**
 * @file shared_state.cpp
 * @brief Implementation of the in-process SharedState singleton that keeps
 * a cached copy of configuration and telemetry helpers.
 */

#include <algorithm>
#include <string_view>

namespace echidna
{
    namespace state
    {
        namespace
        {
            bool IsWhitelisted(const utils::ConfigurationSnapshot &snapshot,
                               std::string_view process)
            {
                const auto is_allowed = [&snapshot](std::string_view candidate)
                {
                    return std::any_of(snapshot.process_whitelist.begin(),
                                       snapshot.process_whitelist.end(),
                                       [candidate](const std::string &entry)
                                       {
                                           return std::string_view(entry) == candidate;
                                       });
                };
                if (is_allowed(process))
                {
                    return true;
                }
                const size_t suffix = process.find(':');
                return suffix != std::string_view::npos &&
                       is_allowed(process.substr(0, suffix));
            }
        } // namespace

        SharedState &SharedState::instance()
        {
            static SharedState instance;
            return instance;
        }

        SharedState::SharedState()
            : status_(InternalStatus::kDisabled),
              profile_("default"),
              bypass_enabled_(false),
              bypass_until_ns_(0)
        {
            refreshFromSharedMemory();
        }

        int SharedState::status() const
        {
            return static_cast<int>(status_.load(std::memory_order_acquire));
        }

        void SharedState::setStatus(InternalStatus status)
        {
            status_.store(status, std::memory_order_release);
        }

        std::string SharedState::profile() const
        {
            std::scoped_lock lock(mutex_);
            return profile_;
        }

        void SharedState::setProfile(const std::string &profile)
        {
            {
                std::scoped_lock lock(mutex_);
                profile_ = profile;
            }
            shared_memory_.updateProfile(profile);
        }

        bool SharedState::isProcessWhitelisted(const std::string &process) const
        {
            std::scoped_lock lock(mutex_);
            return IsWhitelisted(cached_snapshot_, process);
        }

        void SharedState::prepareProcessAdmission(const std::string &process)
        {
            std::scoped_lock lock(mutex_);
            current_process_ = process;
            audio_processing_allowed_.store(
                cached_snapshot_.hooks_enabled && IsWhitelisted(cached_snapshot_, process),
                std::memory_order_release);
        }

        bool SharedState::audioProcessingAllowed() const
        {
            return audio_processing_allowed_.load(std::memory_order_acquire);
        }

        bool SharedState::hooksEnabled() const
        {
            return hooks_enabled_.load(std::memory_order_acquire);
        }

        void SharedState::setBypass(bool enabled)
        {
            bypass_until_ns_.store(0, std::memory_order_relaxed);
            bypass_enabled_.store(enabled, std::memory_order_release);
        }

        void SharedState::setBypassUntil(uint64_t until_ns)
        {
            bypass_until_ns_.store(until_ns, std::memory_order_relaxed);
            bypass_enabled_.store(true, std::memory_order_release);
        }

        bool SharedState::isBypassed(uint64_t now_ns)
        {
            if (!bypass_enabled_.load(std::memory_order_acquire))
            {
                return false;
            }
            const uint64_t until_ns = bypass_until_ns_.load(std::memory_order_relaxed);
            if (until_ns == 0)
            {
                return true;
            }
            if (now_ns >= until_ns)
            {
                bool expected = true;
                if (bypass_enabled_.compare_exchange_strong(expected,
                                                            false,
                                                            std::memory_order_acq_rel,
                                                            std::memory_order_relaxed))
                {
                    bypass_until_ns_.store(0, std::memory_order_relaxed);
                }
                return false;
            }
            return true;
        }

        void SharedState::updateConfiguration(const utils::ConfigurationSnapshot &snapshot)
        {
            std::scoped_lock lock(mutex_);
            cached_snapshot_ = snapshot;
            hooks_enabled_.store(snapshot.hooks_enabled, std::memory_order_release);
            audio_processing_allowed_.store(
                snapshot.hooks_enabled && !current_process_.empty() &&
                    IsWhitelisted(snapshot, current_process_),
                std::memory_order_release);
            if (!snapshot.profile.empty())
            {
                profile_ = snapshot.profile;
            }
        }

        void SharedState::refreshFromSharedMemory()
        {
            updateConfiguration(shared_memory_.snapshot());
        }

        utils::TelemetryAccumulator &SharedState::telemetry()
        {
            return telemetry_accumulator_;
        }

        const utils::TelemetryAccumulator &SharedState::telemetry() const
        {
            return telemetry_accumulator_;
        }

    } // namespace state
} // namespace echidna
