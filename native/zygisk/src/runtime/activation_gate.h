#pragma once

namespace echidna::runtime
{
    /**
     * Small process-local state machine used by the Zygisk lifecycle worker.
     * Synchronisation is owned by the caller so the transition rules remain
     * directly host-testable.
     */
    class ActivationGate
    {
    public:
        void start()
        {
            running_ = true;
        }

        void stop()
        {
            running_ = false;
            admitted_ = false;
        }

        void updatePolicy(bool admitted)
        {
            if (running_)
            {
                admitted_ = admitted;
            }
        }

        void markHooksInstalled()
        {
            if (running_)
            {
                hooks_installed_ = true;
            }
        }

        [[nodiscard]] bool running() const { return running_; }
        [[nodiscard]] bool admitted() const { return running_ && admitted_; }
        [[nodiscard]] bool hooksInstalled() const { return hooks_installed_; }

        [[nodiscard]] bool shouldAttemptInstall() const
        {
            return running_ && admitted_ && !hooks_installed_;
        }

        [[nodiscard]] bool processingActive() const
        {
            return running_ && admitted_ && hooks_installed_;
        }

    private:
        bool running_{false};
        bool admitted_{false};
        bool hooks_installed_{false};
    };

} // namespace echidna::runtime
