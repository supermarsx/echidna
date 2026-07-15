#include <cerrno>
#include <cstring>
#include <string_view>

#include "effect_library.h"
#include "test_support.h"

int main()
{
    CHECK_TRUE(AELI.tag == AUDIO_EFFECT_LIBRARY_TAG);
    CHECK_TRUE(AELI.version == EFFECT_LIBRARY_API_VERSION_3_0);
    CHECK_TRUE(AELI.create_effect != nullptr);
    CHECK_TRUE(AELI.release_effect != nullptr);
    CHECK_TRUE(AELI.get_descriptor != nullptr);
    CHECK_TRUE(AELI.create_effect_3_1 == nullptr);
    CHECK_TRUE(std::string_view(AELI.name).find("Echidna") != std::string_view::npos);

    effect_descriptor_t descriptor{};
    CHECK_TRUE(AELI.get_descriptor(
                   &echidna::effects::legacy::kEffectImplementationUuid,
                   &descriptor) == 0);
    CHECK_TRUE(std::memcmp(&descriptor.type,
                           &echidna::effects::legacy::kEffectTypeUuid,
                           sizeof(effect_uuid_t)) == 0);
    CHECK_TRUE(std::memcmp(&descriptor.uuid,
                           &echidna::effects::legacy::kEffectImplementationUuid,
                           sizeof(effect_uuid_t)) == 0);
    CHECK_TRUE(descriptor.apiVersion == EFFECT_CONTROL_API_VERSION);
    CHECK_TRUE((descriptor.flags & EFFECT_FLAG_TYPE_MASK) ==
               EFFECT_FLAG_TYPE_PRE_PROC);
    CHECK_TRUE((descriptor.flags & EFFECT_FLAG_INSERT_FIRST) != 0);
    CHECK_TRUE(std::string_view(descriptor.name).find("Echidna") !=
               std::string_view::npos);

    effect_uuid_t unknown{};
    CHECK_TRUE(AELI.get_descriptor(&unknown, &descriptor) == -ENOENT);
    CHECK_TRUE(AELI.get_descriptor(nullptr, &descriptor) == -EINVAL);
    CHECK_TRUE(AELI.get_descriptor(
                   &echidna::effects::legacy::kEffectImplementationUuid,
                   nullptr) == -EINVAL);
    return 0;
}
