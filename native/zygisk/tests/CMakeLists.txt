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

find_package(Threads REQUIRED)

add_executable(zygisk_dsp_smoke_test dsp_smoke_test.cpp)
target_link_libraries(zygisk_dsp_smoke_test PRIVATE ech_dsp)
# Only the DSP public C API header is needed (echidna/dsp/api.h under native/dsp).
target_include_directories(zygisk_dsp_smoke_test
    PRIVATE
        ${CMAKE_CURRENT_SOURCE_DIR}/../../dsp/include)
target_compile_features(zygisk_dsp_smoke_test PRIVATE cxx_std_20)

add_executable(capture_buffer_router_test
    capture_buffer_router_test.cpp
    ../src/audio/pcm_buffer_processor.cpp
    ../src/hooks/capture_buffer_router.cpp)
target_link_libraries(capture_buffer_router_test PRIVATE ech_dsp)
target_include_directories(capture_buffer_router_test
    PRIVATE
        ${CMAKE_CURRENT_SOURCE_DIR}/../../dsp/include
        ${CMAKE_CURRENT_SOURCE_DIR}/../src
        ${CMAKE_CURRENT_SOURCE_DIR}/../include
        ${CMAKE_CURRENT_SOURCE_DIR}/../../include)
target_compile_features(capture_buffer_router_test PRIVATE cxx_std_20)

add_executable(aarch64_instruction_test aarch64_instruction_test.cpp)
target_include_directories(aarch64_instruction_test
    PRIVATE ${CMAKE_CURRENT_SOURCE_DIR}/../src)
target_compile_features(aarch64_instruction_test PRIVATE cxx_std_20)

add_executable(audiohal_contract_test audiohal_contract_test.cpp)
target_include_directories(audiohal_contract_test
    PRIVATE
        ${CMAKE_CURRENT_SOURCE_DIR}/../src
        ${CMAKE_CURRENT_SOURCE_DIR}/../../include)
target_compile_features(audiohal_contract_test PRIVATE cxx_std_20)

add_executable(aaudio_callback_registry_test
    aaudio_callback_registry_test.cpp
    ../src/hooks/aaudio_callback_registry.cpp)
target_include_directories(aaudio_callback_registry_test
    PRIVATE ${CMAKE_CURRENT_SOURCE_DIR}/../src)
target_compile_features(aaudio_callback_registry_test PRIVATE cxx_std_20)

add_executable(opensl_pcm_contract_test opensl_pcm_contract_test.cpp)
target_include_directories(opensl_pcm_contract_test
    PRIVATE
        ${CMAKE_CURRENT_SOURCE_DIR}/../src
        ${CMAKE_CURRENT_SOURCE_DIR}/../include
        ${CMAKE_CURRENT_SOURCE_DIR}/../../include)
target_compile_features(opensl_pcm_contract_test PRIVATE cxx_std_20)

add_executable(opensl_buffer_fifo_test opensl_buffer_fifo_test.cpp)
target_include_directories(opensl_buffer_fifo_test
    PRIVATE ${CMAKE_CURRENT_SOURCE_DIR}/../src)
target_compile_features(opensl_buffer_fifo_test PRIVATE cxx_std_20)

add_executable(opensl_lifecycle_test
    opensl_lifecycle_test.cpp
    ../src/audio/pcm_buffer_processor.cpp
    ../src/hooks/capture_buffer_router.cpp)
target_include_directories(opensl_lifecycle_test
    PRIVATE
        ${CMAKE_CURRENT_SOURCE_DIR}/fake_sles
        ${CMAKE_CURRENT_SOURCE_DIR}/../src
        ${CMAKE_CURRENT_SOURCE_DIR}/../include
        ${CMAKE_CURRENT_SOURCE_DIR}/../../include)
target_compile_features(opensl_lifecycle_test PRIVATE cxx_std_20)

add_executable(tinyalsa_contract_test tinyalsa_contract_test.cpp)
target_include_directories(tinyalsa_contract_test
    PRIVATE
        ${CMAKE_CURRENT_SOURCE_DIR}/../src
        ${CMAKE_CURRENT_SOURCE_DIR}/../include
        ${CMAKE_CURRENT_SOURCE_DIR}/../../include)
target_compile_features(tinyalsa_contract_test PRIVATE cxx_std_20)

add_executable(capture_route_reachability_test
    capture_route_reachability_test.cpp
    ../src/hooks/audioflinger_hook_manager.cpp
    ../src/hooks/audiohal_hook_manager.cpp)
target_include_directories(capture_route_reachability_test
    PRIVATE
        ${CMAKE_CURRENT_SOURCE_DIR}/../src)
target_compile_features(capture_route_reachability_test PRIVATE cxx_std_20)

add_executable(profile_sync_protocol_test
    profile_sync_protocol_test.cpp
    ../src/runtime/profile_sync_protocol.cpp)
target_include_directories(profile_sync_protocol_test
    PRIVATE ${CMAKE_CURRENT_SOURCE_DIR}/../src)
target_compile_features(profile_sync_protocol_test PRIVATE cxx_std_20)

add_executable(activation_gate_test activation_gate_test.cpp)
target_link_libraries(activation_gate_test PRIVATE Threads::Threads)
target_include_directories(activation_gate_test
    PRIVATE ${CMAKE_CURRENT_SOURCE_DIR}/../src)
target_compile_features(activation_gate_test PRIVATE cxx_std_20)

add_executable(process_utils_test
    process_utils_test.cpp
    ../src/utils/process_utils.cpp)
