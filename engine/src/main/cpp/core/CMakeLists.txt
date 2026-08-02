# Platform-independent engine core: DSP and data structures only.
# Hard rule (docs/02-architecture.md section 2): nothing in this directory may
# include Android or JNI headers — it must compile on the host unchanged.
cmake_minimum_required(VERSION 3.22)
project(deadaccurate_core LANGUAGES CXX)

add_library(deadaccurate_core STATIC
    src/Version.cpp
    src/LevelAnalyzer.cpp
    src/Biquad.cpp
    src/NoiseGate.cpp
    src/TickDetector.cpp
    src/DspChain.cpp
    src/BeatRateDetector.cpp
    src/RateEstimator.cpp
)

target_include_directories(deadaccurate_core PUBLIC include)

target_compile_features(deadaccurate_core PUBLIC cxx_std_20)
set_target_properties(deadaccurate_core PROPERTIES POSITION_INDEPENDENT_CODE ON)
target_compile_options(deadaccurate_core PRIVATE -Wall -Wextra -Werror)
