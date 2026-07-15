#include <cerrno>
#include <memory>
#include <optional>
#include <vector>

#include <aidl/android/hardware/audio/effect/DefaultExtension.h>
#include <aidl/android/hardware/audio/effect/IEffect.h>
#include <aidl/android/hardware/audio/effect/VendorExtension.h>

#include "effect-impl/EffectImpl.h"
#include "legacy_runtime.h"

namespace aidl::android::hardware::audio::effect
{
    namespace
    {
        ndk::ScopedAStatus RuntimeError(int32_t status, const char *operation)
        {
            const int32_t exception =
                status == -EPERM || status == -EACCES || status == -ENOKEY ||
                        status == -EKEYREJECTED || status == -EKEYEXPIRED
                    ? EX_SECURITY
                : status == -EBUSY || status == -ENODATA
                    ? EX_ILLEGAL_STATE
                    : EX_ILLEGAL_ARGUMENT;
            return ndk::ScopedAStatus::fromExceptionCodeWithMessage(
                exception, operation);
        }
    } // namespace

    class EchidnaContext final : public EffectContext
    {
    public:
        explicit EchidnaContext(const Parameter::Common &common)
            : EffectContext(1, common), runtime_(common)
        {
        }

        [[nodiscard]] bool ready() const noexcept
        {
            return runtime_.ready();
        }

        RetCode setCommon(const Parameter::Common &common) override
        {
            if (runtime_.Reconfigure(common) != 0)
            {
                return RetCode::ERROR_ILLEGAL_PARAMETER;
            }
            return EffectContext::setCommon(common);
        }

        ::echidna::effects::aidl::LegacyRuntime &runtime() noexcept
        {
            return runtime_;
        }

    private:
        ::echidna::effects::aidl::LegacyRuntime runtime_;
    };

    class EchidnaEffect final : public EffectImpl
    {
    public:
        ~EchidnaEffect() override { cleanUp(); }

        ndk::ScopedAStatus getDescriptor(Descriptor *descriptor) override
        {
            if (descriptor == nullptr)
            {
                return ndk::ScopedAStatus::fromExceptionCode(EX_NULL_POINTER);
            }
            *descriptor = ::echidna::effects::aidl::Descriptor();
            return ndk::ScopedAStatus::ok();
        }

        ndk::ScopedAStatus setParameterSpecific(
            const Parameter::Specific &specific) override
        {
            if (specific.getTag() != Parameter::Specific::vendorEffect ||
                !context_)
            {
                return ndk::ScopedAStatus::fromExceptionCode(
                    EX_ILLEGAL_ARGUMENT);
            }
            const auto &vendor =
                specific.get<Parameter::Specific::vendorEffect>();
            std::optional<DefaultExtension> extension;
            if (vendor.extension.getParcelable(&extension) != STATUS_OK ||
                !extension.has_value())
            {
                return ndk::ScopedAStatus::fromExceptionCode(
                    EX_ILLEGAL_ARGUMENT);
            }
            const int32_t status =
                context_->runtime().SetValueV1(extension->bytes);
            return status == 0 ? ndk::ScopedAStatus::ok()
                               : RuntimeError(status, "setVendorParameter");
        }

        ndk::ScopedAStatus getParameterSpecific(
            const Parameter::Id &id,
            Parameter::Specific *specific) override
        {
            if (specific == nullptr ||
                id.getTag() != Parameter::Id::vendorEffectTag || !context_)
            {
                return ndk::ScopedAStatus::fromExceptionCode(
                    EX_ILLEGAL_ARGUMENT);
            }
            const auto &vendor_id =
                id.get<Parameter::Id::vendorEffectTag>();
            std::optional<DefaultExtension> id_extension;
            if (vendor_id.extension.getParcelable(&id_extension) != STATUS_OK ||
                !id_extension.has_value())
            {
                return ndk::ScopedAStatus::fromExceptionCode(
                    EX_ILLEGAL_ARGUMENT);
            }

            DefaultExtension value_extension;
            const int32_t status = context_->runtime().GetValueV1(
                id_extension->bytes, &value_extension.bytes);
            if (status != 0)
            {
                return RuntimeError(status, "getVendorParameter");
            }
            VendorExtension vendor;
            if (vendor.extension.setParcelable(value_extension) != STATUS_OK)
            {
                return ndk::ScopedAStatus::fromExceptionCode(
                    EX_ILLEGAL_ARGUMENT);
            }
            specific->set<Parameter::Specific::vendorEffect>(vendor);
            return ndk::ScopedAStatus::ok();
        }

        std::shared_ptr<EffectContext> createContext(
            const Parameter::Common &common) override
        {
            if (context_)
            {
                return context_;
            }
            auto candidate = std::make_shared<EchidnaContext>(common);
            if (!candidate->ready())
            {
                return nullptr;
            }
            context_ = std::move(candidate);
            return context_;
        }

        std::shared_ptr<EffectContext> getContext() override
        {
            return context_;
        }

        RetCode releaseContext() override
        {
            context_.reset();
            return RetCode::SUCCESS;
        }

        std::string getEffectName() override
        {
            return ::echidna::effects::aidl::kEffectName;
        }

        IEffect::Status effectProcessImpl(float *input,
                                          float *output,
                                          int samples) override
        {
            if (!context_ ||
                !context_->runtime().Process(input, output, samples))
            {
                return {EX_ILLEGAL_ARGUMENT, 0, 0};
            }
            return {STATUS_OK, samples, samples};
        }

    protected:
        ndk::ScopedAStatus commandImpl(CommandId command) override
        {
            if (!context_)
            {
                return ndk::ScopedAStatus::fromExceptionCode(
                    EX_ILLEGAL_STATE);
            }
            int32_t status = 0;
            switch (command)
            {
            case CommandId::START:
                status = context_->runtime().Start();
                break;
            case CommandId::STOP:
                status = context_->runtime().Stop();
                break;
            case CommandId::RESET:
                status = context_->runtime().Stop();
                if (status == 0)
                {
                    status = context_->runtime().Reset();
                }
                break;
            default:
                status = -ENOSYS;
                break;
            }
            if (status != 0)
            {
                return RuntimeError(status, "effectCommand");
            }
            return EffectImpl::commandImpl(command);
        }

    private:
        std::shared_ptr<EchidnaContext> context_;
    };

} // namespace aidl::android::hardware::audio::effect

using ::aidl::android::hardware::audio::effect::Descriptor;
using ::aidl::android::hardware::audio::effect::IEffect;
using ::aidl::android::media::audio::common::AudioUuid;

extern "C" binder_exception_t createEffect(
    const AudioUuid *implementation_uuid,
    std::shared_ptr<IEffect> *instance)
{
    if (implementation_uuid == nullptr || instance == nullptr ||
        *implementation_uuid !=
            ::echidna::effects::aidl::ImplementationUuid())
    {
        return EX_ILLEGAL_ARGUMENT;
    }
    *instance = ndk::SharedRefBase::make<
        ::aidl::android::hardware::audio::effect::EchidnaEffect>();
    return EX_NONE;
}

extern "C" binder_exception_t queryEffect(
    const AudioUuid *implementation_uuid,
    Descriptor *descriptor)
{
    if (implementation_uuid == nullptr || descriptor == nullptr ||
        *implementation_uuid !=
            ::echidna::effects::aidl::ImplementationUuid())
    {
        return EX_ILLEGAL_ARGUMENT;
    }
    *descriptor = ::echidna::effects::aidl::Descriptor();
    return EX_NONE;
}
