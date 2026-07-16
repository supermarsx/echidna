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

# Guard the public C signatures/exports and the source-compatible three-argument
# DspEngine construction path while proving embedded contexts can skip plugins.
add_executable(dsp_api_abi_test api_abi_test.cpp)
target_link_libraries(dsp_api_abi_test PRIVATE ech_dsp)
target_include_directories(dsp_api_abi_test PRIVATE ../include ../src)
target_compile_features(dsp_api_abi_test PRIVATE cxx_std_20)

# §10 engine-level DSP correctness / quality: neutral bit-exactness through the
# full chain, denormal/subnormal handling, full-chain finite guarantee under
# pathological boundary signals (full-scale DC, alternating, impulse, subnormals),
# frame-boundary canaries, and the documented isfinite fail-safe boundary of
# responsibility (engine is GIGO; the stream registry is the sanitizing guard).
add_executable(dsp_quality_test dsp_quality_test.cpp)
target_link_libraries(dsp_quality_test PRIVATE ech_dsp)
target_include_directories(dsp_quality_test PRIVATE ../include ../src)
target_compile_features(dsp_quality_test PRIVATE cxx_std_20)

# Emit the test binaries directly into the top-level build dir (build/dsp/)
# rather than build/dsp/tests/, so CI's `./build/dsp/dsp_preset_test` and
# `./build/dsp/dsp_engine_test` invocations find them. CMAKE_BINARY_DIR is the
# root of this configure (build/dsp/ when CI runs `cmake -S native/dsp -B build/dsp`).
set_target_properties(dsp_preset_test dsp_engine_test dsp_effects_test dsp_api_abi_test dsp_quality_test PROPERTIES
    RUNTIME_OUTPUT_DIRECTORY ${CMAKE_BINARY_DIR})

enable_testing()
add_test(NAME dsp_preset_test COMMAND dsp_preset_test)
add_test(NAME dsp_engine_test COMMAND dsp_engine_test)
add_test(NAME dsp_effects_test COMMAND dsp_effects_test)
add_test(NAME dsp_api_abi_test COMMAND dsp_api_abi_test)
add_test(NAME dsp_quality_test COMMAND dsp_quality_test)

# The Windows host build places libech_dsp.dll under the configuration output
# directory while these long-standing test executables remain at the build root.
# Make CTest resolve the exact DLL it just built without relying on a user PATH.
set_tests_properties(
    dsp_preset_test
    dsp_engine_test
    dsp_effects_test
    dsp_api_abi_test
    dsp_quality_test
    PROPERTIES
        ENVIRONMENT_MODIFICATION
            "PATH=path_list_prepend:$<TARGET_FILE_DIR:ech_dsp>")
