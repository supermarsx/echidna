#include "hooks/audioflinger_format.h"

#include <array>
#include <cstdio>

/**
 * @file audioflinger_format_test.cpp
 * @brief Host harness for the hard-OFF AudioFlinger admission guard.
 *
 * Proves two independent properties without a device:
 *   1. The descriptor-geometry validator accepts self-consistent PCM byte spans
 *      and fail-closes on every inconsistency.
 *   2. The full admission entrypoint is hard-OFF by default: with the boundary
 *      gate disabled (the shipped configuration) it refuses even a perfect,
 *      proven-provenance descriptor. This is the code-level guarantee that the
 *      route stays disabled.
 */

namespace
{
    using echidna::audio::PcmFormat;
    using echidna::hooks::AdmitAudioFlingerCaptureBuffer;
    using echidna::hooks::AudioFlingerAdmission;
    using echidna::hooks::AudioFlingerBoundaryProven;
    using echidna::hooks::AudioFlingerBoundaryProvenance;
    using echidna::hooks::AudioFlingerCaptureBufferDescriptor;
    using echidna::hooks::kAudioFlingerMaxChannels;
    using echidna::hooks::kAudioFlingerMaxFrameCount;
    using echidna::hooks::ValidateAudioFlingerBufferGeometry;

    int g_failures = 0;

    void Check(bool condition, const char *label)
    {
        if (!condition)
        {
            std::fprintf(stderr, "audioflinger_format_test: FAIL %s\n", label);
            ++g_failures;
        }
    }

    // A generously sized backing span so descriptors can carry a non-null
    // pointer; the guard never dereferences it, it only reasons about geometry.
    std::array<std::uint8_t, 8192> g_backing{};

    AudioFlingerCaptureBufferDescriptor MakeDescriptor(size_t byte_length,
                                                       uint32_t frame_count,
                                                       uint32_t channels,
                                                       PcmFormat format)
    {
        AudioFlingerCaptureBufferDescriptor desc;
        desc.data = g_backing.data();
        desc.byte_length = byte_length;
        desc.frame_count = frame_count;
        desc.channels = channels;
        desc.format = format;
        desc.provenance = AudioFlingerBoundaryProvenance::kUnproven;
        return desc;
    }
} // namespace

int main()
{
    // --- Geometry validator: accepted, self-consistent descriptors. ---
    // pcm16 stereo: 480 frames * 2 ch * 2 bytes = 1920 bytes.
    Check(ValidateAudioFlingerBufferGeometry(
              MakeDescriptor(1920, 480, 2, PcmFormat::kSigned16)) ==
              AudioFlingerAdmission::kAdmitted,
          "pcm16_stereo_admitted");
    // float32 mono: 256 frames * 1 ch * 4 bytes = 1024 bytes.
    Check(ValidateAudioFlingerBufferGeometry(
              MakeDescriptor(1024, 256, 1, PcmFormat::kFloat32)) ==
              AudioFlingerAdmission::kAdmitted,
          "float32_mono_admitted");
    // pcm24_packed stereo: 100 frames * 2 ch * 3 bytes = 600 bytes.
    Check(ValidateAudioFlingerBufferGeometry(
              MakeDescriptor(600, 100, 2, PcmFormat::kSigned24Packed)) ==
              AudioFlingerAdmission::kAdmitted,
          "pcm24_packed_stereo_admitted");

    // --- Geometry validator: fail-closed paths. ---
    {
        auto null_buffer = MakeDescriptor(1920, 480, 2, PcmFormat::kSigned16);
        null_buffer.data = nullptr;
        Check(ValidateAudioFlingerBufferGeometry(null_buffer) ==
                  AudioFlingerAdmission::kNullBuffer,
              "null_buffer_refused");
    }
    Check(ValidateAudioFlingerBufferGeometry(
              MakeDescriptor(0, 480, 2, PcmFormat::kSigned16)) ==
              AudioFlingerAdmission::kEmptyBuffer,
          "zero_bytes_refused");
    Check(ValidateAudioFlingerBufferGeometry(
              MakeDescriptor(1920, 0, 2, PcmFormat::kSigned16)) ==
              AudioFlingerAdmission::kEmptyBuffer,
          "zero_frames_refused");
    Check(ValidateAudioFlingerBufferGeometry(
              MakeDescriptor(1920, 480, 0, PcmFormat::kSigned16)) ==
              AudioFlingerAdmission::kInvalidGeometry,
          "zero_channels_refused");
    Check(ValidateAudioFlingerBufferGeometry(
              MakeDescriptor(1920, 480, kAudioFlingerMaxChannels + 1,
                             PcmFormat::kSigned16)) ==
              AudioFlingerAdmission::kInvalidGeometry,
          "too_many_channels_refused");
    Check(ValidateAudioFlingerBufferGeometry(
              MakeDescriptor(1920, kAudioFlingerMaxFrameCount + 1, 2,
                             PcmFormat::kSigned16)) ==
              AudioFlingerAdmission::kInvalidGeometry,
          "too_many_frames_refused");
    // Declared 480 frames but bytes describe 481 frames (1924 bytes).
    Check(ValidateAudioFlingerBufferGeometry(
              MakeDescriptor(1924, 480, 2, PcmFormat::kSigned16)) ==
              AudioFlingerAdmission::kByteLengthMismatch,
          "frame_count_mismatch_refused");
    // Byte length not a whole number of pcm16 stereo frames (odd trailing byte).
    Check(ValidateAudioFlingerBufferGeometry(
              MakeDescriptor(1921, 480, 2, PcmFormat::kSigned16)) ==
              AudioFlingerAdmission::kByteLengthMismatch,
          "partial_frame_refused");
    // Unsupported format (out-of-range enumerator -> zero byte width).
    Check(ValidateAudioFlingerBufferGeometry(
              MakeDescriptor(1920, 480, 2, static_cast<PcmFormat>(99))) ==
              AudioFlingerAdmission::kUnsupportedFormat,
          "unsupported_format_refused");

    // --- Hard-OFF gate: shipped build must refuse everything. ---
    Check(!AudioFlingerBoundaryProven(),
          "boundary_gate_off_by_default");
    {
        // A perfectly valid descriptor that even attests proven provenance is
        // STILL refused, because the compile-time gate is off in this build.
        auto proven = MakeDescriptor(1920, 480, 2, PcmFormat::kSigned16);
        proven.provenance = AudioFlingerBoundaryProvenance::kProvenAudioserver;
        const auto admission = AdmitAudioFlingerCaptureBuffer(proven);
        if (AudioFlingerBoundaryProven())
        {
            // Defensive: if a companion build flips the gate on, a valid+proven
            // descriptor must be admitted and the geometry check must govern.
            Check(admission == AudioFlingerAdmission::kAdmitted,
                  "proven_descriptor_admitted_when_gate_on");
        }
        else
        {
            Check(admission == AudioFlingerAdmission::kBoundaryNotProven,
                  "proven_descriptor_refused_when_gate_off");
        }
    }
    {
        // Unproven provenance is refused regardless of gate state.
        auto valid = MakeDescriptor(1920, 480, 2, PcmFormat::kSigned16);
        Check(AdmitAudioFlingerCaptureBuffer(valid) ==
                  AudioFlingerAdmission::kBoundaryNotProven,
              "unproven_provenance_refused");
    }

    if (g_failures != 0)
    {
        std::fprintf(stderr, "audioflinger_format_test: %d check(s) failed\n",
                     g_failures);
        return 1;
    }
    std::fprintf(stderr, "audioflinger_format_test: all checks passed\n");
    return 0;
}
