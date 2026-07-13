#pragma once

/**
 * @file android_shared_memory.h
 * @brief Anonymous shared-memory backing for Android/bionic, replacing POSIX
 * shm_open (which bionic does not provide).
 *
 * bionic has no shm_open/shm_unlink, so the config and telemetry regions are
 * created with memfd_create (a libc syscall, no libandroid link needed) and
 * kept in a process-global, name-keyed registry. Multiple helper instances that
 * ask for the same logical name therefore map the SAME region — this preserves
 * the intra-process sharing the old shm_open("/name") design relied on (e.g.
 * the profile-sync reader applies config through one ConfigSharedMemory instance
 * while SharedState reads it through another).
 *
 * The region is a real fd + fixed size while it is being created: it is
 * ftruncate'd and mmap'd, then the fd is closed immediately (the mapping keeps
 * the memfd region alive on its own). Closing the fd is required for correctness
 * on Android 14: a Java app process is specialized from the (app-)zygote by
 * fork WITHOUT exec, so an inherited memfd is never cleared by MFD_CLOEXEC, and
 * the app-zygote fd sanitizer aborts any specialized app that still holds a
 * non-allowlisted fd (e.g. Chrome crashing on "/memfd:echidna_config"). These
 * config/telemetry regions are intra-process only and their fd is never handed
 * to another process, so dropping the fd after mmap costs nothing. (The
 * profile-sync socket reader is a separate path in profile_sync_server.cpp and
 * is unaffected.)
 *
 * Header-only (all functions inline) so no new translation unit / CMakeLists
 * source entry is required. The function-local statics give a single registry
 * instance shared across every translation unit that includes this header.
 *
 * memfd_create is used via the raw syscall so it works regardless of the libc
 * wrapper's minimum API level (the wrapper is API 30+, the syscall is available
 * on every supported Android kernel). ASharedMemory_create was considered but
 * requires linking libandroid, which the zygisk target does not link.
 */

#include <fcntl.h>
#include <sys/mman.h>
#include <sys/stat.h>
#include <sys/syscall.h>
#include <sys/types.h>
#include <unistd.h>

#include <cerrno>
#include <cstddef>
#include <map>
#include <mutex>
#include <string>

#ifndef MFD_CLOEXEC
#define MFD_CLOEXEC 0x0001U
#endif

namespace echidna
{
    namespace utils
    {

        /**
         * @brief Result of acquiring a shared region: a mapped pointer + backing fd.
         *
         * @c addr is nullptr on failure.
         */
        struct AndroidSharedRegion
        {
            void *addr{nullptr};
            int fd{-1}; ///< Always -1: the backing fd is closed after mmap (see file header).
            size_t size{0};
            bool created{false};   ///< True when this call allocated a fresh (zeroed) region.
            bool read_only{false}; ///< True when the region is mapped PROT_READ only (see below).
        };

        namespace detail
        {
            struct SharedRegionEntry
            {
                void *addr{nullptr};
                int fd{-1};
                size_t size{0};
                int refcount{0};
                bool read_only{false};
            };

            /** Process-global mutex guarding the registry (single instance across TUs). */
            inline std::mutex &SharedRegistryMutex()
            {
                static std::mutex mutex;
                return mutex;
            }

            /** Process-global name->region registry (single instance across TUs). */
            inline std::map<std::string, SharedRegionEntry> &SharedRegistry()
            {
                static std::map<std::string, SharedRegionEntry> registry;
                return registry;
            }

            inline int MemfdCreate(const char *name, unsigned int flags)
            {
#if defined(__NR_memfd_create)
                return static_cast<int>(::syscall(__NR_memfd_create, name, flags));
#elif defined(SYS_memfd_create)
                return static_cast<int>(::syscall(SYS_memfd_create, name, flags));
#else
                (void)name;
                (void)flags;
                errno = ENOSYS;
                return -1;
#endif
            }
        } // namespace detail

