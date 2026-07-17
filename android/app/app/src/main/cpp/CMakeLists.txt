cmake_minimum_required(VERSION 3.22)

project(echidna_lab_jni LANGUAGES C CXX)

set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)

# In-process DSP bridge for the app's "Lab" testbench. It dlopens the
# self-contained DSP engine (libech_dsp.so, packaged into this APK's jniLibs)
# and runs the app's OWN audio through it. It does not link the Zygisk module.
add_library(echidna_lab_jni SHARED
    echidna_lab_jni.cpp)

find_library(LOG_LIB log)
find_library(DL_LIB dl)

if(LOG_LIB)
    target_link_libraries(echidna_lab_jni PRIVATE ${LOG_LIB})
endif()

if(DL_LIB)
    target_link_libraries(echidna_lab_jni PRIVATE ${DL_LIB})
endif()

get_filename_component(ECHIDNA_REPO_ROOT
    "${CMAKE_CURRENT_SOURCE_DIR}/../../../../../../"
    REALPATH)

target_include_directories(echidna_lab_jni
    PRIVATE
        ${CMAKE_CURRENT_SOURCE_DIR}
        ${ECHIDNA_REPO_ROOT}/native/dsp/include)
