#include "utils/process_utils.h"

#include <fstream>

namespace echidna {
namespace utils {

std::string CurrentProcessName() {
    std::ifstream cmdline("/proc/self/cmdline");
    std::string value;
    std::getline(cmdline, value, '\0');
    return value;
}

}  // namespace utils
}  // namespace echidna
