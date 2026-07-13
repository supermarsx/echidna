/* Copyright 2022-2023 John "topjohnwu" Wu
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

// This is the public API for Zygisk modules. It is the canonical Zygisk API v3
// contract as published upstream (Magisk v25.x native/jni/zygisk/api.hpp): the
// type layout, function-pointer table, and ABI must NOT be altered or Zygisk
// module compatibility will break. Only cosmetic whitespace has been reformatted
// to this repository's .clang-format style so the CI format gate stays green; no
// declaration, signature, enum value, or struct layout has changed. The header
// is self-contained and only depends on <jni.h> (provided by the NDK sysroot on
// device and by the JDK on a host toolchain).
//
// API v3 is chosen deliberately: Magisk's loader rejects a module whose
// advertised api_version is greater than the version it implements
// (`if (ver > ZYGISK_API_VERSION) return false;`) and then dereferences the
// unregistered module during system-server specialization, crashing zygote.
// v3 is the highest version supported by Magisk 24.1+ and is accepted (via the
// backward-compatible `switch (*ver)` dispatch) by every newer Magisk release,
// so it gives the widest device compatibility. This module drives all of its
// hooking through its own PltResolver and never calls the version-variant
// api_table entry points, so v3 loses no functionality.

#pragma once

#include <jni.h>
#include <stdint.h>
#include <sys/types.h>

#define ZYGISK_API_VERSION 3

/*

***************
* Introduction
***************

On Android, all app processes are forked from a special process called "Zygote".
Zygote starts up when the system boots, and pre-loads the Android runtime and
common libraries. When an app has to be started, Zygote forks (specializes) into
the app process, giving it a fast startup time.

A Zygisk module is a native library loaded into the Zygote process. Because every
app process is forked from Zygote, a Zygisk module gains the ability to run its
own code in the context of every app (and the system server), before and after
each process is specialized. This is the ideal injection point for installing
in-process hooks.

**********************
* Development guide
**********************

Define a class and inherit ModuleBase to implement the callbacks for Zygisk.
Use the REGISTER_ZYGISK_MODULE macro to register that class to Zygisk.

Please note that modules are unloaded from the app process after all
post[XXX]Specialize callbacks return, UNLESS the module has installed hooks into
the process (in which case the library MUST remain mapped). Therefore, do NOT set
the DLCLOSE_MODULE_LIBRARY option if your module installs inline/PLT hooks.

Example:

class ExampleModule : public zygisk::ModuleBase {
public:
    void onLoad(zygisk::Api *api, JNIEnv *env) override {
        this->api = api;
        this->env = env;
    }
    void postAppSpecialize(const zygisk::AppSpecializeArgs *args) override {
        // Install your hooks here.
    }
private:
    zygisk::Api *api;
    JNIEnv *env;
};

REGISTER_ZYGISK_MODULE(ExampleModule)

*/

namespace zygisk
{

    struct Api;
    struct AppSpecializeArgs;
    struct ServerSpecializeArgs;

    class ModuleBase
    {
    public:
        // This method is called as soon as the module is loaded into the target process.
        // A Zygisk API handle will be passed as an argument.
        virtual void onLoad([[maybe_unused]] Api *api, [[maybe_unused]] JNIEnv *env) {}

        // This method is called before the app process is specialized.
        // At this point, the process just got forked from zygote, but no app specific
        // specialization is applied. This means that the process does not have any
        // sandbox restrictions and still runs with the same privilege of zygote.
        //
        // All the arguments that will be sent and used for app specialization is passed
        // as a single AppSpecializeArgs object. You can read and overwrite these arguments
        // to change how the app process will be specialized.
        //
        // If you need to run some operations as superuser, you can call Api::connectCompanion()
        // to get a socket to do IPC calls with a root companion process.
        virtual void preAppSpecialize([[maybe_unused]] AppSpecializeArgs *args) {}

        // This method is called after the app process is specialized.
        // At this point, the process has all sandbox restrictions enabled for this
        // application. This means that this method runs with the same privilege of the
        // app's own code.
        virtual void postAppSpecialize([[maybe_unused]] const AppSpecializeArgs *args) {}

        // This method is called before the system server process is specialized.
        // See preAppSpecialize(args) for more info.
        virtual void preServerSpecialize([[maybe_unused]] ServerSpecializeArgs *args) {}

        // This method is called after the system server process is specialized.
        // At this point, the process runs with the privilege of system_server.
        virtual void postServerSpecialize([[maybe_unused]] const ServerSpecializeArgs *args) {}
    };

