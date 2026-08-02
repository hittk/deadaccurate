#include <cmath>
#include <cstdint>
#include <vector>

#include "TestFramework.h"
#include "deadaccurate/DspChain.h"

using deadaccurate::DspChain;

namespace {

constexpr int kSampleRate = 48000;
constexpr int kBph = 28800;
constexpr int kBeatPeriodFrames = 6000;  // 125 ms at 48 kHz

// Deterministic noise; test code must not use rand().
struct Lcg {
    uint32_t state = 12345;
    float Next() {
        state = state * 1664525u + 1013904223u;
        return (static_cast<float>(state >> 8) / static_cast<float>(1 << 24)) * 2.0f - 1.0f;
    }
};

// Synthetic watch: ambient noise at ~-60 dBFS plus a 2 ms decaying 5 kHz
// click every beat period (docs/03-signal-processing.md section 8).
std::vector<float> SyntheticWatchSignal(int seconds, double periodFrames,
                                        int beatErrorFrames = 0) {
    Lcg lcg;
    std::vector<float> signal(static_cast<size_t>(seconds) * kSampleRate);
    for (auto& sample : signal) {
        sample = 0.001f * lcg.Next();
    }
    constexpr int kBurstFrames = 96;       // 2 ms
    constexpr float kBurstDecayFrames = 24.0f;  // 0.5 ms
    for (size_t beat = 0;; ++beat) {
        // Odd beats land late by beatErrorFrames: intervals alternate
        // T+e / T-e like a real out-of-beat escapement.
        const auto start = static_cast<size_t>(beat * periodFrames) +
                           (beat % 2 == 1 ? beatErrorFrames : 0);
        if (start + kBurstFrames >= signal.size()) {
            break;
        }
        for (int j = 0; j < kBurstFrames; ++j) {
            const auto burst = static_cast<float>(
                0.3 * std::exp(-j / kBurstDecayFrames) *
                std::sin(2.0 * M_PI * 5000.0 * j / kSampleRate));
            signal[start + j] += burst;
        }
    }
    return signal;
}

}  // namespace

TEST(DspChain, DetectsSyntheticTickTrain) {
    DspChain chain(kSampleRate);
    chain.SetBphOverride(kBph);

    const std::vector<float> signal = SyntheticWatchSignal(6, kBeatPeriodFrames);
    DspChain::Output output;
    std::vector<int64_t> tickFrames;
    size_t calibratingLevels = 0;
    size_t totalLevels = 0;

    // Feed in uneven chunks to exercise block boundaries.
    constexpr size_t kChunk = 997;
    for (size_t offset = 0; offset < signal.size(); offset += kChunk) {
        const size_t n = std::min(kChunk, signal.size() - offset);
        chain.Process(signal.data() + offset, n, output);
        for (const auto& tick : output.ticks) {
            tickFrames.push_back(tick.frameIndex);
        }
        for (const auto& level : output.levels) {
            ++totalLevels;
            if (level.calibrating) {
                ++calibratingLevels;
            }
        }
    }

    // 6 s at 8 ticks/s = 48 beats; the first second is calibration (gate
    // closed), so expect the remaining ~40, give or take edge effects.
    EXPECT_TRUE(tickFrames.size() >= 35);
    EXPECT_TRUE(tickFrames.size() <= 42);

    // Every detected interval must sit on the beat period (no doubles, no
    // splits). Generous +/-2 ms bound; onset jitter is far smaller.
    for (size_t i = 1; i < tickFrames.size(); ++i) {
        const int64_t interval = tickFrames[i] - tickFrames[i - 1];
        EXPECT_TRUE(interval > kBeatPeriodFrames - 96);
        EXPECT_TRUE(interval < kBeatPeriodFrames + 96);
    }

    // Level telemetry runs at ~30 Hz and flags the calibration phase.
    EXPECT_NEAR(static_cast<double>(totalLevels), 180.0, 3.0);
    EXPECT_NEAR(static_cast<double>(calibratingLevels), 30.0, 3.0);
}

