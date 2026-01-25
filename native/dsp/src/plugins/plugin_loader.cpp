#include "plugins/plugin_loader.h"

/**
 * @file plugin_loader.cpp
 * @brief Implementation of the PluginLoader, discovery helpers and
 * cryptographic verification utilities used to safely load plugin shared
 * objects.
 */

#include <sys/stat.h>

#ifdef _WIN32
#include <windows.h>
#else
#include <dlfcn.h>
#endif

#include <algorithm>
#include <array>
#include <cctype>
#include <cerrno>
#include <cstring>
#include <dirent.h>
#include <fstream>
#include <memory>
#include <string>
#include <utility>
#include <vector>

#ifdef ECHIDNA_HAS_BORINGSSL
#include <openssl/evp.h>
#endif
namespace
{
  constexpr std::array<const char *, 1> kTrustedKeys = {
      "e6f05a8f7e2c4bfa3a3d28a62a6f68fa4b5379f16e2e63ef1c6d3ccad1f7b010"};

  /**
   * @brief Return true when a regular file exists at path.
   */
  bool FileExists(const std::string &path)
  {
    struct stat st;
    return stat(path.c_str(), &st) == 0 && S_ISREG(st.st_mode);
  }

#ifdef ECHIDNA_HAS_BORINGSSL
  /**
   * @brief Verify an ed25519 signature using OpenSSL/BoringSSL helpers.
   *
   * (Available only when ECHIDNA_HAS_BORINGSSL is set.)
   */
  bool VerifyEd25519(const std::vector<uint8_t> &payload,
                     const std::vector<uint8_t> &signature,
                     const std::array<uint8_t, 32> &public_key)
  {
    EVP_PKEY *key = EVP_PKEY_new_raw_public_key(EVP_PKEY_ED25519,
                                                nullptr,
                                                public_key.data(),
                                                public_key.size());
    if (!key)
    {
      return false;
    }
    EVP_MD_CTX *ctx = EVP_MD_CTX_new();
    if (!ctx)
    {
      EVP_PKEY_free(key);
      return false;
    }
    bool ok = EVP_DigestVerifyInit(ctx, nullptr, nullptr, nullptr, key) == 1 &&
              EVP_DigestVerify(ctx,
                               signature.data(),
                               signature.size(),
                               payload.data(),
                               payload.size()) == 1;
    EVP_MD_CTX_free(ctx);
    EVP_PKEY_free(key);
    return ok;
  }
#endif

  /**
   * @brief Read a file into a byte buffer. Returns empty vector on failure.
   */
  std::vector<uint8_t> ReadFile(const std::string &path)
  {
    std::ifstream file(path, std::ios::binary);
    if (!file.is_open())
    {
      return {};
    }
    file.seekg(0, std::ios::end);
    const std::streampos end = file.tellg();
    if (end <= 0)
    {
      return {};
    }
    file.seekg(0, std::ios::beg);
    std::vector<uint8_t> buffer(static_cast<size_t>(end));
    file.read(reinterpret_cast<char *>(buffer.data()), buffer.size());
    return buffer;
  }

  /**
   * @brief Convert a single hex digit to its numeric value.
   */
  uint8_t FromHex(char c)
  {
    if (c >= '0' && c <= '9')
    {
      return static_cast<uint8_t>(c - '0');
    }
    if (c >= 'a' && c <= 'f')
    {
      return static_cast<uint8_t>(c - 'a' + 10);
    }
    if (c >= 'A' && c <= 'F')
    {
      return static_cast<uint8_t>(c - 'A' + 10);
    }
    return 0;
  }

  /**
   * @brief Decode a hex string into raw bytes. Expects filtered input of
   * length 128 (64 bytes encoded) and will return a vector of 64 raw bytes.
   */
  std::vector<uint8_t> DecodeHex(const std::vector<uint8_t> &data)
  {
    std::vector<uint8_t> filtered;
    filtered.reserve(data.size());
    for (uint8_t byte : data)
    {
      const char c = static_cast<char>(byte);
      if (std::isxdigit(static_cast<unsigned char>(c)))
      {
        filtered.push_back(static_cast<uint8_t>(std::tolower(static_cast<unsigned char>(c))));
      }
    }
    if (filtered.size() != 128)
    {
      return {};
    }
    std::vector<uint8_t> decoded(64);
    for (size_t i = 0; i < decoded.size(); ++i)
    {
      const uint8_t high = FromHex(static_cast<char>(filtered[i * 2]));
      const uint8_t low = FromHex(static_cast<char>(filtered[i * 2 + 1]));
      decoded[i] = static_cast<uint8_t>((high << 4) | low);
    }
    return decoded;
  }

