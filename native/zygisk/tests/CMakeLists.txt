cmake_minimum_required(VERSION 3.22)

# Host-runnable smoke harness for the Zygisk audio-capture -> DSP routing path.
# It drives the same int16<->float conversion + "not-allowed passes through"
# guard the on-device hook performs (see src/hooks/audiorecord_hook_manager.cpp
# and src/api.cpp), calling the real DSP block underneath. The device-only
# injection boundary (symbol resolve + inline patch, live victim process) is
# stubbed: the harness synthesises the captured PCM instead of a hook delivering
# it. It therefore links only the DSP library (ech_dsp) — never the Zygisk
# module (libechidna.so), which needs an Android/POSIX runtime and a live
# process to hook and cannot execute on a host.
#
# ech_dsp is defined by the sibling native/dsp project, so this target is only
# available in the aggregated super-build (cmake -S native ...), where dsp is
# added before zygisk. Guarded with TARGET ech_dsp so a standalone
# `cmake -S native/zygisk` configure still succeeds (the smoke test is simply
# skipped there). CI's smoke-tests job (t2-e15) builds it via the super-build:
#   cmake -S native -B <dir> -DBUILD_TESTING=ON
#   cmake --build <dir> --target zygisk_dsp_smoke_test
#   ctest --test-dir <dir>
if(NOT TARGET ech_dsp)
  message(STATUS
      "Echidna Zygisk tests: ech_dsp target not present (standalone zygisk "
      "configure) -> skipping host smoke harness. Configure -S native to build it.")
  return()
endif()

project(echidna-zygisk-tests LANGUAGES CXX)

add_executable(zygisk_dsp_smoke_test dsp_smoke_test.cpp)
target_link_libraries(zygisk_dsp_smoke_test PRIVATE ech_dsp)
# Only the DSP public C API header is needed (echidna/dsp/api.h under native/dsp).
target_include_directories(zygisk_dsp_smoke_test
    PRIVATE
        ${CMAKE_CURRENT_SOURCE_DIR}/../../dsp/include)
target_compile_features(zygisk_dsp_smoke_test PRIVATE cxx_std_20)

add_executable(capture_buffer_router_test
    capture_buffer_router_test.cpp
    ../src/hooks/capture_buffer_router.cpp)
target_link_libraries(capture_buffer_router_test PRIVATE ech_dsp)
target_include_directories(capture_buffer_router_test
    PRIVATE
        ${CMAKE_CURRENT_SOURCE_DIR}/../../dsp/include
        ${CMAKE_CURRENT_SOURCE_DIR}/../src
        ${CMAKE_CURRENT_SOURCE_DIR}/../include
        ${CMAKE_CURRENT_SOURCE_DIR}/../../include)
target_compile_features(capture_buffer_router_test PRIVATE cxx_std_20)

if(NOT WIN32)
  add_executable(config_shared_memory_test
      config_shared_memory_test.cpp
      ../src/utils/config_shared_memory.cpp)
  target_include_directories(config_shared_memory_test
      PRIVATE
          ${CMAKE_CURRENT_SOURCE_DIR}/../src)
  target_compile_features(config_shared_memory_test PRIVATE cxx_std_20)
endif()

# Emit into the top-level build dir so a `./build/<...>/zygisk_dsp_smoke_test`
# invocation from CI finds it next to the other native test binaries.
set(ECHIDNA_ZYGISK_TEST_TARGETS
    zygisk_dsp_smoke_test
    capture_buffer_router_test)
if(NOT WIN32)
  list(APPEND ECHIDNA_ZYGISK_TEST_TARGETS config_shared_memory_test)
endif()

set_target_properties(${ECHIDNA_ZYGISK_TEST_TARGETS} PROPERTIES
    RUNTIME_OUTPUT_DIRECTORY ${CMAKE_BINARY_DIR})

enable_testing()
add_test(NAME zygisk_dsp_smoke_test COMMAND zygisk_dsp_smoke_test)
add_test(NAME capture_buffer_router_test COMMAND capture_buffer_router_test)
if(NOT WIN32)
  add_test(NAME config_shared_memory_test COMMAND config_shared_memory_test)
endif()
