#pragma once

#include <cstddef>
#include <vector>

namespace deadaccurate {

// Hop-based RMS/peak meter feeding the UI level display (M1). Later chain
// stages (band-pass, envelope, gate — docs/03-signal-processing.md) will sit
// upstream of this analyzer on the same DSP thread.
class LevelAnalyzer {
public:
    struct Level {
        float rmsDb;
        float peakDb;
    };

    // Emits one Level per `hopSize` consumed samples.
    explicit LevelAnalyzer(size_t hopSize);

    // Consumes `count` samples; appends a Level to `out` for each completed
    // hop (zero or more per call).
    void Push(const float* samples, size_t count, std::vector<Level>& out);

private:
    const size_t hopSize_;
    size_t samplesInHop_ = 0;
    double sumSquares_ = 0.0;
    float peak_ = 0.0f;
};

}  // namespace deadaccurate
