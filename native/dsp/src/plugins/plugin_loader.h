#pragma once

#include <memory>
#include <mutex>
#include <string>
#include <vector>

#include "echidna/dsp/plugin_api.h"
#include "effects/effect_base.h"

namespace echidna::dsp::plugins {

struct PluginEffect {
  std::string identifier;
  std::string display_name;
  uint32_t version{0};
  uint32_t flags{ECHIDNA_PLUGIN_FLAG_NONE};
  effects::EffectProcessor *instance{nullptr};
  void (*destroy)(void *instance){nullptr};
};

class PluginLoader {
 public:
  PluginLoader();
  ~PluginLoader();

  PluginLoader(const PluginLoader &) = delete;
  PluginLoader &operator=(const PluginLoader &) = delete;

  void LoadFromDirectory(const std::string &directory);
  void PrepareAll(uint32_t sample_rate, uint32_t channels);
  void ResetAll();
  void ProcessAll(effects::ProcessContext &ctx);

  size_t plugin_count() const;

 private:
  struct ModuleHandle {
    void *library{nullptr};
    std::vector<PluginEffect> effects;
  };

  bool LoadPlugin(const std::string &path);
  bool VerifySignature(const std::string &binary_path,
                       const std::string &signature_path) const;
  void UnloadLocked();

  std::string signature_path_for(const std::string &binary_path) const;

  mutable std::mutex mutex_;
  std::vector<ModuleHandle> modules_;
  bool loaded_{false};
};

}  // namespace echidna::dsp::plugins

