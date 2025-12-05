#pragma once

/**
 * @file plugin_loader.h
 * @brief Dynamic plugin loader for DSP effects. Handles discovery, signature
 * verification, lifecycle and invocation of external effect plugins.
 */

#include <memory>
#include <mutex>
#include <string>
#include <vector>

#include "echidna/dsp/plugin_api.h"
#include "effects/effect_base.h"

namespace echidna::dsp::plugins
{

  /**
   * @brief Metadata and runtime instance for a loaded plugin effect.
   */
  struct PluginEffect
  {
    std::string identifier;
    std::string display_name;
    uint32_t version{0};
    uint32_t flags{ECHIDNA_PLUGIN_FLAG_NONE};
    effects::EffectProcessor *instance{nullptr};
    void (*destroy)(void *instance){nullptr};
  };

  /**
   * @brief Loads plugin modules from disk, prepares and dispatches calls to
   * plugin EffectProcessor implementations.
   */
  class PluginLoader
  {
  public:
    /** Default constructor. */
    PluginLoader();
    /** Destructor unloads any loaded plugins. */
    ~PluginLoader();

    PluginLoader(const PluginLoader &) = delete;
    PluginLoader &operator=(const PluginLoader &) = delete;

    /**
     * @brief Load all .so plugin modules from directory.
     * @param directory Filesystem path containing plugin shared objects.
     */
    void LoadFromDirectory(const std::string &directory);
    /**
     * @brief Prepare all loaded plugin instances for given sample rate and
     * channel count.
     */
    void PrepareAll(uint32_t sample_rate, uint32_t channels);
    /** Reset per-instance state for all loaded plugins. */
    void ResetAll();
    /**
     * @brief Run the `process` method of all enabled plugin effects.
     */
    void ProcessAll(effects::ProcessContext &ctx);

    /**
     * @brief Return number of plugin effect instances currently loaded.
     */
    size_t plugin_count() const;

  private:
    struct ModuleHandle
    {
      void *library{nullptr};
      std::vector<PluginEffect> effects;
    };

    /**
     * @brief Load a single plugin shared object by path. Internal, non-thread
     * safe helper.
     */
    bool LoadPlugin(const std::string &path);
    /**
     * @brief Verify a plugin binary using the provided signature file (ed25519)
     */
    bool VerifySignature(const std::string &binary_path,
                         const std::string &signature_path) const;
    /**
     * @brief Unload and cleanup all currently loaded plugin modules. Must be
     * called while holding mutex_ (internal only).
     */
    void UnloadLocked();

    /**
     * @brief Return the expected signature file path for a plugin binary.
     */
    std::string signature_path_for(const std::string &binary_path) const;

    mutable std::mutex mutex_;
    std::vector<ModuleHandle> modules_;
    bool loaded_{false};
  };

} // namespace echidna::dsp::plugins
