#include <array>
#include <atomic>
#include <cstdint>
#include <cstdlib>
#include <cstring>
#include <filesystem>
#include <fstream>
#include <new>
#include <string>

#ifndef _WIN32
#include <sys/stat.h>
#include <unistd.h>
#endif

#include "effect_context.h"
#include "test_support.h"

namespace
{
    std::atomic<bool> g_track_allocations{false};
    std::atomic<size_t> g_allocation_count{0};

    void *Allocate(size_t size)
    {
        if (g_track_allocations.load(std::memory_order_relaxed))
        {
            g_allocation_count.fetch_add(1, std::memory_order_relaxed);
        }
        if (void *memory = std::malloc(size == 0 ? 1 : size))
        {
            return memory;
        }
        throw std::bad_alloc();
    }

    class ScopedProofKey
    {
    public:
        ScopedProofKey()
        {
            path_ = std::filesystem::temp_directory_path() /
                    ("echidna_realtime_proof_" +
                     std::to_string(reinterpret_cast<uintptr_t>(this)) + ".key");
            std::array<uint8_t, 32> key{};
            for (size_t index = 0; index < key.size(); ++index)
            {
                key[index] = static_cast<uint8_t>(index);
            }
            std::ofstream output(path_, std::ios::binary | std::ios::trunc);
            output.write(reinterpret_cast<const char *>(key.data()),
                         static_cast<std::streamsize>(key.size()));
            output.close();
            valid_ = static_cast<bool>(output);
#ifndef _WIN32
            valid_ = valid_ && ::chmod(path_.c_str(), 0440) == 0;
#endif
        }

        ~ScopedProofKey()
        {
            std::error_code error;
            std::filesystem::remove(path_, error);
        }

        bool valid() const noexcept { return valid_; }

        echidna::effects::legacy::TelemetryProofKeyOptions options() const
        {
            echidna::effects::legacy::TelemetryProofKeyOptions options;
            options.key_path = path_.string();
#ifndef _WIN32
            options.required_owner_uid = static_cast<uint32_t>(::geteuid());
            options.required_group_gid = static_cast<uint32_t>(::getegid());
#else
            options.required_owner_uid = 0;
            options.required_group_gid = 0;
#endif
            return options;
        }

    private:
        std::filesystem::path path_;
        bool valid_{false};
    };
} // namespace

void *operator new(size_t size)
{
    return Allocate(size);
}

void *operator new[](size_t size)
{
    return Allocate(size);
}

void operator delete(void *memory) noexcept
{
    std::free(memory);
}

void operator delete[](void *memory) noexcept
{
    std::free(memory);
}

void operator delete(void *memory, size_t) noexcept
{
    std::free(memory);
}

void operator delete[](void *memory, size_t) noexcept
{
    std::free(memory);
}

int main()
{
    using echidna::dsp::config::PresetDefinition;
    using echidna::effects::legacy::EffectContext;

    ScopedProofKey proof_key;
    CHECK_TRUE(proof_key.valid());
    EffectContext context(301, 31, {}, proof_key.options());
    CHECK_TRUE(context.Initialize() == 0);

    // Confirm this instance loaded the proof key before allocation tracking;
    // the subsequent 1,000 callback invocations must remain allocation-free.
    constexpr size_t kProofQueryBytes =
        sizeof(effect_param_t) +
        echidna::effects::legacy::kTelemetryProofQueryBytes;
    constexpr size_t kProofReplyWords =
        echidna::effects::legacy::kTelemetryProofReplyBytes / sizeof(uint32_t);
    std::array<uint32_t, kProofReplyWords> proof_storage{};
    auto *proof = reinterpret_cast<effect_param_t *>(proof_storage.data());
    proof->psize = echidna::effects::legacy::kTelemetryProofQueryBytes;
    proof->vsize = echidna::effects::legacy::kTelemetryProofValueBytes;
    std::memcpy(proof->data,
                echidna::effects::legacy::kTelemetryProofParameter.data(),
                echidna::effects::legacy::kTelemetryProofParameter.size());
    proof->data[echidna::effects::legacy::kTelemetryProofParameter.size()] = 1;
    uint32_t proof_reply_size =
        echidna::effects::legacy::kTelemetryProofReplyBytes;
    CHECK_TRUE(context.Command(EFFECT_CMD_GET_PARAM,
                               kProofQueryBytes,
                               proof,
                               &proof_reply_size,
                               proof) == 0);
#ifdef ECHIDNA_HAS_BORINGSSL
    CHECK_TRUE(proof->status ==
               echidna::effects::legacy::kTelemetryProofNoCapabilityStatus);
#else
    CHECK_TRUE(proof->status ==
               echidna::effects::legacy::kTelemetryProofKeyUnavailableStatus);
#endif
    CHECK_TRUE(context.SetConfig(MakeEffectConfig()) == 0);
    CHECK_TRUE(!context.plugin_directory_scanned());

    PresetDefinition preset;
    preset.name = "realtime-allocation-guard";
    preset.mix.params.dry_wet = 50.0f;
    preset.mix.params.output_gain_db = -6.0f;
    CHECK_TRUE(context.SetPolicyPreset(true, &preset) == 0);
    CHECK_TRUE(context.Enable() == 0);

    std::array<float, 256> input{};
    std::array<float, 256> output{};
    for (size_t index = 0; index < input.size(); ++index)
    {
        input[index] = static_cast<float>((index % 31) - 15) / 64.0f;
    }
    audio_buffer_t in{};
    in.frameCount = input.size();
    in.f32 = input.data();
    audio_buffer_t out{};
    out.frameCount = output.size();
    out.f32 = output.data();

    CHECK_TRUE(context.Process(&in, &out) == 0);
    g_allocation_count.store(0, std::memory_order_relaxed);
    g_track_allocations.store(true, std::memory_order_release);
    for (size_t iteration = 0; iteration < 1000; ++iteration)
    {
        if (context.Process(&in, &out) != 0)
        {
            g_track_allocations.store(false, std::memory_order_release);
            return 1;
        }
    }
    g_track_allocations.store(false, std::memory_order_release);

    CHECK_TRUE(g_allocation_count.load(std::memory_order_relaxed) == 0);
    const auto telemetry = context.telemetry();
    CHECK_TRUE(telemetry.processed_calls == 1001);
    CHECK_TRUE(telemetry.dsp_failures == 0);
    CHECK_TRUE(telemetry.mutations == 1001);
    return 0;
}
