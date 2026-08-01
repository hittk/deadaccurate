# Android build of the native engine: JNI bindings + platform glue around the
# platform-independent core. The core is also built standalone on the host for
# unit tests via engine/host/CMakeLists.txt.
cmake_minimum_required(VERSION 3.22)
project(deadaccurate_engine LANGUAGES CXX)

add_subdirectory(core)

add_library(deadaccurate_engine SHARED
    jni/EngineJni.cpp
)

target_link_libraries(deadaccurate_engine
    PRIVATE
    deadaccurate_core
    android
    log
)

target_compile_options(deadaccurate_engine PRIVATE -Wall -Wextra -Werror)