    struct AppSpecializeArgs
    {
        // Required arguments. These arguments are guaranteed to exist on all Android versions.
        jint &uid;
        jint &gid;
        jintArray &gids;
        jint &runtime_flags;
        jobjectArray &rlimits;
        jint &mount_external;
        jstring &se_info;
        jstring &nice_name;
        jstring &instruction_set;
        jstring &app_data_dir;

        // Optional arguments. Please check whether the pointer is null before de-referencing
        jintArray *const fds_to_ignore;
        jboolean *const is_child_zygote;
        jboolean *const is_top_app;
        jobjectArray *const pkg_data_info_list;
        jobjectArray *const whitelisted_data_info_list;
        jboolean *const mount_data_dirs;
        jboolean *const mount_storage_dirs;

        AppSpecializeArgs() = delete;
    };

    struct ServerSpecializeArgs
    {
        jint &uid;
        jint &gid;
        jintArray &gids;
        jint &runtime_flags;
        jlong &permitted_capabilities;
        jlong &effective_capabilities;

        ServerSpecializeArgs() = delete;
    };

    namespace internal
    {
        struct api_table;
        template <class T>
        void entry_impl(api_table *, JNIEnv *);
    } // namespace internal

    // These values are return values of Api::getFlags
    enum StateFlag : uint32_t
    {
        // The user has granted root access to the current process
        PROCESS_GRANTED_ROOT = (1u << 0),

        // The current process was added into the denylist
        PROCESS_ON_DENYLIST = (1u << 1),
    };

    // Options that can be set by Api::setOption
    enum Option : int
    {
        // Force Magisk's denylist unmount routines to run on this process.
        //
        // Setting this option only makes sense in preAppSpecialize.
        // The actual unmounting happens during app process specialization.
        //
        // Set this option to force all Magisk and modules' files to be unmounted from the
        // mount namespace of the process, regardless of the denylist enforcement status.
        FORCE_DENYLIST_UNMOUNT = 0,

        // When this option is set, your module's library will be dlclose-ed after post[XXX]Specialize.
        // Be aware that after dlclose-ing your module, all of your code will be unmapped from memory.
        // YOU MUST NOT ENABLE THIS OPTION AFTER HOOKING ANY FUNCTIONS IN THE PROCESS.
        DLCLOSE_MODULE_LIBRARY = 1,
    };

    // All API methods will stop working after post[XXX]Specialize as Zygisk will be unloaded
    // from the specialized process afterwards.
    struct Api
    {

        // Connect to a root companion process and get a Unix domain socket for IPC.
        //
        // This API only works in the pre[XXX]Specialize methods due to SELinux restrictions.
        //
        // The pre[XXX]Specialize methods run with the same privilege of zygote.
        // If you would like to do some operations with superuser permissions, register a
        // companion handler function with REGISTER_ZYGISK_COMPANION(func).
        // Another process with superuser permissions (root) will be created, and this
        // process will run the companion function. This API will connect to that process
        // via Unix domain socket for IPC.
        //
        // Returns a file descriptor to a socket that is connected to the socket passed to
        // your module's companion request handler. Returns -1 if the connection attempt failed.
        int connectCompanion();

        // Get the file descriptor of the root folder of the current module.
        //
        // This API only works in the pre[XXX]Specialize methods.
        // Accessing the directory returned is only possible in the pre[XXX]Specialize methods
        // or in the root companion process (assuming that you sent the fd over the socket).
        // Both restrictions are due to SELinux and UID.
        //
        // Returns -1 if errors occurred.
        int getModuleDir();

        // Set various options for your module.
        // Please note that this method accepts one single option at a time.
        // Check zygisk::Option for the full list of options available.
        void setOption(Option opt);

        // Get information about the current process.
        // Returns bitwise-or'd zygisk::StateFlag values.
        uint32_t getFlags();

        // Hook JNI native methods for a class
        //
        // Lookup all registered JNI native methods and replace it with your own methods.
        // The original function pointer will be saved in each JNINativeMethod's fnPtr.
        // If no matching class, method name, or signature is found, that specific
        // JNINativeMethod.fnPtr will be set to nullptr.
        void hookJniNativeMethods(JNIEnv *env, const char *className, JNINativeMethod *methods, int numMethods);

        // For ELF PLT "call" instruction hooking.
        //
        // Parsing /proc/[PID]/maps will give you the memory map of a process. The `regex`
        // is matched against the pathname of memory-mapped ELF files; for all matches, the
        // PLT entry of `symbol` is redirected to `newFunc`. If `oldFunc` is not nullptr, the
        // original function pointer is saved to it.
        void pltHookRegister(const char *regex, const char *symbol, void *newFunc, void **oldFunc);

        // Exclude the matching ELF(s) (by `regex` over pathname and `symbol`) from PLT
        // hooking registered above.
        void pltHookExclude(const char *regex, const char *symbol);

