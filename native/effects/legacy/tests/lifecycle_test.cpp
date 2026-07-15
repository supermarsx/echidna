#include <array>
#include <cerrno>
#include <cstdint>
#include <cstring>

#include "effect_library.h"
#include "test_support.h"

int main()
{
    effect_handle_t handle = nullptr;
    effect_uuid_t unknown{};
    CHECK_TRUE(AELI.create_effect(&unknown, 41, 7, &handle) == -ENOENT);
    CHECK_TRUE(handle == nullptr);
    CHECK_TRUE(AELI.create_effect(nullptr, 41, 7, &handle) == -EINVAL);
    CHECK_TRUE(AELI.create_effect(
                   &echidna::effects::legacy::kEffectImplementationUuid,
                   41,
                   7,
                   &handle) == 0);
    CHECK_TRUE(handle != nullptr && *handle != nullptr);

    effect_descriptor_t descriptor{};
    CHECK_TRUE((*handle)->get_descriptor(handle, &descriptor) == 0);
    CHECK_TRUE((*handle)->get_descriptor(handle, nullptr) == -EINVAL);

    int32_t status = 0;
    CHECK_TRUE(SendStatusCommand(handle, EFFECT_CMD_ENABLE, 0, nullptr, &status) == 0);
    CHECK_TRUE(status == -ENODATA);
    CHECK_TRUE((*handle)->command(handle,
                                  EFFECT_CMD_INIT,
                                  1,
                                  nullptr,
                                  nullptr,
                                  nullptr) == -EINVAL);
    CHECK_TRUE(SendStatusCommand(handle, EFFECT_CMD_INIT, 0, nullptr, &status) == 0);
    CHECK_TRUE(status == 0);

    auto config = MakeEffectConfig();
    CHECK_TRUE(SendStatusCommand(handle,
                                 EFFECT_CMD_SET_CONFIG,
                                 sizeof(config) - 1,
                                 &config,
                                 &status) == -EINVAL);
    CHECK_TRUE(SendStatusCommand(handle,
                                 EFFECT_CMD_SET_CONFIG,
                                 sizeof(config),
                                 &config,
                                 &status) == 0);
    CHECK_TRUE(status == 0);

    effect_config_t returned{};
    uint32_t returned_size = sizeof(returned);
    CHECK_TRUE((*handle)->command(handle,
                                  EFFECT_CMD_GET_CONFIG,
                                  0,
                                  nullptr,
                                  &returned_size,
                                  &returned) == 0);
    CHECK_TRUE(returned_size == sizeof(returned));
    CHECK_TRUE(std::memcmp(&returned, &config, sizeof(config)) == 0);

    CHECK_TRUE(SendStatusCommand(handle, EFFECT_CMD_ENABLE, 0, nullptr, &status) == 0);
    CHECK_TRUE(status == 0);
    CHECK_TRUE(SendStatusCommand(handle,
                                 EFFECT_CMD_SET_CONFIG,
                                 sizeof(config),
                                 &config,
                                 &status) == 0);
    CHECK_TRUE(status == -EBUSY);
    uint32_t reset_reply = 99;
    CHECK_TRUE((*handle)->command(handle,
                                  EFFECT_CMD_RESET,
                                  0,
                                  nullptr,
                                  &reset_reply,
                                  nullptr) == 0);
    CHECK_TRUE(reset_reply == 0);
    CHECK_TRUE(SendStatusCommand(handle, EFFECT_CMD_DISABLE, 0, nullptr, &status) == 0);
    CHECK_TRUE(status == 0);

    std::array<float, 4> input{0.1f, -0.2f, 0.3f, -0.4f};
    std::array<float, 4> output{};
    audio_buffer_t input_buffer{input.size(), {.f32 = input.data()}};
    audio_buffer_t output_buffer{output.size(), {.f32 = output.data()}};
    CHECK_TRUE((*handle)->process(handle, &input_buffer, &output_buffer) == -ENODATA);
    CHECK_TRUE(input == output);

    CHECK_TRUE((*handle)->command(handle,
                                  EFFECT_CMD_RESET,
                                  1,
                                  nullptr,
                                  nullptr,
                                  nullptr) == -EINVAL);
    CHECK_TRUE((*handle)->command(handle,
                                  EFFECT_CMD_FIRST_PROPRIETARY,
                                  0,
                                  nullptr,
                                  nullptr,
                                  nullptr) == -ENOSYS);
    CHECK_TRUE(AELI.release_effect(handle) == 0);
    CHECK_TRUE(AELI.release_effect(nullptr) == -EINVAL);
    return 0;
}
