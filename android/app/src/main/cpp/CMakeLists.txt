cmake_minimum_required(VERSION 3.22.1)
project("native_lib")

add_library(
    native_lib
    SHARED
    native_lib.cpp
    opensl_audio.cpp
)

# Android 系统库
find_library(log-lib log)
find_library(android-lib android)
find_library(opensl-lib OpenSLES)

target_include_directories(native_lib PRIVATE ${CMAKE_CURRENT_SOURCE_DIR})
target_compile_options(native_lib PRIVATE -std=c++11)

target_link_libraries(
    native_lib
    ${log-lib}
    ${android-lib}
    ${opensl-lib}
)