  /**
   * @brief Utility to check whether a string `value` ends with `suffix`.
   */
  bool HasSuffix(const std::string &value, const std::string &suffix)
  {
    if (value.length() < suffix.length())
    {
      return false;
    }
    return std::equal(suffix.rbegin(), suffix.rend(), value.rbegin());
  }

  void *OpenLibrary(const char *path)
  {
#ifdef _WIN32
    return static_cast<void *>(LoadLibraryA(path));
#else
    return dlopen(path, RTLD_NOW | RTLD_LOCAL);
#endif
  }

  void *ResolveSymbol(void *handle, const char *name)
  {
    if (!handle)
    {
      return nullptr;
    }
#ifdef _WIN32
    return reinterpret_cast<void *>(GetProcAddress(static_cast<HMODULE>(handle), name));
#else
    return dlsym(handle, name);
#endif
  }

  void CloseLibrary(void *handle)
  {
    if (!handle)
    {
      return;
    }
#ifdef _WIN32
    FreeLibrary(static_cast<HMODULE>(handle));
#else
    dlclose(handle);
#endif
  }

} // namespace

namespace echidna::dsp::plugins
{

  /** Default constructor. */
  PluginLoader::PluginLoader() = default;

  /** Destructor: ensure modules are unloaded. */
  PluginLoader::~PluginLoader() { UnloadLocked(); }

  /**
   * @brief Unload library handles and destroy instantiated plugin objects.
   */
  void PluginLoader::UnloadLocked()
  {
    std::scoped_lock lock(mutex_);
    for (auto &module : modules_)
    {
      for (auto &effect : module.effects)
      {
        if (effect.instance && effect.destroy)
        {
          effect.destroy(effect.instance);
        }
        effect.instance = nullptr;
      }
      if (module.library)
      {
        CloseLibrary(module.library);
        module.library = nullptr;
      }
    }
    modules_.clear();
    loaded_ = false;
  }

  /**
   * @brief Enumerate .so files and attempt to load validated plugin modules.
   */
  void PluginLoader::LoadFromDirectory(const std::string &directory)
  {
    std::scoped_lock lock(mutex_);
    if (loaded_)
    {
      return;
    }
    DIR *dir = opendir(directory.c_str());
    if (!dir)
    {
      loaded_ = true;
      return;
    }
    while (auto *entry = readdir(dir))
    {
      if (!entry->d_name)
      {
        continue;
      }
      std::string name(entry->d_name);
      if (name == "." || name == "..")
      {
        continue;
      }
      if (!HasSuffix(name, ".so"))
      {
        continue;
      }
      std::string full_path = directory + "/" + name;
      LoadPlugin(full_path);
    }
    closedir(dir);
    loaded_ = true;
  }

  /**
   * @brief Load a single plugin module from `path` and instantiate contained
   * effect instances if the module is valid and trusted.
   */
  bool PluginLoader::LoadPlugin(const std::string &path)
  {
    const std::string signature_path = signature_path_for(path);
    if (!FileExists(signature_path))
    {
      return false;
    }
    if (!VerifySignature(path, signature_path))
    {
      return false;
    }
    void *handle = OpenLibrary(path.c_str());
    if (!handle)
    {
      return false;
    }
    auto *registration = reinterpret_cast<echidna_plugin_registration_fn>(
        ResolveSymbol(handle, "echidna_get_plugin_module"));
    if (!registration)
    {
      CloseLibrary(handle);
      return false;
    }
    const echidna_plugin_module_t *module = registration();
    if (!module || module->abi_version != ECHIDNA_DSP_PLUGIN_ABI_VERSION ||
        !module->descriptors || module->descriptor_count == 0)
    {
      CloseLibrary(handle);
      return false;
    }

    ModuleHandle module_handle;
    module_handle.library = handle;
    module_handle.effects.reserve(module->descriptor_count);
    for (size_t i = 0; i < module->descriptor_count; ++i)
    {
      const auto &descriptor = module->descriptors[i];
      if (!descriptor.identifier || !descriptor.create || !descriptor.destroy)
      {
        continue;
      }
      auto *raw = static_cast<effects::EffectProcessor *>(descriptor.create());
      if (!raw)
      {
        continue;
      }
      raw->set_enabled((descriptor.flags & ECHIDNA_PLUGIN_FLAG_DEFAULT_ENABLED) != 0);

      PluginEffect effect;
      effect.identifier = descriptor.identifier;
      effect.display_name = descriptor.display_name ? descriptor.display_name : descriptor.identifier;
      effect.version = descriptor.version;
      effect.flags = descriptor.flags;
      effect.destroy = descriptor.destroy;
      effect.instance = raw;

      module_handle.effects.emplace_back(std::move(effect));
    }
    if (!module_handle.effects.empty())
    {
      modules_.emplace_back(std::move(module_handle));
      return true;
    }
    CloseLibrary(handle);
    return false;
  }

