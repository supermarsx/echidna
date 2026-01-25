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

if(MSVC)
  set_target_properties(ech_dsp PROPERTIES
      RUNTIME_OUTPUT_DIRECTORY ${CMAKE_BINARY_DIR}/$<CONFIG>
      ARCHIVE_OUTPUT_DIRECTORY ${CMAKE_BINARY_DIR}/$<CONFIG>
      LIBRARY_OUTPUT_DIRECTORY ${CMAKE_BINARY_DIR}/$<CONFIG>
      WINDOWS_EXPORT_ALL_SYMBOLS ON)
else()
  set_target_properties(ech_dsp PROPERTIES
      LIBRARY_OUTPUT_DIRECTORY ${CMAKE_BINARY_DIR}/lib)
  if(ANDROID AND DEFINED ANDROID_ABI)
    set_target_properties(ech_dsp PROPERTIES
        LIBRARY_OUTPUT_DIRECTORY ${CMAKE_BINARY_DIR}/lib/${ANDROID_ABI})
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


find_library(CRYPTO_LIB crypto)
if(CRYPTO_LIB)
  target_link_libraries(ech_dsp PRIVATE ${CRYPTO_LIB})
  target_compile_definitions(ech_dsp PRIVATE ECHIDNA_HAS_BORINGSSL=1)
endif()
install(TARGETS ech_dsp
    LIBRARY DESTINATION lib)
install(DIRECTORY include/ DESTINATION include)

if(BUILD_TESTING)
  add_subdirectory(tests)
endif()
