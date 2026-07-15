#include <array>
#include <atomic>
#include <cstdlib>
#include <new>

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

    EffectContext context(301, 31);
    CHECK_TRUE(context.Initialize() == 0);
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
    return 0;
}
