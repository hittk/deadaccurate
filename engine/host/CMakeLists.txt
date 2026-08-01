# Host build of the engine core + its unit tests. Runs anywhere with a C++20
# compiler — no Android SDK/NDK required:
#
#   cmake -S engine/host -B engine/host/build
#   cmake --build engine/host/build
#   ctest --test-dir engine/host/build --output-on-failure
cmake_minimum_required(VERSION 3.22)
project(deadaccurate_host_tests LANGUAGES CXX)

enable_testing()

add_subdirectory(../src/main/cpp/core core)

add_executable(core_tests
    tests/SpscRingBufferTest.cpp
    tests/VersionTest.cpp
    tests/TestMain.cpp
)
target_link_libraries(core_tests PRIVATE deadaccurate_core)
target_compile_options(core_tests PRIVATE -Wall -Wextra -Werror)

add_test(NAME core_tests COMMAND core_tests)