  /**
   * @brief Prepare all plugins by calling their prepare() methods.
   */
  void PluginLoader::PrepareAll(uint32_t sample_rate, uint32_t channels)
  {
    std::scoped_lock lock(mutex_);
    for (auto &module : modules_)
    {
      for (auto &effect : module.effects)
      {
        if (effect.instance)
        {
          effect.instance->prepare(sample_rate, channels);
        }
      }
    }
  }

  /**
   * @brief Reset every plugin instance state.
   */
  void PluginLoader::ResetAll()
  {
    std::scoped_lock lock(mutex_);
    for (auto &module : modules_)
    {
      for (auto &effect : module.effects)
      {
        if (effect.instance)
        {
          effect.instance->reset();
        }
      }
    }
  }

  /**
   * @brief Call process() on all enabled plugin instances.
   */
  void PluginLoader::ProcessAll(effects::ProcessContext &ctx)
  {
    std::scoped_lock lock(mutex_);
    for (auto &module : modules_)
    {
      for (auto &effect : module.effects)
      {
        if (effect.instance && effect.instance->enabled())
        {
          effect.instance->process(ctx);
        }
      }
    }
  }

  /**
   * @brief Return a count of all loaded plugin effect descriptors.
   */
  size_t PluginLoader::plugin_count() const
  {
    std::scoped_lock lock(mutex_);
    size_t count = 0;
    for (const auto &module : modules_)
    {
      count += module.effects.size();
    }
    return count;
  }

  /**
   * @brief Compute the signature filename for a module binary.
   */
  std::string PluginLoader::signature_path_for(const std::string &binary_path) const
  {
    return binary_path + ".sig";
  }

  /**
   * @brief Verify payload with signature and the compiled set of trusted
   * public keys. Returns true for valid signatures.
   */
  bool PluginLoader::VerifySignature(const std::string &binary_path,
                                     const std::string &signature_path) const
  {
    auto payload = ReadFile(binary_path);
    auto signature_bytes = ReadFile(signature_path);
    if (payload.empty() || signature_bytes.empty())
    {
      return false;
    }

    std::vector<uint8_t> signature = signature_bytes;
    if (signature.size() != 64)
    {
      signature = DecodeHex(signature_bytes);
    }

#ifdef ECHIDNA_HAS_BORINGSSL
    if (signature.size() != 64)
    {
      return false;
    }
    for (const char *hex_key : kTrustedKeys)
    {
      std::array<uint8_t, 32> public_key{};
      for (size_t i = 0; i < public_key.size(); ++i)
      {
        char high = hex_key[i * 2];
        char low = hex_key[i * 2 + 1];
        uint8_t value = 0;
        if (high >= '0' && high <= '9')
        {
          value = static_cast<uint8_t>(high - '0') << 4;
        }
        else if (high >= 'a' && high <= 'f')
        {
          value = static_cast<uint8_t>(high - 'a' + 10) << 4;
        }
        else if (high >= 'A' && high <= 'F')
        {
          value = static_cast<uint8_t>(high - 'A' + 10) << 4;
        }
        else
        {
          value = 0;
        }
        if (low >= '0' && low <= '9')
        {
          value |= static_cast<uint8_t>(low - '0');
        }
        else if (low >= 'a' && low <= 'f')
        {
          value |= static_cast<uint8_t>(low - 'a' + 10);
        }
        else if (low >= 'A' && low <= 'F')
        {
          value |= static_cast<uint8_t>(low - 'A' + 10);
        }
        public_key[i] = value;
      }
      if (VerifyEd25519(payload, signature, public_key))
      {
        return true;
      }
    }
    return false;
#else
    (void)binary_path;
    (void)signature_path;
    (void)payload;
    (void)signature_bytes;
    (void)signature;
    return false;
#endif
  }

} // namespace echidna::dsp::plugins
