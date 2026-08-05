#include <cmath>
#include <cstdint>
#include <vector>

#include "TestFramework.h"
#include "deadaccurate/AmplitudeAnalyzer.h"
#include "deadaccurate/DspChain.h"

using deadaccurate::AmplitudeAnalyzer;
using deadaccurate::DspChain;

namespace {

constexpr int kFs = 48000;
constexpr int kBph = 28800;
constexpr double kBeatFrames = 3600.0 / kBph * kFs;  // 6000
constexpr double kLiftAngle = 52.0;

// liftMs for a target amplitude: inverse of A = γ / (2 sin(π t / T)).
double LiftMsFor(double amplitudeDeg, int bph) {
    const double oscillationMs = 2.0 * 3.6e6 / bph;
    return oscillationMs / M_PI * std::asin(kLiftAngle / (2.0 * amplitudeDeg));
}

// One tick: unlocking, impulse, drop — drop loudest, unlocking faintest —
// as short 5 kHz wavelets spread over the lift time.
void AddTick(std::vector<float>& signal, size_t start, double liftMs) {
    const double offsets[3] = {0.0, liftMs * 0.45, liftMs};
    const double gains[3] = {0.35, 0.5, 1.0};
    for (int p = 0; p < 3; ++p) {
        const auto base = start + static_cast<size_t>(offsets[p] / 1000.0 * kFs);
        for (int j = 0; j < 60; ++j) {
            if (base + j >= signal.size()) return;
            signal[base + j] += static_cast<float>(
                0.4 * gains[p] * std::exp(-j / 15.0) *
                std::sin(2.0 * M_PI * 5000.0 * j / kFs));
        }
    }
}

std::vector<float> ThreePulseSignal(int seconds, double liftMs, uint32_t seed = 1) {
    std::vector<float> signal(static_cast<size_t>(seconds) * kFs);
    uint32_t lcg = seed;
    for (auto& s : signal) {
        lcg = lcg * 1664525u + 1013904223u;
        s = 0.002f * ((static_cast<float>(lcg >> 8) / (1 << 24)) * 2.0f - 1.0f);
    }
    for (size_t beat = 0;; ++beat) {
        const auto start = static_cast<size_t>(beat * kBeatFrames);
        if (start + 3000 >= signal.size()) break;
        AddTick(signal, start, liftMs);
    }
    return signal;
}

}  // namespace

TEST(AmplitudeAnalyzer, RecoversAmplitudeFromSubPulseTiming) {
    for (const double target : {270.0, 200.0}) {
        const double liftMs = LiftMsFor(target, kBph);
        AmplitudeAnalyzer analyzer(kFs);
        const std::vector<float> signal = ThreePulseSignal(8, liftMs);
        for (size_t i = 0; i < signal.size(); ++i) {
            analyzer.Push(signal[i]);
            if (std::fmod(static_cast<double>(i), kBeatFrames) < 1.0) {
                analyzer.OnTick(static_cast<int64_t>(i));
            }
        }
        const auto estimate = analyzer.Current(kBph, kLiftAngle);
        EXPECT_TRUE(estimate.valid);
        EXPECT_NEAR(estimate.amplitudeDeg, target, target * 0.06);
        EXPECT_NEAR(estimate.liftTimeMs, liftMs, 0.6);
    }
}

TEST(AmplitudeAnalyzer, StaysInvalidOnNoise) {
    AmplitudeAnalyzer analyzer(kFs);
    uint32_t lcg = 77;
    for (int i = 0; i < 6 * kFs; ++i) {
        lcg = lcg * 1664525u + 1013904223u;
        analyzer.Push(0.05f * ((static_cast<float>(lcg >> 8) / (1 << 24)) * 2.0f - 1.0f));
        if (i % 6000 == 0) {
            analyzer.OnTick(i);
        }
    }
    // Random maxima produce wildly spread "lift times"; the spread gate
    // must refuse to call that an amplitude.
    EXPECT_TRUE(!analyzer.Current(kBph, kLiftAngle).valid);
}

TEST(AmplitudeAnalyzer, InvalidWithoutARate) {
    AmplitudeAnalyzer analyzer(kFs);
    EXPECT_TRUE(!analyzer.Current(0, kLiftAngle).valid);
}

// End to end through the chain: gate calibrates on the noise floor, the
// tick detector finds the bursts, amplitude frames report the swing.
TEST(DspChain, EdgeModeMeasuresAmplitude) {
    const double liftMs = LiftMsFor(280.0, kBph);
    // First second is noise-only so the gate calibrates on the floor.
    std::vector<float> signal(static_cast<size_t>(kFs), 0.0f);
    uint32_t lcg = 5;
    for (auto& s : signal) {
        lcg = lcg * 1664525u + 1013904223u;
        s = 0.002f * ((static_cast<float>(lcg >> 8) / (1 << 24)) * 2.0f - 1.0f);
    }
    const std::vector<float> ticks = ThreePulseSignal(10, liftMs, 9);
    signal.insert(signal.end(), ticks.begin(), ticks.end());

    DspChain chain(kFs);
    chain.SetBphOverride(kBph);
    DspChain::Output output;
    DspChain::AmplitudeFrame last{};
    constexpr size_t kChunk = 1024;
    for (size_t offset = 0; offset < signal.size(); offset += kChunk) {
        const size_t n = std::min(kChunk, signal.size() - offset);
        chain.Process(signal.data() + offset, n, output);
        for (const auto& frame : output.amplitudes) {
            last = frame;
        }
    }
    EXPECT_TRUE(last.valid);
    EXPECT_NEAR(last.amplitudeDeg, 280.0, 20.0);
}
