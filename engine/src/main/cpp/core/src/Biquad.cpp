#include "deadaccurate/Biquad.h"

#include <cmath>

namespace deadaccurate {
namespace {

constexpr double kPi = 3.14159265358979323846;
// Butterworth Q for a single 2nd-order section.
constexpr double kButterworthQ = 0.70710678118654752440;

}  // namespace

Biquad Biquad::LowPass(double sampleRate, double cutoffHz, double q) {
    const double w0 = 2.0 * kPi * cutoffHz / sampleRate;
    const double cosw0 = std::cos(w0);
    const double alpha = std::sin(w0) / (2.0 * q);
    const double a0 = 1.0 + alpha;
    return {
        ((1.0 - cosw0) / 2.0) / a0,
        (1.0 - cosw0) / a0,
        ((1.0 - cosw0) / 2.0) / a0,
        (-2.0 * cosw0) / a0,
        (1.0 - alpha) / a0,
    };
}

Biquad Biquad::HighPass(double sampleRate, double cutoffHz, double q) {
    const double w0 = 2.0 * kPi * cutoffHz / sampleRate;
    const double cosw0 = std::cos(w0);
    const double alpha = std::sin(w0) / (2.0 * q);
    const double a0 = 1.0 + alpha;
    return {
        ((1.0 + cosw0) / 2.0) / a0,
        -(1.0 + cosw0) / a0,
        ((1.0 + cosw0) / 2.0) / a0,
        (-2.0 * cosw0) / a0,
        (1.0 - alpha) / a0,
    };
}

BandPassFilter::BandPassFilter(double sampleRate, double lowCutHz, double highCutHz)
    : highPass_(Biquad::HighPass(sampleRate, lowCutHz, kButterworthQ)),
      lowPass_(Biquad::LowPass(sampleRate, highCutHz, kButterworthQ)) {}

void BandPassFilter::Reset() {
    highPass_.Reset();
    lowPass_.Reset();
}

}  // namespace deadaccurate
