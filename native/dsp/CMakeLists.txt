cmake_minimum_required(VERSION 3.22)

project(echidna-dsp LANGUAGES C CXX)

set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)

if(NOT ANDROID)
  message(WARNING "Building DSP engine without Android toolchain; using host platform for smoke testing.")
endif()

set(ECHIDNA_DSP_SOURCES
    src/api.cpp
    src/engine.cpp
    src/config/preset_loader.cpp
    src/runtime/block_queue.cpp
    src/runtime/simd.cpp
    src/effects/effect_base.cpp
    src/effects/gate_processor.cpp
    src/effects/parametric_eq.cpp
    src/effects/compressor.cpp
    src/effects/pitch_shifter.cpp
    src/effects/formant_shifter.cpp
    src/effects/auto_tune.cpp
    src/effects/reverb.cpp
    src/effects/mix_bus.cpp
    src/plugins/plugin_loader.cpp)

add_library(ech_dsp SHARED ${ECHIDNA_DSP_SOURCES})

set_target_properties(ech_dsp PROPERTIES
    OUTPUT_NAME ech_dsp)

if(WIN32)
  set_target_properties(ech_dsp PROPERTIES
      RUNTIME_OUTPUT_DIRECTORY ${CMAKE_BINARY_DIR}/$<CONFIG>
      ARCHIVE_OUTPUT_DIRECTORY ${CMAKE_BINARY_DIR}/$<CONFIG>
      LIBRARY_OUTPUT_DIRECTORY ${CMAKE_BINARY_DIR}/$<CONFIG>)
  if(MSVC)
    set_target_properties(ech_dsp PROPERTIES WINDOWS_EXPORT_ALL_SYMBOLS ON)
  endif()
else()
  # Host and per-ABI NDK builds share the same in-tree layout: the library
  # always lands at ${CMAKE_BINARY_DIR}/lib. For NDK cross-compiles the ABI
  # separation comes from a per-ABI *binary* directory (build/<abi>), driven by
  # tools/build_native_ndk.sh, so the on-device path is:
  #     build/<abi>/lib/libech_dsp.so
  # (Do NOT append ${ANDROID_ABI} to the output dir here — that would nest it as
  # build/<abi>/lib/<abi>/ and break the layout the Magisk packager expects.)
  set_target_properties(ech_dsp PROPERTIES
      LIBRARY_OUTPUT_DIRECTORY ${CMAKE_BINARY_DIR}/lib)
  if(ANDROID AND DEFINED ANDROID_ABI)
    # ABI-aware branch — dead on a host build (ANDROID unset) but LIVE under the
    # NDK toolchain file, which sets ANDROID/ANDROID_ABI. Tag the target with its
    # ABI for on-device diagnostics; keep the output at the per-ABI build root.
    target_compile_definitions(ech_dsp PRIVATE ECHIDNA_TARGET_ABI="${ANDROID_ABI}")
    message(STATUS "Echidna DSP: Android ABI ${ANDROID_ABI} -> ${CMAKE_BINARY_DIR}/lib/libech_dsp.so")
  endif()
endif()

target_include_directories(ech_dsp
    PUBLIC
        $<BUILD_INTERFACE:${CMAKE_CURRENT_SOURCE_DIR}/include>
        $<BUILD_INTERFACE:${CMAKE_CURRENT_SOURCE_DIR}/../include>
        $<INSTALL_INTERFACE:include>
    PRIVATE
        ${CMAKE_CURRENT_SOURCE_DIR}/src)

if(CMAKE_SYSTEM_PROCESSOR MATCHES "(arm64|aarch64)")
  target_compile_definitions(ech_dsp PRIVATE ECHIDNA_DSP_HAS_NEON=1)
elseif(CMAKE_SYSTEM_PROCESSOR MATCHES "(x86_64|AMD64)")
  target_compile_definitions(ech_dsp PRIVATE ECHIDNA_DSP_HAS_AVX=1)
