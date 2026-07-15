#include "utils/process_utils.h"

/**
 * @file process_utils.cpp
 * @brief Implementation of process name helpers.
 */

#include <charconv>
#include <cstdint>
#include <fstream>
#include <limits>
#include <mutex>
#include <string_view>
#include <utility>

#ifdef __ANDROID__
#include <fcntl.h>
#include <sys/stat.h>
#include <unistd.h>

#include <cerrno>
#endif

namespace echidna
{
    namespace utils
    {
        namespace
        {
            constexpr size_t kMaximumPackagesListBytes = 8 * 1024 * 1024;
            constexpr int64_t kAndroidUserUidRange = 100000;
            constexpr int64_t kAndroidApplicationIdMinimum = 10000;
            constexpr int64_t kAndroidApplicationIdMaximum = 19999;

            std::string &ProcessNameCache()
            {
                static std::string process = CurrentProcessName();
                return process;
            }

            std::mutex &ProcessNameMutex()
            {
                static std::mutex mutex;
                return mutex;
            }
        } // namespace

        std::string CurrentProcessName()
        {
            std::ifstream cmdline("/proc/self/cmdline");
            std::string value;
            std::getline(cmdline, value, '\0');
            return value;
        }

        void SetCachedProcessName(std::string process_name)
        {
            if (process_name.empty())
            {
                return;
            }
            std::lock_guard<std::mutex> lock(ProcessNameMutex());
            ProcessNameCache() = std::move(process_name);
        }

        std::string CachedProcessName()
        {
            std::lock_guard<std::mutex> lock(ProcessNameMutex());
            return ProcessNameCache();
        }

        int64_t ParsePackageUid(std::string_view packages_list,
                                std::string_view package_name)
        {
            if (package_name.empty())
            {
                return -1;
            }

            int64_t matched_uid = -1;
            size_t line_begin = 0;
            while (line_begin < packages_list.size())
            {
                size_t line_end = packages_list.find('\n', line_begin);
                if (line_end == std::string_view::npos)
                {
                    line_end = packages_list.size();
                }
                const std::string_view line =
                    packages_list.substr(line_begin, line_end - line_begin);
                line_begin = line_end == packages_list.size() ? line_end : line_end + 1;

                const size_t package_end = line.find_first_of(" \t");
                if (package_end == std::string_view::npos ||
                    line.substr(0, package_end) != package_name)
                {
                    continue;
                }
                const size_t uid_begin = line.find_first_not_of(" \t", package_end);
                if (uid_begin == std::string_view::npos)
                {
                    return -1;
                }
                size_t uid_end = line.find_first_of(" \t\r", uid_begin);
                if (uid_end == std::string_view::npos)
                {
                    uid_end = line.size();
                }
                const std::string_view uid_text = line.substr(uid_begin, uid_end - uid_begin);
                uint32_t parsed_uid = 0;
                const auto parsed = std::from_chars(uid_text.data(),
                                                    uid_text.data() + uid_text.size(),
                                                    parsed_uid);
                if (uid_text.empty() || parsed.ec != std::errc{} ||
                    parsed.ptr != uid_text.data() + uid_text.size())
                {
                    return -1;
                }
                if (matched_uid >= 0)
                {
                    return -1;
                }
                matched_uid = static_cast<int64_t>(parsed_uid);
            }
            return matched_uid;
        }

        int64_t ResolvePackageUid(std::string_view package_name,
                                  const std::string &packages_list_path)
        {
#ifdef __ANDROID__
            // PackageManager writes this registry as a system-owned regular file.
            // Open without following links and validate metadata on the same fd so
            // the privileged pre-specialization read cannot be redirected.
            const int fd = ::open(packages_list_path.c_str(),
                                  O_RDONLY | O_CLOEXEC | O_NOFOLLOW);
            if (fd < 0)
            {
                return -1;
            }
            struct stat metadata
            {
            };
            constexpr uid_t kRootUid = 0;
            constexpr uid_t kSystemUid = 1000;
            if (::fstat(fd, &metadata) != 0 || !S_ISREG(metadata.st_mode) ||
                (metadata.st_uid != kRootUid && metadata.st_uid != kSystemUid) ||
                (metadata.st_mode & (S_IWGRP | S_IWOTH)) != 0 || metadata.st_size <= 0 ||
                metadata.st_size > static_cast<off_t>(kMaximumPackagesListBytes))
            {
                ::close(fd);
                return -1;
            }

            std::string contents(static_cast<size_t>(metadata.st_size), '\0');
            size_t received = 0;
            while (received < contents.size())
            {
                const ssize_t result =
                    ::read(fd, contents.data() + received, contents.size() - received);
                if (result < 0 && errno == EINTR)
                {
                    continue;
                }
                if (result <= 0)
                {
                    ::close(fd);
                    return -1;
                }
                received += static_cast<size_t>(result);
            }
            ::close(fd);
            return ParsePackageUid(contents, package_name);
#else
            std::ifstream packages(packages_list_path, std::ios::binary);
            if (!packages)
            {
                return -1;
            }
            packages.seekg(0, std::ios::end);
            const std::streamoff size = packages.tellg();
            if (size <= 0 || size > static_cast<std::streamoff>(kMaximumPackagesListBytes))
            {
                return -1;
            }
            packages.seekg(0, std::ios::beg);
            std::string contents(static_cast<size_t>(size), '\0');
            if (!packages.read(contents.data(), size))
            {
                return -1;
            }
            return ParsePackageUid(contents, package_name);
#endif
        }

        int64_t PackageUidForTargetUser(int64_t package_uid, int64_t target_uid)
        {
            if (package_uid < 0 || target_uid < 0)
            {
                return -1;
            }
            const int64_t app_id = package_uid % kAndroidUserUidRange;
            if (app_id < kAndroidApplicationIdMinimum ||
                app_id > kAndroidApplicationIdMaximum)
            {
                return -1;
            }
            const int64_t user_id = target_uid / kAndroidUserUidRange;
            if (user_id > (std::numeric_limits<int64_t>::max() - app_id) /
                              kAndroidUserUidRange)
            {
                return -1;
            }
            return user_id * kAndroidUserUidRange + app_id;
        }

    } // namespace utils
} // namespace echidna
