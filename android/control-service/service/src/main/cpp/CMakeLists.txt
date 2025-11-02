cmake_minimum_required(VERSION 3.22)

project(echidna_control_jni LANGUAGES C CXX)

set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)

add_library(echidna_control_jni SHARED
    echidna_control_jni.cpp)

find_library(LOG_LIB log)
find_library(DL_LIB dl)

if(LOG_LIB)
    target_link_libraries(echidna_control_jni PRIVATE ${LOG_LIB})
endif()

if(DL_LIB)
    target_link_libraries(echidna_control_jni PRIVATE ${DL_LIB})
endif()

target_include_directories(echidna_control_jni
    PRIVATE
        ${CMAKE_CURRENT_SOURCE_DIR}
        ${CMAKE_CURRENT_SOURCE_DIR}/../../../../../native/include)

