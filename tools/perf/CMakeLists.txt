cmake_minimum_required(VERSION 3.22)

project(echidna-audio-performance LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)

# This is deliberately an independent host-only build. It consumes the real DSP
# library and PCM bridge without adding benchmark targets to Android/NDK builds.
set(BUILD_TESTING OFF CACHE BOOL "Build tests" FORCE)
set(ECHIDNA_FETCH_BORINGSSL OFF CACHE BOOL "Fetch BoringSSL" FORCE)

set(ECHIDNA_ROOT "${CMAKE_CURRENT_LIST_DIR}/../..")
add_subdirectory("${ECHIDNA_ROOT}/native/dsp" "${CMAKE_BINARY_DIR}/echidna-dsp")

add_library(echidna_pcm_buffer_processor STATIC
    "${ECHIDNA_ROOT}/native/zygisk/src/audio/pcm_buffer_processor.cpp")
target_include_directories(echidna_pcm_buffer_processor
    PUBLIC
        "${ECHIDNA_ROOT}/native/zygisk/src"
        "${ECHIDNA_ROOT}/native/include")
target_compile_features(echidna_pcm_buffer_processor PUBLIC cxx_std_20)

add_executable(audio_pipeline_benchmark audio_pipeline_benchmark.cpp)
target_link_libraries(audio_pipeline_benchmark
    PRIVATE
        ech_dsp
        echidna_pcm_buffer_processor)
target_include_directories(audio_pipeline_benchmark
    PRIVATE
        "${ECHIDNA_ROOT}/native/dsp/include"
        "${ECHIDNA_ROOT}/native/include"
        "${ECHIDNA_ROOT}/native/zygisk/src")
target_compile_features(audio_pipeline_benchmark PRIVATE cxx_std_20)

string(TOUPPER "${CMAKE_BUILD_TYPE}" ECHIDNA_BUILD_TYPE_UPPER)
set(ECHIDNA_RECORDED_CXX_FLAGS
    "${CMAKE_CXX_FLAGS} ${CMAKE_CXX_FLAGS_${ECHIDNA_BUILD_TYPE_UPPER}}")
if(MSVC)
  string(APPEND ECHIDNA_RECORDED_CXX_FLAGS " /W4 /permissive-")
else()
  string(APPEND ECHIDNA_RECORDED_CXX_FLAGS
      " -Wall -Wextra -Wpedantic -Wconversion -Wshadow")
endif()
string(REPLACE "\\" "\\\\" ECHIDNA_RECORDED_CXX_FLAGS
    "${ECHIDNA_RECORDED_CXX_FLAGS}")
string(REPLACE "\"" "\\\"" ECHIDNA_RECORDED_CXX_FLAGS
    "${ECHIDNA_RECORDED_CXX_FLAGS}")
target_compile_definitions(audio_pipeline_benchmark PRIVATE
    ECHIDNA_PERF_BUILD_TYPE="$<CONFIG>"
    ECHIDNA_PERF_CXX_FLAGS="${ECHIDNA_RECORDED_CXX_FLAGS}")

if(MSVC)
  target_compile_options(audio_pipeline_benchmark PRIVATE /W4 /permissive-)
else()
  target_compile_options(audio_pipeline_benchmark PRIVATE
      -Wall -Wextra -Wpedantic -Wconversion -Wshadow)
endif()

if(WIN32)
  set_target_properties(audio_pipeline_benchmark PROPERTIES
      RUNTIME_OUTPUT_DIRECTORY "${CMAKE_BINARY_DIR}/$<CONFIG>")
endif()
