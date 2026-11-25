cmake_minimum_required(VERSION 3.22.1)
project(vrcftbridge LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

add_library(vrcftbridge SHARED native-lib.cpp)

target_compile_options(vrcftbridge PRIVATE -O2 -Wall -Wextra -fvisibility=hidden)

find_library(log-lib log)
# binder_ndk: NDK Binder (API 29) for pxreyetrackingservice; nothing PICO-specific is linked.
target_link_libraries(vrcftbridge ${log-lib} binder_ndk dl)
