cmake_minimum_required(VERSION 3.22)

add_library(echidna_preproc_core STATIC
    capability_protocol.cpp
    effect_context.cpp
    telemetry_protocol.cpp)
set_target_properties(echidna_preproc_core PROPERTIES
    POSITION_INDEPENDENT_CODE ON)
target_include_directories(echidna_preproc_core
    PUBLIC ${CMAKE_CURRENT_SOURCE_DIR})
target_link_libraries(echidna_preproc_core PUBLIC ech_dsp_core)
target_compile_features(echidna_preproc_core PUBLIC cxx_std_20)

add_library(echidna_preproc SHARED
    effect_library.cpp)
target_link_libraries(echidna_preproc PRIVATE echidna_preproc_core)
target_include_directories(echidna_preproc PUBLIC ${CMAKE_CURRENT_SOURCE_DIR})
target_compile_definitions(echidna_preproc PRIVATE ECHIDNA_PREPROC_BUILD=1)
target_compile_features(echidna_preproc PRIVATE cxx_std_20)
set_target_properties(echidna_preproc PROPERTIES
    OUTPUT_NAME echidna_preproc
    CXX_VISIBILITY_PRESET hidden
    VISIBILITY_INLINES_HIDDEN YES)

if(WIN32)
  set_target_properties(echidna_preproc PROPERTIES
      RUNTIME_OUTPUT_DIRECTORY ${CMAKE_BINARY_DIR}/$<CONFIG>
      ARCHIVE_OUTPUT_DIRECTORY ${CMAKE_BINARY_DIR}/$<CONFIG>
      LIBRARY_OUTPUT_DIRECTORY ${CMAKE_BINARY_DIR}/$<CONFIG>)
else()
  set_target_properties(echidna_preproc PROPERTIES
      LIBRARY_OUTPUT_DIRECTORY ${CMAKE_BINARY_DIR}/lib)
  target_link_options(echidna_preproc PRIVATE
      "LINKER:--version-script=${CMAKE_CURRENT_SOURCE_DIR}/effect_exports.map")
  set_target_properties(echidna_preproc PROPERTIES
      LINK_DEPENDS ${CMAKE_CURRENT_SOURCE_DIR}/effect_exports.map)
endif()

install(TARGETS echidna_preproc LIBRARY DESTINATION lib)

if(BUILD_TESTING AND NOT ANDROID)
  add_subdirectory(tests)
endif()
