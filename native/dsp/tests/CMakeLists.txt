cmake_minimum_required(VERSION 3.22)

project(echidna-dsp-tests LANGUAGES CXX)

add_executable(dsp_preset_test preset_test.cpp)
target_link_libraries(dsp_preset_test PRIVATE ech_dsp)
target_include_directories(dsp_preset_test PRIVATE ../include ../src)
target_compile_features(dsp_preset_test PRIVATE cxx_std_20)

enable_testing()
add_test(NAME dsp_preset_test COMMAND dsp_preset_test)
