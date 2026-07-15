#include "effect_library.h"

#include <cerrno>
#include <cstddef>
#include <cstdint>
#include <cstring>
#include <new>

#include "effect_context.h"

namespace echidna::effects::legacy
{
    namespace
    {
        constexpr uint32_t kModuleMagic = 0x45434844U; // "ECHD"

        constexpr effect_descriptor_t kDescriptor = {
            kEffectTypeUuid,
            kEffectImplementationUuid,
            EFFECT_CONTROL_API_VERSION,
            EFFECT_FLAG_TYPE_PRE_PROC | EFFECT_FLAG_INSERT_FIRST,
            100,
            512,
            "Echidna Input Preprocessor",
            "Echidna",
        };

        struct EffectModule
        {
            const effect_interface_s *interface;
            EffectContext *context;
            uint32_t magic;
        };

        static_assert(offsetof(EffectModule, interface) == 0);

        bool UuidEquals(const effect_uuid_t *left,
                        const effect_uuid_t &right) noexcept
        {
            return left != nullptr &&
                   std::memcmp(left, &right, sizeof(effect_uuid_t)) == 0;
        }

        EffectModule *ModuleFromHandle(effect_handle_t handle) noexcept;

        int32_t Process(effect_handle_t self,
                        audio_buffer_t *input,
                        audio_buffer_t *output)
        {
            auto *module = ModuleFromHandle(self);
            return module == nullptr ? -EINVAL : module->context->Process(input, output);
        }

        int32_t Command(effect_handle_t self,
                        uint32_t command_code,
                        uint32_t command_size,
                        void *command_data,
                        uint32_t *reply_size,
                        void *reply_data)
        {
            auto *module = ModuleFromHandle(self);
            return module == nullptr
                       ? -EINVAL
                       : module->context->Command(command_code,
                                                  command_size,
                                                  command_data,
                                                  reply_size,
                                                  reply_data);
        }

        int32_t GetInstanceDescriptor(effect_handle_t self,
                                      effect_descriptor_t *descriptor)
        {
            if (ModuleFromHandle(self) == nullptr || descriptor == nullptr)
            {
                return -EINVAL;
            }
            *descriptor = kDescriptor;
            return 0;
        }

        constexpr effect_interface_s kInterface = {
            Process,
            Command,
            GetInstanceDescriptor,
            nullptr,
        };

        EffectModule *ModuleFromHandle(effect_handle_t handle) noexcept
        {
            if (handle == nullptr)
            {
                return nullptr;
            }
            auto *module = reinterpret_cast<EffectModule *>(handle);
            if (module->magic != kModuleMagic || module->interface != &kInterface ||
                module->context == nullptr)
            {
                return nullptr;
            }
            return module;
        }

        int32_t CreateEffect(const effect_uuid_t *uuid,
                             int32_t session_id,
                             int32_t io_id,
                             effect_handle_t *handle)
        {
            if (handle == nullptr || uuid == nullptr)
            {
                return -EINVAL;
            }
            *handle = nullptr;
            if (!UuidEquals(uuid, kEffectImplementationUuid))
            {
                return -ENOENT;
            }
            auto *module = new (std::nothrow) EffectModule{};
            if (module == nullptr)
            {
                return -ENOMEM;
            }
            module->context = new (std::nothrow) EffectContext(session_id, io_id);
            if (module->context == nullptr)
            {
                delete module;
                return -ENOMEM;
            }
            module->interface = &kInterface;
            module->magic = kModuleMagic;
            *handle = reinterpret_cast<effect_handle_t>(module);
            return 0;
        }

        int32_t ReleaseEffect(effect_handle_t handle)
        {
            auto *module = ModuleFromHandle(handle);
            if (module == nullptr)
            {
                return -EINVAL;
            }
            module->magic = 0;
            delete module->context;
            module->context = nullptr;
            delete module;
            return 0;
        }

        int32_t GetDescriptor(const effect_uuid_t *uuid,
                              effect_descriptor_t *descriptor)
        {
            if (uuid == nullptr || descriptor == nullptr)
            {
                return -EINVAL;
            }
            if (!UuidEquals(uuid, kEffectImplementationUuid))
            {
                return -ENOENT;
            }
            *descriptor = kDescriptor;
            return 0;
        }

    } // namespace
} // namespace echidna::effects::legacy

extern "C"
{
    ECHIDNA_PREPROC_EXPORT audio_effect_library_t AUDIO_EFFECT_LIBRARY_INFO_SYM = {
        AUDIO_EFFECT_LIBRARY_TAG,
        EFFECT_LIBRARY_API_VERSION_3_0,
        "Echidna Legacy Input Preprocessor",
        "Echidna",
        echidna::effects::legacy::CreateEffect,
        echidna::effects::legacy::ReleaseEffect,
        echidna::effects::legacy::GetDescriptor,
        nullptr,
    };
}
