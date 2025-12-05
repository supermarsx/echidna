#pragma once

/**
 * @file plugin_api.h
 * @brief ABI used by external plugin modules to describe their provided
 * effects and create/destroy functions. Plugins must expose a
 * `echidna_get_plugin_module()` symbol returning a module descriptor.
 */

#include <stddef.h>
#include <stdint.h>

#ifdef __cplusplus
extern "C"
{
#endif

#define ECHIDNA_DSP_PLUGIN_ABI_VERSION 1U

  typedef struct echidna_plugin_descriptor
  {
    const char *identifier;
    const char *display_name;
    uint32_t version;
    uint32_t flags;
    void *(*create)(void);
    void (*destroy)(void *instance);
  } echidna_plugin_descriptor_t;

  typedef struct echidna_plugin_module
  {
    uint32_t abi_version;
    const echidna_plugin_descriptor_t *descriptors;
    size_t descriptor_count;
  } echidna_plugin_module_t;

  typedef const echidna_plugin_module_t *(*echidna_plugin_registration_fn)(void);

  typedef enum echidna_plugin_flags
  {
    ECHIDNA_PLUGIN_FLAG_NONE = 0,
    ECHIDNA_PLUGIN_FLAG_DEFAULT_ENABLED = 1U << 0,
  } echidna_plugin_flags_t;

  const echidna_plugin_module_t *echidna_get_plugin_module(void);

#ifdef __cplusplus
}
#endif