target_include_directories(process_utils_test
    PRIVATE ${CMAKE_CURRENT_SOURCE_DIR}/../src)
target_compile_features(process_utils_test PRIVATE cxx_std_20)

add_executable(telemetry_accumulator_test
    telemetry_accumulator_test.cpp
    ../src/utils/telemetry_accumulator.cpp)
target_include_directories(telemetry_accumulator_test
    PRIVATE ${CMAKE_CURRENT_SOURCE_DIR}/../src)
target_compile_features(telemetry_accumulator_test PRIVATE cxx_std_20)

if(NOT WIN32)
  add_executable(config_shared_memory_test
      config_shared_memory_test.cpp
      ../src/state/shared_state.cpp
      ../src/utils/config_shared_memory.cpp
      ../src/utils/telemetry_accumulator.cpp)
  target_include_directories(config_shared_memory_test
      PRIVATE
          ${CMAKE_CURRENT_SOURCE_DIR}/../src)
  target_compile_features(config_shared_memory_test PRIVATE cxx_std_20)
endif()

if(CMAKE_SYSTEM_NAME STREQUAL "Linux" AND NOT ANDROID)
  add_executable(profile_sync_server_test
      profile_sync_server_test.cpp
      ../src/runtime/profile_sync_server.cpp
      ../src/runtime/profile_sync_protocol.cpp
      ../src/state/shared_state.cpp
      ../src/utils/config_shared_memory.cpp
      ../src/utils/telemetry_accumulator.cpp
      ../src/runtime/telemetry_socket_exporter.cpp
      ../src/utils/process_utils.cpp)
  target_link_libraries(profile_sync_server_test PRIVATE Threads::Threads)
  target_include_directories(profile_sync_server_test
      PRIVATE
          ${CMAKE_CURRENT_SOURCE_DIR}/../src
          ${CMAKE_CURRENT_SOURCE_DIR}/../include
          ${CMAKE_CURRENT_SOURCE_DIR}/../../include)
  target_compile_features(profile_sync_server_test PRIVATE cxx_std_20)
endif()

if(NOT WIN32)
  add_executable(telemetry_socket_exporter_test
      telemetry_socket_exporter_test.cpp
      ../src/runtime/telemetry_socket_exporter.cpp
      ../src/utils/telemetry_accumulator.cpp)
  target_include_directories(telemetry_socket_exporter_test
      PRIVATE ${CMAKE_CURRENT_SOURCE_DIR}/../src)
  target_compile_features(telemetry_socket_exporter_test PRIVATE cxx_std_20)
endif()

# Emit into the top-level build dir so a `./build/<...>/zygisk_dsp_smoke_test`
# invocation from CI finds it next to the other native test binaries.
set(ECHIDNA_ZYGISK_TEST_TARGETS
    zygisk_dsp_smoke_test
    capture_buffer_router_test
    aarch64_instruction_test
    audiohal_contract_test
    aaudio_callback_registry_test
    opensl_pcm_contract_test
    opensl_buffer_fifo_test
    opensl_lifecycle_test
    tinyalsa_contract_test
    capture_route_reachability_test
    profile_sync_protocol_test
    activation_gate_test
    process_utils_test
    telemetry_accumulator_test)
if(NOT WIN32)
  list(APPEND ECHIDNA_ZYGISK_TEST_TARGETS
      config_shared_memory_test
      telemetry_socket_exporter_test)
endif()
if(CMAKE_SYSTEM_NAME STREQUAL "Linux" AND NOT ANDROID)
  list(APPEND ECHIDNA_ZYGISK_TEST_TARGETS profile_sync_server_test)
endif()

set_target_properties(${ECHIDNA_ZYGISK_TEST_TARGETS} PROPERTIES
    RUNTIME_OUTPUT_DIRECTORY ${CMAKE_BINARY_DIR})

enable_testing()
add_test(NAME zygisk_dsp_smoke_test COMMAND zygisk_dsp_smoke_test)
add_test(NAME capture_buffer_router_test COMMAND capture_buffer_router_test)
add_test(NAME aarch64_instruction_test COMMAND aarch64_instruction_test)
add_test(NAME audiohal_contract_test COMMAND audiohal_contract_test)
add_test(NAME aaudio_callback_registry_test COMMAND aaudio_callback_registry_test)
add_test(NAME opensl_pcm_contract_test COMMAND opensl_pcm_contract_test)
add_test(NAME opensl_buffer_fifo_test COMMAND opensl_buffer_fifo_test)
add_test(NAME opensl_lifecycle_test COMMAND opensl_lifecycle_test)
add_test(NAME tinyalsa_contract_test COMMAND tinyalsa_contract_test)
add_test(NAME capture_route_reachability_test COMMAND capture_route_reachability_test)
add_test(NAME profile_sync_protocol_test COMMAND profile_sync_protocol_test)
add_test(NAME activation_gate_test COMMAND activation_gate_test)
add_test(NAME process_utils_test COMMAND process_utils_test)
add_test(NAME telemetry_accumulator_test COMMAND telemetry_accumulator_test)
if(NOT WIN32)
  add_test(NAME config_shared_memory_test COMMAND config_shared_memory_test)
  add_test(NAME telemetry_socket_exporter_test COMMAND telemetry_socket_exporter_test)
endif()
if(CMAKE_SYSTEM_NAME STREQUAL "Linux" AND NOT ANDROID)
  add_test(NAME profile_sync_server_test COMMAND profile_sync_server_test)
endif()
