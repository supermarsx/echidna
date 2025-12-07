#include "utils/offset_probe.h"

#ifdef __ANDROID__
#include <android/log.h>
#else
#define __android_log_print(...) ((void)0)
#define ANDROID_LOG_INFO 0
#endif

#include <fcntl.h>
#include <unistd.h>

#include <cstdio>

namespace echidna {
namespace utils {

void OffsetProbe::LogOffsets(const std::string &tag, int32_t sr_offset, int32_t ch_offset) {
    __android_log_print(ANDROID_LOG_INFO,
                        "echidna",
                        "%s offsets sr=%d ch=%d",
                        tag.c_str(),
                        sr_offset,
                        ch_offset);
}

void OffsetProbe::WriteOffsetsToFile(const std::string &path,
                                     int32_t sr_offset,
                                     int32_t ch_offset) {
    int fd = ::open(path.c_str(), O_WRONLY | O_CREAT | O_TRUNC, 0644);
    if (fd < 0) {
        return;
    }
    char buf[128];
    int len = std::snprintf(buf,
                            sizeof(buf),
                            "sr_offset=%d\nch_mask_offset=%d\n",
                            sr_offset,
                            ch_offset);
    if (len > 0) {
        ::write(fd, buf, static_cast<size_t>(len));
    }
    ::close(fd);
}

}  // namespace utils
}  // namespace echidna
