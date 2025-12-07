#pragma once

#include <cstddef>
#include <cstdint>
#include <string>

namespace echidna {
namespace utils {

/**
 * @brief Debug helper to dump discovered offsets for AudioFlinger/Audio HAL objects.
 */
class OffsetProbe {
  public:
    static void LogOffsets(const std::string &tag, int32_t sr_offset, int32_t ch_offset);
    static void WriteOffsetsToFile(const std::string &path, int32_t sr_offset, int32_t ch_offset);
};

}  // namespace utils
}  // namespace echidna
