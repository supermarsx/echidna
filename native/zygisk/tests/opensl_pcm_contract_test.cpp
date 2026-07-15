#include "hooks/opensl_pcm_contract.h"

#include <cstdio>

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
    using echidna::audio::PcmFormat;
    using namespace echidna::hooks;

    OpenSlPcmDescriptor pcm16{kOpenSlDataFormatPcm,
                              2,
                              48000000,
                              16,
                              16,
                              kOpenSlByteOrderLittleEndian,
                              0};
    const auto parsed16 = ParseOpenSlPcmContract(pcm16);
    Check(parsed16 && parsed16->sample_rate == 48000 &&
              parsed16->channels == 2 && parsed16->format == PcmFormat::kSigned16,
          "standard PCM16 descriptor must resolve exactly");

    OpenSlPcmDescriptor pcm8 = pcm16;
    pcm8.bits_per_sample = pcm8.container_bits = 8;
    const auto parsed8 = ParseOpenSlPcmContract(pcm8);
    Check(parsed8 && parsed8->format == PcmFormat::kUnsigned8,
          "standard PCM8 must use the unsigned OpenSL representation");

    OpenSlPcmDescriptor packed24 = pcm16;
    packed24.bits_per_sample = packed24.container_bits = 24;
    const auto parsed24 = ParseOpenSlPcmContract(packed24);
    Check(parsed24 && parsed24->format == PcmFormat::kSigned24Packed,
          "24-bit PCM in a 24-bit container must resolve as packed PCM24");

    OpenSlPcmDescriptor floating{kOpenSlAndroidDataFormatPcmEx,
                                 1,
                                 96000000,
                                 32,
                                 32,
                                 kOpenSlByteOrderLittleEndian,
                                 kOpenSlPcmRepresentationFloat};
    const auto parsed_float = ParseOpenSlPcmContract(floating);
    Check(parsed_float && parsed_float->format == PcmFormat::kFloat32,
          "Android PCM_EX float must resolve as float32");

    OpenSlPcmDescriptor invalid = pcm16;
    invalid.sample_rate_millihz = 48000001;
    Check(!ParseOpenSlPcmContract(invalid),
          "fractional-Hz sample rates must fail closed");
    invalid = pcm16;
    invalid.container_bits = 32;
    Check(!ParseOpenSlPcmContract(invalid),
          "ambiguous 24-in-32/container mismatches must fail closed");
    invalid = floating;
    invalid.representation = kOpenSlPcmRepresentationUnsignedInt;
    Check(!ParseOpenSlPcmContract(invalid),
          "unsupported unsigned PCM32 must fail closed");
    invalid = pcm16;
    invalid.byte_order = 1;
    Check(!ParseOpenSlPcmContract(invalid),
          "big-endian PCM must fail closed on Android");

    if (gFailures != 0)
    {
        return 1;
    }
    std::fprintf(stderr, "opensl_pcm_contract_test: all checks passed\n");
    return 0;
}
