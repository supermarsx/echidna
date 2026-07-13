cmake_minimum_required(VERSION 3.22)

project(echidna-native LANGUAGES C CXX)

# Aggregate entry point for the two native libraries so a single
# configure/build produces both into ${CMAKE_BINARY_DIR}/lib:
#   * libech_dsp.so   (native/dsp)     — DSP engine + plugin loader
#   * libechidna.so   (native/zygisk)  — Zygisk module (dlopens libech_dsp.so)
#
# Used by tools/build_native_ndk.sh, which configures this directory once per
# ABI under a per-ABI binary dir (build/<abi>) with the NDK toolchain file:
#
#   cmake -S native -B build/<abi> -G Ninja \
#     -DCMAKE_TOOLCHAIN_FILE=$ANDROID_NDK/build/cmake/android.toolchain.cmake \
#     -DANDROID_ABI=<abi> -DANDROID_PLATFORM=android-26 -DBUILD_TESTING=OFF
#
# yielding build/<abi>/lib/libech_dsp.so and build/<abi>/lib/libechidna.so.
#
# The individual native/dsp and native/zygisk projects remain independently
# configurable (as CI/release.yml do today, host-side) — this file only adds a
# convenience super-build; it does not change their standalone behaviour.

set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)

# Cross-compiled/device builds must not pull in the host-only DSP test targets.
# Off by default here; the DSP subproject also guards tests with `NOT ANDROID`.
option(BUILD_TESTING "Build host-side native tests" OFF)

if(BUILD_TESTING AND NOT ANDROID)
  enable_testing()
endif()

add_subdirectory(dsp)
add_subdirectory(zygisk)
