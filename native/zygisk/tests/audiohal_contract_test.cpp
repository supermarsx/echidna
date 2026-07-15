#include "hooks/audiohal_contract.h"

#include <cstdio>

int main()
{
    using echidna::audio::PcmFormat;
    using echidna::hooks::ParseAudioHalPcmContract;

    const auto pcm16 = ParseAudioHalPcmContract("48000", "2", "pcm16");
    if (!pcm16 || pcm16->sample_rate != 48000 || pcm16->channels != 2 ||
        pcm16->format != PcmFormat::kSigned16)
    {
        return 1;
    }
    const auto packed = ParseAudioHalPcmContract("44100", "1", "pcm24_packed");
    if (!packed || packed->format != PcmFormat::kSigned24Packed)
    {
        return 1;
    }
    const auto pcm32 = ParseAudioHalPcmContract("96000", "8", "pcm32");
    if (!pcm32 || pcm32->format != PcmFormat::kSigned32)
    {
        return 1;
    }
    const auto floating = ParseAudioHalPcmContract("48000", "2", "float");
    if (!floating || floating->format != PcmFormat::kFloat32)
    {
        return 1;
    }
    if (ParseAudioHalPcmContract(nullptr, "2", "pcm16") ||
        ParseAudioHalPcmContract("48000junk", "2", "pcm16") ||
        ParseAudioHalPcmContract("7999", "2", "pcm16") ||
        ParseAudioHalPcmContract("48000", "0", "pcm16") ||
        ParseAudioHalPcmContract("48000", "2", "pcm24") ||
        ParseAudioHalPcmContract("48000", "2", nullptr))
    {
        return 1;
    }
    std::fprintf(stderr, "audiohal_contract_test: all checks passed\n");
    return 0;
}
