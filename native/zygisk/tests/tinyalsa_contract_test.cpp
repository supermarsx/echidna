#include "hooks/tinyalsa_contract.h"
#include "hooks/tinyalsa_target_gate.h"

#include <cstdio>
#include <limits>
#include <vector>

namespace
{
    int gFailures = 0;
    void Check(bool condition, const char *message)
    {
        if (!condition)
        {
            std::fprintf(stderr, "FAIL: %s\n", message);
            ++gFailures;
        }
    }
} // namespace

int main()
{
    using namespace echidna::hooks;
    TinyAlsaConfigPrefix config{2, 48000, 240, 4, kTinyAlsaFormatS16Le};
    const auto pcm16 = ParseTinyAlsaContract(kTinyAlsaPcmIn, &config, 16);
    Check(pcm16 && pcm16->stream.format == ECHIDNA_PCM_FORMAT_SIGNED_16 &&
              pcm16->stream.max_frames == 960 &&
              pcm16->bytes_per_frame == 4 &&
              TinyAlsaBytesForFrames(*pcm16, 10) == 40,
          "PCM16 contract retains exact ring geometry");

    config.format = kTinyAlsaFormatFloatLe;
    const auto float32 = ParseTinyAlsaContract(kTinyAlsaPcmIn, &config, 32);
    Check(float32 && float32->stream.format == ECHIDNA_PCM_FORMAT_FLOAT_32 &&
              float32->bytes_per_frame == 8 &&
              TinyAlsaBytesForFrames(*float32, 10) == 80,
          "FLOAT_LE contract is exact");
    Check(!ParseTinyAlsaContract(kTinyAlsaPcmIn, &config, 24),
          "reported float storage mismatch fails closed");

    for (int32_t unsupported : {kTinyAlsaFormatS32Le,
                                kTinyAlsaFormatS24Le,
                                kTinyAlsaFormatS24PackedLe,
                                99})
    {
        config.format = unsupported;
        Check(!ParseTinyAlsaContract(kTinyAlsaPcmIn, &config, 32),
              "S24/S32/unknown formats fail closed");
    }

    config = {2, 48000, 240, 4, kTinyAlsaFormatS16Le};
    Check(!ParseTinyAlsaContract(0, &config, 16) &&
              !ParseTinyAlsaContract(kTinyAlsaPcmIn, nullptr, 16),
          "output and null configs fail closed");
    config.period_size = 0;
    Check(!ParseTinyAlsaContract(kTinyAlsaPcmIn, &config, 16),
          "zero period fails closed");
    config.period_size = std::numeric_limits<uint32_t>::max();
    config.period_count = 2;
    Check(!ParseTinyAlsaContract(kTinyAlsaPcmIn, &config, 16),
          "ring multiplication overflow fails closed");
    config = {8, 48000, 4096, 2, kTinyAlsaFormatS16Le};
    Check(!ParseTinyAlsaContract(kTinyAlsaPcmIn, &config, 16),
          "oversized interleaved ring fails closed");

    Check(TinyAlsaCompletedByteRead(0, 64) == 64 &&
              !TinyAlsaCompletedByteRead(-5, 64) &&
              !TinyAlsaCompletedByteRead(1, 64),
          "deprecated byte reads process only a complete zero-status transfer");
    Check(TinyAlsaCompletedFrameRead(2, 4) == 2 &&
              !TinyAlsaCompletedFrameRead(0, 4) &&
              !TinyAlsaCompletedFrameRead(-5, 4) &&
              !TinyAlsaCompletedFrameRead(5, 4),
          "frame reads accept short success and reject zero/error/overflow");

    Check(IsTinyAlsaExecutableMapping("/vendor/lib64/libtinyalsa.so", "r-xp") &&
              !IsTinyAlsaExecutableMapping("/vendor/lib64/libtinyalsa.so", "r--p") &&
              !IsTinyAlsaExecutableMapping("/vendor/lib64/libtinyalsa_vendor.so", "r-xp"),
          "only exact executable target mappings qualify");
    TinyAlsaSymbolEvidence symbols{true, true, true, true, true, false, false};
    Check(IsCompatibleTinyAlsaTarget(true, symbols) &&
              !IsCompatibleTinyAlsaTarget(false, symbols),
          "mapped target and complete ABI evidence are both required");
    symbols.format_to_bits = false;
    Check(!IsCompatibleTinyAlsaTarget(true, symbols),
          "format ABI evidence is mandatory");

    symbols = {true, true, true, true, true, true, true};
    for (uint32_t outcomes = 0; outcomes < 32; ++outcomes)
    {
        std::vector<TinyAlsaHookRole> order;
        const auto installed = InstallTinyAlsaHookSet(
            symbols,
            [&](TinyAlsaHookRole role)
            {
                order.push_back(role);
                return (outcomes & (1U << static_cast<uint32_t>(role))) != 0;
            });
        if (installed.complete())
        {
            Check(!order.empty() && order.back() == TinyAlsaHookRole::kOpen,
                  "open hook is installed last");
        }
        for (size_t index = 0; index + 1 < order.size(); ++index)
        {
            Check(order[index] != TinyAlsaHookRole::kOpen,
                  "open is never published before support hooks");
        }
    }

    if (gFailures != 0)
    {
        return 1;
    }
    std::fprintf(stderr, "tinyalsa_contract_test: all checks passed\n");
    return 0;
}