TEST(DspChain, AutoDetectsRateAndMeasuresFastWatchEndToEnd) {
    DspChain chain(kSampleRate);  // no override: auto-detection path

    // True period 5999 frames instead of 6000: 1 frame/beat fast at 28800
    // bph -> 86400/6000 = +14.4 s/day.
    const std::vector<float> signal = SyntheticWatchSignal(16, 5999.0);
    DspChain::Output output;
    DspChain::RateFrame last{};
    bool sawSearching = false;

    constexpr size_t kChunk = 1024;
    for (size_t offset = 0; offset < signal.size(); offset += kChunk) {
        const size_t n = std::min(kChunk, signal.size() - offset);
        chain.Process(signal.data() + offset, n, output);
        for (const auto& rate : output.rates) {
            if (rate.activeBph == 0) {
                sawSearching = true;
            }
            last = rate;
        }
    }

    EXPECT_TRUE(sawSearching);  // it searched before locking
    EXPECT_TRUE(last.locked);
    EXPECT_TRUE(!last.overridden);
    EXPECT_EQ(last.activeBph, kBph);
    EXPECT_TRUE(last.rateValid);
    // M3 acceptance bar (docs/04-milestones.md): within ±0.3 s/day of
    // ground truth on synthetic fixtures.
    EXPECT_NEAR(last.secPerDay, 14.4, 0.3);
    EXPECT_TRUE(last.tickCount >= 60);
}

TEST(DspChain, AutoLocksAndMeasuresAllStandardRates) {
    // M3 acceptance: every standard rate auto-locks from raw audio and the
    // readout lands within ±0.3 s/day of ground truth.
    constexpr int kRates[] = {18000, 19800, 21600, 25200, 28800, 36000};
    // 20 ppm fast -> +1.728 s/day, same for every rate.
    constexpr double kSpeedFactor = 1.0 - 20e-6;
    constexpr double kExpectedSecPerDay = 20e-6 * 86400.0;

    for (const int bph : kRates) {
        const double idealPeriod = 3600.0 / bph * kSampleRate;
        const std::vector<float> signal =
            SyntheticWatchSignal(16, idealPeriod * kSpeedFactor);

        DspChain chain(kSampleRate);
        DspChain::Output output;
        DspChain::RateFrame last{};
        constexpr size_t kChunk = 1024;
        for (size_t offset = 0; offset < signal.size(); offset += kChunk) {
            const size_t n = std::min(kChunk, signal.size() - offset);
            chain.Process(signal.data() + offset, n, output);
            for (const auto& rate : output.rates) {
                last = rate;
            }
        }

        EXPECT_TRUE(last.locked);
        EXPECT_EQ(last.activeBph, bph);
        EXPECT_TRUE(last.rateValid);
        EXPECT_NEAR(last.secPerDay, kExpectedSecPerDay, 0.3);
    }
}

TEST(DspChain, MeasuresBeatErrorEndToEnd) {
    DspChain chain(kSampleRate);  // auto-detect path

    // 2 ms beat error (96 frames) on an otherwise perfect 28800 watch.
    const std::vector<float> signal = SyntheticWatchSignal(16, kBeatPeriodFrames, 96);
    DspChain::Output output;
    DspChain::RateFrame last{};
    constexpr size_t kChunk = 1024;
    for (size_t offset = 0; offset < signal.size(); offset += kChunk) {
        const size_t n = std::min(kChunk, signal.size() - offset);
        chain.Process(signal.data() + offset, n, output);
        for (const auto& rate : output.rates) {
            last = rate;
        }
    }

    EXPECT_TRUE(last.locked);
    EXPECT_EQ(last.activeBph, kBph);
    EXPECT_TRUE(last.rateValid);
    EXPECT_NEAR(last.beatErrorMs, 2.0, 0.3);
    EXPECT_NEAR(last.secPerDay, 0.0, 0.3);
}

TEST(DspChain, OverridePinsTheRateImmediately) {
    DspChain chain(kSampleRate);
    chain.SetBphOverride(18000);

    const std::vector<float> signal = SyntheticWatchSignal(4, kBeatPeriodFrames);
    DspChain::Output output;
    DspChain::RateFrame last{};
    constexpr size_t kChunk = 1024;
    for (size_t offset = 0; offset < signal.size(); offset += kChunk) {
        const size_t n = std::min(kChunk, signal.size() - offset);
        chain.Process(signal.data() + offset, n, output);
        for (const auto& rate : output.rates) {
            last = rate;
        }
    }
    // The signal is 28800 but the override pins 18000 (and reports it).
    EXPECT_EQ(last.activeBph, 18000);
    EXPECT_TRUE(last.overridden);
}

TEST(DspChain, StaysSilentOnPureNoise) {
    DspChain chain(kSampleRate);
    chain.SetBphOverride(kBph);

    Lcg lcg;
    std::vector<float> noise(static_cast<size_t>(4) * kSampleRate);
    for (auto& sample : noise) {
        sample = 0.001f * lcg.Next();
    }

    DspChain::Output output;
    size_t ticks = 0;
    constexpr size_t kChunk = 1024;
    for (size_t offset = 0; offset < noise.size(); offset += kChunk) {
        const size_t n = std::min(kChunk, noise.size() - offset);
        chain.Process(noise.data() + offset, n, output);
        ticks += output.ticks.size();
    }
    EXPECT_EQ(ticks, 0u);
}
