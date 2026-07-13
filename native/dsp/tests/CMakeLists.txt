cmake_minimum_required(VERSION 3.22)

project(echidna-dsp-tests LANGUAGES CXX)

add_executable(dsp_preset_test preset_test.cpp)
target_link_libraries(dsp_preset_test PRIVATE ech_dsp)
target_include_directories(dsp_preset_test PRIVATE ../include ../src)
target_compile_features(dsp_preset_test PRIVATE cxx_std_20)

add_executable(dsp_engine_test engine_test.cpp)
target_link_libraries(dsp_engine_test PRIVATE ech_dsp)
target_include_directories(dsp_engine_test PRIVATE ../include ../src)
target_compile_features(dsp_engine_test PRIVATE cxx_std_20)

# Per-effect correctness tests (golden-signal fixtures): pitch cents accuracy,
# formant tilt, auto-tune snap, gate/compressor/EQ sanity. White-box against the
# effect classes (headers under ../src/effects), linked from ech_dsp.
add_executable(dsp_effects_test effects_test.cpp)
target_link_libraries(dsp_effects_test PRIVATE ech_dsp)
target_include_directories(dsp_effects_test PRIVATE ../include ../src)
target_compile_features(dsp_effects_test PRIVATE cxx_std_20)

# Emit the test binaries directly into the top-level build dir (build/dsp/)
# rather than build/dsp/tests/, so CI's `./build/dsp/dsp_preset_test` and
# `./build/dsp/dsp_engine_test` invocations find them. CMAKE_BINARY_DIR is the
# root of this configure (build/dsp/ when CI runs `cmake -S native/dsp -B build/dsp`).
set_target_properties(dsp_preset_test dsp_engine_test dsp_effects_test PROPERTIES
    RUNTIME_OUTPUT_DIRECTORY ${CMAKE_BINARY_DIR})

enable_testing()
add_test(NAME dsp_preset_test COMMAND dsp_preset_test)
add_test(NAME dsp_engine_test COMMAND dsp_engine_test)
add_test(NAME dsp_effects_test COMMAND dsp_effects_test)
