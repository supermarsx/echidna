find_package(Threads REQUIRED)

add_executable(legacy_effect_abi_test abi_test.cpp)
target_link_libraries(legacy_effect_abi_test PRIVATE echidna_preproc)

add_executable(legacy_effect_lifecycle_test lifecycle_test.cpp)
target_link_libraries(legacy_effect_lifecycle_test PRIVATE echidna_preproc)

add_executable(legacy_effect_audio_test audio_test.cpp)
target_link_libraries(legacy_effect_audio_test
    PRIVATE echidna_preproc_core Threads::Threads)

add_executable(legacy_effect_realtime_test realtime_test.cpp)
target_link_libraries(legacy_effect_realtime_test PRIVATE echidna_preproc_core)

add_executable(legacy_effect_capability_test capability_test.cpp)
target_link_libraries(legacy_effect_capability_test PRIVATE echidna_preproc_core)

set(_legacy_effect_tests
    legacy_effect_abi_test
    legacy_effect_lifecycle_test
    legacy_effect_audio_test
    legacy_effect_realtime_test
    legacy_effect_capability_test)
foreach(_target IN LISTS _legacy_effect_tests)
  target_include_directories(${_target} PRIVATE ${CMAKE_CURRENT_SOURCE_DIR}/..)
  target_compile_features(${_target} PRIVATE cxx_std_20)
  set_target_properties(${_target} PROPERTIES
      RUNTIME_OUTPUT_DIRECTORY ${CMAKE_BINARY_DIR}/$<CONFIG>)
  add_test(NAME ${_target} COMMAND ${_target})
endforeach()
