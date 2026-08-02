#include <cmath>
#include <cstddef>

#include "TestFramework.h"
#include "deadaccurate/Biquad.h"

using deadaccurate::BandPassFilter;

namespace {

constexpr double kSampleRate = 48000.0;

// Steady-state gain of the filter for a sine at `freqHz` (RMS out / RMS in,
// measured over the second half so the transient settles).
float SineGain(double freqHz) {
    BandPassFilter filter(kSampleRate, 2000.0, 12000.0);
    const size_t total = 9600;
    double sumSquares = 0.0;
    size_t measured = 0;
    for (size_t i = 0; i < total; ++i) {
        const auto x = static_cast<float>(
            std::sin(2.0 * M_PI * freqHz * static_cast<double>(i) / kSampleRate));
        const float y = filter.Process(x);
        if (i >= total / 2) {
            sumSquares += static_cast<double>(y) * y;
            ++measured;
        }
    }
    const double rmsOut = std::sqrt(sumSquares / static_cast<double>(measured));
    const double rmsIn = 1.0 / std::sqrt(2.0);
    return static_cast<float>(rmsOut / rmsIn);
}

}  // namespace

TEST(BandPassFilter, PassesTickBandNearUnity) {
    EXPECT_TRUE(SineGain(5000.0) > 0.9f);
}

TEST(BandPassFilter, RejectsRumbleBelowTheBand) {
    EXPECT_TRUE(SineGain(100.0) < 0.01f);
}

TEST(BandPassFilter, AttenuatesAboveTheBand) {
    EXPECT_TRUE(SineGain(20000.0) < 0.5f);
    EXPECT_TRUE(SineGain(20000.0) < SineGain(5000.0));
}
