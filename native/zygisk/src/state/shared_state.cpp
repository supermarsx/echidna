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

        SharedState &SharedState::instance()
        {
            static SharedState instance;
            return instance;
        }

        SharedState::SharedState()
            : status_(InternalStatus::kDisabled),
              profile_("default")
        {
            refreshFromSharedMemory();
        }

        int SharedState::status() const
        {
            std::scoped_lock lock(mutex_);
            return static_cast<int>(status_);
        }

        void SharedState::setStatus(InternalStatus status)
        {
            std::scoped_lock lock(mutex_);
            status_ = status;
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
            return std::find(cached_snapshot_.process_whitelist.begin(),
                             cached_snapshot_.process_whitelist.end(),
                             process) != cached_snapshot_.process_whitelist.end();
        }

        bool SharedState::hooksEnabled() const
        {
            std::scoped_lock lock(mutex_);
            return cached_snapshot_.hooks_enabled;
        }

        void SharedState::updateConfiguration(const utils::ConfigurationSnapshot &snapshot)
        {
            std::scoped_lock lock(mutex_);
            cached_snapshot_ = snapshot;
            if (!snapshot.profile.empty())
            {
                profile_ = snapshot.profile;
            }
        }

        void SharedState::refreshFromSharedMemory()
        {
            updateConfiguration(shared_memory_.snapshot());
        }

        utils::TelemetrySharedMemory &SharedState::telemetry()
        {
            return telemetry_memory_;
        }

        const utils::TelemetrySharedMemory &SharedState::telemetry() const
        {
            return telemetry_memory_;
        }

    } // namespace state
} // namespace echidna