endif()


# --- Ed25519 plugin-signature crypto (BoringSSL / OpenSSL) ---
# src/plugins/plugin_loader.cpp only compiles the real EVP-based signature
# verification when ECHIDNA_HAS_BORINGSSL is defined; otherwise VerifySignature()
# returns false (fail-closed) and plugin loading is effectively disabled. The NDK
# sysroot ships NO libcrypto, so an on-device (Android) build MUST supply one or
# plugin verification is silently off (t1-e2 Major 7 / t1-e5 Blocker 3).
#
# Resolution strategy, in priority order (first hit wins):
#   1. -DECHIDNA_BORINGSSL_ROOT=<dir> : a prebuilt BoringSSL/OpenSSL install
#      (expects <dir>/lib/libcrypto.{a,so} + <dir>/include). Preferred for
#      reproducible, offline docker/CI builds — nothing is fetched.
#   2. find_library(crypto) : a toolchain/system-provided libcrypto (host dev
#      machines, or an NDK/prefix that already carries one).
#   3. FetchContent BoringSSL, pinned + cross-compiled with the active toolchain.
#      Only attempted for Android builds (ANDROID set) and only when
#      ECHIDNA_FETCH_BORINGSSL is ON (default). Requires network at configure
#      time. NOTHING is vendored into the repo — the source is fetched into the
#      build tree. Repo/tag are overridable so a docker image can pin/mirror them.
option(ECHIDNA_FETCH_BORINGSSL
    "For Android builds, FetchContent+cross-compile BoringSSL when no libcrypto is found" ON)
set(ECHIDNA_BORINGSSL_ROOT "" CACHE PATH
    "Path to a prebuilt BoringSSL/OpenSSL install (lib/ + include/) for plugin signature verify")
set(ECHIDNA_BORINGSSL_GIT_REPOSITORY "https://github.com/google/boringssl.git" CACHE STRING
    "BoringSSL git repository used by the FetchContent fallback")
set(ECHIDNA_BORINGSSL_GIT_TAG "0.20240913.0" CACHE STRING
    "Pinned BoringSSL tag/commit for the FetchContent fallback (override to a vetted pin)")

set(_echidna_crypto_linked OFF)

# 1. Explicit prebuilt root.
if(NOT _echidna_crypto_linked AND ECHIDNA_BORINGSSL_ROOT)
  find_library(ECHIDNA_PREBUILT_CRYPTO crypto
      PATHS "${ECHIDNA_BORINGSSL_ROOT}/lib" "${ECHIDNA_BORINGSSL_ROOT}/lib64"
      NO_DEFAULT_PATH)
  if(ECHIDNA_PREBUILT_CRYPTO)
    target_include_directories(ech_dsp PRIVATE "${ECHIDNA_BORINGSSL_ROOT}/include")
    target_link_libraries(ech_dsp PRIVATE ${ECHIDNA_PREBUILT_CRYPTO})
    target_compile_definitions(ech_dsp PRIVATE ECHIDNA_HAS_BORINGSSL=1)
    set(_echidna_crypto_linked ON)
    message(STATUS "Echidna DSP: plugin verify via prebuilt BoringSSL at ${ECHIDNA_BORINGSSL_ROOT}")
  else()
    message(WARNING "Echidna DSP: ECHIDNA_BORINGSSL_ROOT set but no libcrypto under it")
  endif()
endif()

# 2. Toolchain / system libcrypto (host builds keep their previous behaviour).
if(NOT _echidna_crypto_linked)
  find_library(CRYPTO_LIB crypto)
  if(CRYPTO_LIB)
    target_link_libraries(ech_dsp PRIVATE ${CRYPTO_LIB})
    target_compile_definitions(ech_dsp PRIVATE ECHIDNA_HAS_BORINGSSL=1)
    set(_echidna_crypto_linked ON)
    message(STATUS "Echidna DSP: plugin verify via system libcrypto ${CRYPTO_LIB}")
  endif()