        /**
         * @brief Acquire (or attach to) the process-global anonymous shared region
         * identified by @p name, sized @p size bytes.
         *
         * The first caller for a given name creates and maps the region; later
         * callers with the same name receive the same mapping and fd. Each
         * successful call takes a reference; pair it with ReleaseSharedRegion(name).
         *
         * @return AndroidSharedRegion with @c addr != nullptr on success; a default
         *         (addr == nullptr) value on failure.
         */
        inline AndroidSharedRegion AcquireSharedRegion(const char *name, size_t size)
        {
            std::scoped_lock lock(detail::SharedRegistryMutex());
            auto &registry = detail::SharedRegistry();
            const std::string key(name ? name : "");

            auto it = registry.find(key);
            if (it != registry.end() && it->second.addr)
            {
                it->second.refcount += 1;
                return {it->second.addr, it->second.fd, it->second.size, false,
                        it->second.read_only};
            }

            // Strip a leading '/' so the region label is a plain name.
            const char *label = (name && name[0] == '/') ? name + 1 : name;
            const std::string plain = (label && label[0]) ? std::string(label) : "echidna_shm";

            // Back the region with a real file under the module's world-shared tmp
            // dir rather than an anonymous memfd. A file mapping is visible ACROSS
            // processes (the controller writes config; every hooked app reads it;
            // hooked apps publish telemetry the controller reads) — a memfd is
            // process-local and cannot do that. If the shared file is unavailable
            // (e.g. sandbox/SELinux denies it), fall back to a private memfd so the
            // process still gets a valid, non-crashing region.
            const std::string path = std::string("/data/local/tmp/echidna/") + plain + ".bin";
            bool fresh = false;
            bool read_only = false;
            int fd = ::open(path.c_str(), O_RDWR | O_CREAT, 0666);
            if (fd >= 0)
            {
                struct stat st;
                fresh = (::fstat(fd, &st) == 0) && (st.st_size == 0);
                if ((::fstat(fd, &st) != 0 || static_cast<size_t>(st.st_size) < size) &&
                    ::ftruncate(fd, static_cast<off_t>(size)) != 0)
                {
                    ::close(fd);
                    fd = -1;
                }
            }
            else
            {
                // O_RDWR was denied. Under enforcing SELinux a hooked app has only
                // read access to the controller-published config region (the writer
                // is root/the service), so retry the pre-created region read-only.
                // The region must already exist and be at least `size` bytes (the
                // module's post-fs-data.sh creates and sizes it); a hooked app
                // cannot create or grow it. Falls through to a private memfd below
                // if the shared region is genuinely unavailable.
                int ro_fd = ::open(path.c_str(), O_RDONLY);
                if (ro_fd >= 0)
                {
                    struct stat st;
                    if (::fstat(ro_fd, &st) == 0 && static_cast<size_t>(st.st_size) >= size)
                    {
                        fd = ro_fd;
                        read_only = true;
                    }
                    else
                    {
                        ::close(ro_fd);
                    }
                }
            }
            if (fd < 0)
            {
                read_only = false;
                fd = detail::MemfdCreate(plain.c_str(), MFD_CLOEXEC);
                if (fd < 0)
                {
                    return {};
                }
                if (::ftruncate(fd, static_cast<off_t>(size)) != 0)
                {
                    ::close(fd);
                    return {};
                }
                fresh = true;
            }
            const int prot = read_only ? PROT_READ : (PROT_READ | PROT_WRITE);
            void *addr = ::mmap(nullptr, size, prot, MAP_SHARED, fd, 0);
            if (addr == MAP_FAILED)
            {
                ::close(fd);
                return {};
            }

            // The mapping keeps the region alive on its own; the fd is no longer
            // needed. Close it now so no un-allowlisted fd lingers into app-zygote
            // specialization (Android aborts a specialized app that still holds a
            // non-allowlisted fd). See file header for the rationale.
            ::close(fd);

            detail::SharedRegionEntry entry;
            entry.addr = addr;
            entry.fd = -1;
            entry.size = size;
            entry.refcount = 1;
            entry.read_only = read_only;
            registry[key] = entry;
            return {addr, -1, size, fresh, read_only};
        }

        /**
         * @brief Release one reference to a named region previously acquired with
         * AcquireSharedRegion. The region is unmapped and its fd closed only when
         * the last reference is dropped.
         */
        inline void ReleaseSharedRegion(const char *name)
        {
            std::scoped_lock lock(detail::SharedRegistryMutex());
            auto &registry = detail::SharedRegistry();
            auto it = registry.find(std::string(name ? name : ""));
            if (it == registry.end())
            {
                return;
            }
            if (--it->second.refcount > 0)
            {
                return;
            }
            if (it->second.addr)
            {
                ::munmap(it->second.addr, it->second.size);
            }
            if (it->second.fd >= 0)
            {
                ::close(it->second.fd);
            }
            registry.erase(it);
        }

    } // namespace utils
} // namespace echidna
