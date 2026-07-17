#pragma once

/**
 * @file audioflinger_format.h
 * @brief Host-testable, hard-OFF admission guard for a hypothetical AudioFlinger
 *        RecordThread capture-buffer transform.
 *
 * The live AudioFlinger route is DISABLED and stays disabled (see
 * @c audioflinger_hook_manager.cpp and docs/hardening/audioflinger-route.md).
 * Zygisk injects whitelisted application processes, never @c audioserver, and
 * @c RecordThread exposes no stable PCM-buffer transform ABI across Android
 * versions. This header contains none of that unreachable machinery. It carries
 * only the part that CAN be reasoned about and verified without a device: the
 * admission-control predicate a future, separately-proven audioserver companion
 * would have to satisfy before it were ever allowed to hand a raw capture buffer
 * to the DSP.
 *
 * The predicate is deliberately fail-closed on two independent axes:
 *   1. Boundary provenance — the gate below defaults OFF, so
 *      @ref AdmitAudioFlingerCaptureBuffer refuses every buffer regardless of
 *      its contents in a normal build. Enabling it is an out-of-tree act a
 *      companion build performs only after proving the injection boundary.
 *   2. Descriptor self-consistency — even with the gate on, a descriptor whose
 *      byte length disagrees with its declared frame/channel/format geometry is
 *      rejected, because the RecordThread ABI is not stable enough to trust a
 *      caller-supplied layout blindly.
 *
 * Nothing here references an AOSP-internal type; the descriptor is expressed in
 * ABI-independent quantities (a byte span plus PCM geometry). This mirrors the
 * standalone contract-scaffold pattern already used for the sibling
 * device-gated route (see @c audiohal_contract.h), which is likewise host-tested
 * and intentionally not wired into its disabled @c install().
 */

#include <cstddef>
#include <cstdint>

#include "audio/pcm_buffer_processor.h"

namespace echidna::hooks
{

    /**
     * Compile-time boundary gate. Defaults OFF. While this is 0, the AudioFlinger
     * route has NO proven audioserver-injection boundary and every candidate
     * capture buffer is refused. Defining it to 1 is meaningful only inside a
     * separate companion build that has independently established that boundary;
     * it does not, on its own, make the route reachable in this module.
     */
#ifndef ECHIDNA_AUDIOFLINGER_BOUNDARY_PROVEN
#define ECHIDNA_AUDIOFLINGER_BOUNDARY_PROVEN 0
#endif

    /** True only when the compile-time boundary gate is enabled. */
    constexpr bool AudioFlingerBoundaryProven()
    {
        return ECHIDNA_AUDIOFLINGER_BOUNDARY_PROVEN != 0;
    }

    /**
     * Provenance an injecting companion would attach to a candidate buffer. Only
     * @ref kProvenAudioserver is ever admissible, and even then only when the
     * compile-time gate is also on. Every other value fails closed.
     */
    enum class AudioFlingerBoundaryProvenance : uint8_t
    {
        kUnproven = 0,          ///< Default. No injection boundary established.
        kProvenAudioserver = 1, ///< Reserved for a future proven audioserver companion.
    };

    /**
     * A RecordThread capture buffer as an audioserver-side companion would
     * observe it, described only in stable, ABI-independent quantities.
     */
    struct AudioFlingerCaptureBufferDescriptor
    {
        const void *data{nullptr};
        size_t byte_length{0};
        uint32_t frame_count{0};
        uint32_t channels{0};
        audio::PcmFormat format{audio::PcmFormat::kSigned16};
        AudioFlingerBoundaryProvenance provenance{
            AudioFlingerBoundaryProvenance::kUnproven};
    };

    /** Outcome of admission control. Anything but @ref kAdmitted is a refusal. */
    enum class AudioFlingerAdmission : uint8_t
    {
        kAdmitted,           ///< Descriptor is self-consistent AND the boundary is proven.
        kBoundaryNotProven,  ///< The hard-OFF gate (or unproven provenance) refused it.
        kNullBuffer,         ///< data pointer was null.
        kEmptyBuffer,        ///< byte_length or frame_count was zero.
        kInvalidGeometry,    ///< channels/frame_count outside the accepted range.
        kUnsupportedFormat,  ///< format has no valid byte width.
        kByteLengthMismatch, ///< byte_length disagrees with frames*channels*width.
    };

    /** Largest per-transfer frame count a single RecordThread buffer may claim. */
    inline constexpr uint32_t kAudioFlingerMaxFrameCount = 1u << 20; // ~1M frames.
    /** Largest channel count accepted, matching @c ResolveBufferLayout's bound. */
    inline constexpr uint32_t kAudioFlingerMaxChannels = 8;

    /**
     * Pure, device-independent descriptor validation. Does NOT consult the
     * boundary gate — it answers only "is this byte span internally consistent
     * with its declared PCM geometry?", which is exactly the check that must
     * survive an unstable RecordThread ABI. Reuses @c ResolveBufferLayout so the
     * byte/frame arithmetic has a single source of truth.
     */
    inline AudioFlingerAdmission ValidateAudioFlingerBufferGeometry(
        const AudioFlingerCaptureBufferDescriptor &desc)
    {
        if (desc.data == nullptr)
        {
            return AudioFlingerAdmission::kNullBuffer;
        }
        if (desc.byte_length == 0 || desc.frame_count == 0)
        {
            return AudioFlingerAdmission::kEmptyBuffer;
        }
        if (desc.channels == 0 || desc.channels > kAudioFlingerMaxChannels ||
            desc.frame_count > kAudioFlingerMaxFrameCount)
        {
            return AudioFlingerAdmission::kInvalidGeometry;
        }

        audio::BufferLayout layout;
        if (!audio::ResolveBufferLayout(desc.byte_length, desc.format,
                                        desc.channels, &layout))
        {
            // ResolveBufferLayout rejects unknown formats (zero byte width) and
            // any byte length that is not a whole number of frames. Distinguish
            // the unsupported-format case so callers can report it precisely.
            audio::BufferLayout probe;
            if (!audio::ResolveBufferLayout(desc.channels *
                                                static_cast<size_t>(desc.frame_count),
                                            desc.format, desc.channels, &probe))
            {
                return AudioFlingerAdmission::kUnsupportedFormat;
            }
            return AudioFlingerAdmission::kByteLengthMismatch;
        }
        if (layout.frames != desc.frame_count)
        {
            return AudioFlingerAdmission::kByteLengthMismatch;
        }
        return AudioFlingerAdmission::kAdmitted;
    }

    /**
     * Full admission control: the descriptor must be self-consistent AND the
     * boundary must be proven (compile-time gate on and provenance attested). In
     * a normal build the gate is OFF, so this ALWAYS returns
     * @ref AudioFlingerAdmission::kBoundaryNotProven — the code-level expression
     * of "this route is disabled". The geometry validator above remains fully
     * exercisable on its own so the layout logic can be host-tested.
     */
    inline AudioFlingerAdmission AdmitAudioFlingerCaptureBuffer(
        const AudioFlingerCaptureBufferDescriptor &desc)
    {
        if (!AudioFlingerBoundaryProven() ||
            desc.provenance != AudioFlingerBoundaryProvenance::kProvenAudioserver)
        {
            return AudioFlingerAdmission::kBoundaryNotProven;
        }
        return ValidateAudioFlingerBufferGeometry(desc);
    }

} // namespace echidna::hooks