endif()

# 3. FetchContent BoringSSL — Android on-device builds only.
if(NOT _echidna_crypto_linked AND ANDROID AND ECHIDNA_FETCH_BORINGSSL)
  include(FetchContent)
  # Build only BoringSSL's static crypto lib; skip its tests/tools.
  #
  # Hygiene (t2-e24): BoringSSL adds its own test/tool targets (crypto_test,
  # pki_test, ssl_test, the `bssl` tool, GoogleTest — ~670 targets) and does NOT
  # gate them on the standard BUILD_TESTING, so a plain FetchContent pulls them
  # all into the default `all` target and roughly triples the release
  # cross-compile time/output. Marking the whole sub-build EXCLUDE_FROM_ALL means
  # only the `crypto` target that ech_dsp actually links (and its transitive
  # deps) is built — the test/tool targets are dropped from `all` and never
  # compiled during the per-ABI release build. BUILD_SHARED_LIBS/BUILD_TESTING
  # are still forced OFF as belt-and-braces for any target that does honour them.
  set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
  set(BUILD_TESTING OFF CACHE BOOL "" FORCE)
  # EXCLUDE_FROM_ALL as a FetchContent_Declare keyword requires CMake >= 3.28
  # (the build hosts — docker native-build cmake 3.30, dev host 4.x — satisfy
  # this). Fall back gracefully on older CMake so configure never hard-fails.
  if(CMAKE_VERSION VERSION_GREATER_EQUAL "3.28")
    FetchContent_Declare(boringssl
        GIT_REPOSITORY "${ECHIDNA_BORINGSSL_GIT_REPOSITORY}"
        GIT_TAG "${ECHIDNA_BORINGSSL_GIT_TAG}"
        GIT_SHALLOW ON
        EXCLUDE_FROM_ALL)
  else()
    message(WARNING
        "Echidna DSP: CMake < 3.28 cannot EXCLUDE_FROM_ALL the BoringSSL "
        "sub-build; its ~670 test/tool targets will build with `all`. "
        "Build the explicit targets (--target ech_dsp) to avoid them.")
    FetchContent_Declare(boringssl
        GIT_REPOSITORY "${ECHIDNA_BORINGSSL_GIT_REPOSITORY}"
        GIT_TAG "${ECHIDNA_BORINGSSL_GIT_TAG}"
        GIT_SHALLOW ON)
  endif()
  FetchContent_MakeAvailable(boringssl)
  # BoringSSL exposes a target named 'crypto'.
  target_link_libraries(ech_dsp PRIVATE crypto)
  target_compile_definitions(ech_dsp PRIVATE ECHIDNA_HAS_BORINGSSL=1)
  set(_echidna_crypto_linked ON)
  message(STATUS "Echidna DSP: plugin verify via FetchContent BoringSSL "
                 "(${ECHIDNA_BORINGSSL_GIT_TAG}) for ${ANDROID_ABI}")
endif()

if(NOT _echidna_crypto_linked)
  message(WARNING
      "Echidna DSP: no libcrypto available -> Ed25519 plugin signature verification "
      "is DISABLED (fail-closed, plugins will not load). Provide -DECHIDNA_BORINGSSL_ROOT "
      "or keep ECHIDNA_FETCH_BORINGSSL=ON for Android builds.")
endif()

install(TARGETS ech_dsp
    LIBRARY DESTINATION lib)
install(DIRECTORY include/ DESTINATION include)

# Host-only test targets: never cross-compile them for an NDK/device build
# (they are host executables, not device artifacts). tools/build_native_ndk.sh
# also passes -DBUILD_TESTING=OFF; the NOT ANDROID guard is a belt-and-braces
# so an aggregated NDK configure can't accidentally pull them in.
if(BUILD_TESTING AND NOT ANDROID)
  add_subdirectory(tests)
endif()