        // Commit all the hooks that was previously registered.
        // Returns false if an error occurred.
        bool pltHookCommit();

    private:
        internal::api_table *tbl;
        template <class T>
        friend void internal::entry_impl(internal::api_table *, JNIEnv *);
    };

    // Register a class as a Zygisk module

#define REGISTER_ZYGISK_MODULE(clazz)                                   \
    extern "C" [[gnu::visibility("default")]] void zygisk_module_entry( \
        zygisk::internal::api_table *table, JNIEnv *env)                \
    {                                                                   \
        zygisk::internal::entry_impl<clazz>(table, env);                \
    }

    // Register a root companion request handler function for your module
    //
    // The function runs in a superuser daemon process and handles a root companion
    // request from your module running in a target process. The function must accept an
    // integer value, which is a Unix domain socket that is connected to the target process.
    //
    // NOTE: the function can run concurrently on multiple threads.
    // Be aware of race conditions if you have a globally shared resource.

#define REGISTER_ZYGISK_COMPANION(func)                                    \
    extern "C" [[gnu::visibility("default")]] void zygisk_companion_entry( \
        int client)                                                        \
    {                                                                      \
        func(client);                                                      \
    }

    /*********************************************************
     * The following is concrete implementation of the APIs.
     *********************************************************/

    namespace internal
    {

        struct module_abi
        {
            long api_version;
            ModuleBase *impl;

            void (*preAppSpecialize)(ModuleBase *, AppSpecializeArgs *);
            void (*postAppSpecialize)(ModuleBase *, const AppSpecializeArgs *);
            void (*preServerSpecialize)(ModuleBase *, ServerSpecializeArgs *);
            void (*postServerSpecialize)(ModuleBase *, const ServerSpecializeArgs *);

            module_abi(ModuleBase *module) : api_version(ZYGISK_API_VERSION), impl(module)
            {
                preAppSpecialize = [](auto m, auto args)
                { m->preAppSpecialize(args); };
                postAppSpecialize = [](auto m, auto args)
                { m->postAppSpecialize(args); };
                preServerSpecialize = [](auto m, auto args)
                { m->preServerSpecialize(args); };
                postServerSpecialize = [](auto m, auto args)
                { m->postServerSpecialize(args); };
            }
        };

        struct api_table
        {
            // Base
            void *impl;
            bool (*registerModule)(api_table *, module_abi *);

            void (*hookJniNativeMethods)(JNIEnv *, const char *, JNINativeMethod *, int);
            void (*pltHookRegister)(const char *, const char *, void *, void **);
            void (*pltHookExclude)(const char *, const char *);
            bool (*pltHookCommit)();
            int (*connectCompanion)(void * /* impl */);
            void (*setOption)(void * /* impl */, Option);
            int (*getModuleDir)(void * /* impl */);
            uint32_t (*getFlags)(void * /* impl */);
        };

        template <class T>
        void entry_impl(api_table *table, JNIEnv *env)
        {
            ModuleBase *module = new T();
            if (!table->registerModule(table, new module_abi(module)))
                return;
            auto api = new Api();
            api->tbl = table;
            module->onLoad(api, env);
        }

    } // namespace internal

    inline int Api::connectCompanion()
    {
        return tbl->connectCompanion != nullptr ? tbl->connectCompanion(tbl->impl) : -1;
    }
    inline int Api::getModuleDir()
    {
        return tbl->getModuleDir != nullptr ? tbl->getModuleDir(tbl->impl) : -1;
    }
    inline void Api::setOption(Option opt)
    {
        if (tbl->setOption != nullptr)
        {
            tbl->setOption(tbl->impl, opt);
        }
    }
    inline uint32_t Api::getFlags()
    {
        return tbl->getFlags != nullptr ? tbl->getFlags(tbl->impl) : 0;
    }
    inline void Api::hookJniNativeMethods(JNIEnv *env, const char *className, JNINativeMethod *methods, int numMethods)
    {
        if (tbl->hookJniNativeMethods != nullptr)
        {
            tbl->hookJniNativeMethods(env, className, methods, numMethods);
        }
    }
    inline void Api::pltHookRegister(const char *regex, const char *symbol, void *newFunc, void **oldFunc)
    {
        if (tbl->pltHookRegister != nullptr)
        {
            tbl->pltHookRegister(regex, symbol, newFunc, oldFunc);
        }
    }
    inline void Api::pltHookExclude(const char *regex, const char *symbol)
    {
        if (tbl->pltHookExclude != nullptr)
        {
            tbl->pltHookExclude(regex, symbol);
        }
    }
    inline bool Api::pltHookCommit()
    {
        return tbl->pltHookCommit != nullptr ? tbl->pltHookCommit() : false;
    }

} // namespace zygisk
