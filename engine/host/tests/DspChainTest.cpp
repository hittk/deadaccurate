#include <cmath>
#include <cstdint>
#include <cstdio>
#include <vector>

#include "TestFramework.h"
#include "deadaccurate/BeatRateDetector.h"
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

// Phone-mic conditions: heavy ambient noise (AGC-pumped) with ticks only a
// few dB proud of it. Edge detection cannot work here; correlation must.
std::vector<float> LowSnrWatchSignal(int seconds, double periodFrames,
                                     int beatErrorFrames = 0) {
    Lcg lcg{99};
    std::vector<float> signal(static_cast<size_t>(seconds) * kSampleRate);
    for (auto& sample : signal) {
        sample = 0.02f * lcg.Next();
    }
    constexpr int kBurstFrames = 96;
    constexpr float kBurstDecayFrames = 24.0f;
    for (size_t beat = 0;; ++beat) {
        const auto start = static_cast<size_t>(beat * periodFrames) +
                           (beat % 2 == 1 ? beatErrorFrames : 0);
        if (start + kBurstFrames >= signal.size()) {
            break;
        }
        for (int j = 0; j < kBurstFrames; ++j) {
            signal[start + j] += static_cast<float>(
                0.04 * std::exp(-j / kBurstDecayFrames) *
                std::sin(2.0 * M_PI * 5000.0 * j / kSampleRate));
        }
    }
    return signal;
}

DspChain::RateFrame RunChain(DspChain& chain, const std::vector<float>& signal,
                             size_t* phaseFrames = nullptr, int* tickCount = nullptr,
                             bool* everLocked = nullptr) {
    DspChain::Output output;
    DspChain::RateFrame last{};
    constexpr size_t kChunk = 1024;
    for (size_t offset = 0; offset < signal.size(); offset += kChunk) {
        const size_t n = std::min(kChunk, signal.size() - offset);
        chain.Process(signal.data() + offset, n, output);
        for (const auto& rate : output.rates) {
            last = rate;
            if (everLocked != nullptr && rate.activeBph != 0) {
                *everLocked = true;
            }
        }
        if (phaseFrames != nullptr) {
            *phaseFrames += output.phases.size();
        }
        if (tickCount != nullptr) {
            *tickCount += static_cast<int>(output.ticks.size());
        }
    }
    return last;
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
    EXPECT_TRUE(last.detectedBph > 0);
    EXPECT_TRUE(!last.overridden);
    EXPECT_EQ(last.activeBph, kBph);
    EXPECT_TRUE(last.rateValid);
    // M3 acceptance bar (docs/04-milestones.md): within ±0.3 s/day of
    // ground truth on synthetic fixtures.
    EXPECT_NEAR(last.secPerDay, 14.4, 0.3);
    EXPECT_TRUE(last.tickCount >= 60);
}

TEST(DspChain, AutoLocksAndMeasuresAllStandardRates) {
    // M3 acceptance: every supported rate (including the vintage 14400 and
    // 16200) auto-locks from raw audio and the readout lands within
    // ±0.3 s/day of ground truth.
    // 20 ppm fast -> +1.728 s/day, same for every rate.
    constexpr double kSpeedFactor = 1.0 - 20e-6;
    constexpr double kExpectedSecPerDay = 20e-6 * 86400.0;

    for (const int bph : deadaccurate::BeatRateDetector::kStandardRatesBph) {
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

        EXPECT_TRUE(last.detectedBph > 0);
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

    EXPECT_TRUE(last.detectedBph > 0);
    EXPECT_EQ(last.activeBph, kBph);
    EXPECT_TRUE(last.rateValid);
    EXPECT_NEAR(last.beatErrorMs, 2.0, 0.3);
    EXPECT_NEAR(last.secPerDay, 0.0, 0.3);
}

TEST(DspChain, OverridePinsTheRateImmediately) {
    DspChain chain(kSampleRate);
    chain.SetBphOverride(18000);

    const std::vector<float> signal = SyntheticWatchSignal(6, kBeatPeriodFrames);
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
    // The detector keeps scoring in the background so the UI can flag the
    // disagreement (FR-4).
    EXPECT_EQ(last.detectedBph, kBph);
}

TEST(DspChain, CorrelationLocksAndMeasuresWhereEdgeCannot) {
    // ~+14.4 s/day fast at phone-mic SNR. 60 s: the folded-phase regression
    // needs the profile settled (~20 s) plus a clean span.
    const std::vector<float> signal = LowSnrWatchSignal(60, 5999.0);

    // Edge mode gets nothing: ticks never clear a gate 12 dB over this floor.
    DspChain edgeChain(kSampleRate);
    edgeChain.SetAnalysisMode(DspChain::AnalysisMode::kEdge);
    int edgeTicks = 0;
    const auto edgeLast = RunChain(edgeChain, signal, nullptr, &edgeTicks);
    EXPECT_TRUE(!edgeLast.rateValid);
    EXPECT_TRUE(edgeTicks < 20);

    // Correlation mode locks and measures.
    DspChain chain(kSampleRate);
    chain.SetAnalysisMode(DspChain::AnalysisMode::kCorrelation);
    size_t phaseFrames = 0;
    const auto last = RunChain(chain, signal, &phaseFrames);
    EXPECT_EQ(last.activeBph, kBph);
    EXPECT_EQ(last.detectedBph, kBph);
    EXPECT_TRUE(last.rateValid);
    EXPECT_NEAR(last.secPerDay, 14.4, 1.0);
    EXPECT_TRUE(phaseFrames > 10);  // the trace has something to draw
}

TEST(DspChain, SignatureFramesReportTheDominantBand) {
    // The synthetic tick carrier is 5 kHz — band 1 (3-8 kHz) of the
    // signature must dominate, and frames must flow in BOTH modes (the
    // folding path always runs). Movement recognition consumes these.
    const std::vector<float> signal = LowSnrWatchSignal(30, 5999.0);
    for (const auto mode : {DspChain::AnalysisMode::kCorrelation,
                            DspChain::AnalysisMode::kEdge}) {
        DspChain chain(kSampleRate);
        chain.SetAnalysisMode(mode);
        DspChain::Output output;
        DspChain::SignatureFrame last{};
        size_t frames = 0;
        constexpr size_t kChunk = 1024;
        for (size_t offset = 0; offset < signal.size(); offset += kChunk) {
            const size_t n = std::min(kChunk, signal.size() - offset);
            chain.Process(signal.data() + offset, n, output);
            for (const auto& sig : output.signatures) {
                last = sig;
                ++frames;
            }
        }
        EXPECT_TRUE(frames > 10);
        EXPECT_EQ(last.bph, kBph);
        for (int c = 0; c < deadaccurate::FoldingAnalyzer::kChannels; ++c) {
            EXPECT_TRUE(last.bandScores[1] >= last.bandScores[c]);
        }
    }
}

TEST(DspChain, CorrelationStaysUnlockedOnPureNoise) {
    Lcg lcg{7};
    std::vector<float> noise(static_cast<size_t>(30) * kSampleRate);
    for (auto& sample : noise) {
        sample = 0.02f * lcg.Next();
    }
    DspChain chain(kSampleRate);
    chain.SetAnalysisMode(DspChain::AnalysisMode::kCorrelation);
    bool everLocked = false;
    RunChain(chain, noise, nullptr, nullptr, &everLocked);
    EXPECT_TRUE(!everLocked);
}

TEST(DspChain, CorrelationMeasuresBeatErrorAtLowSnr) {
    // 2 ms beat error, on-rate, phone-mic SNR.
    const std::vector<float> signal = LowSnrWatchSignal(60, kBeatPeriodFrames, 96);
    DspChain chain(kSampleRate);
    chain.SetAnalysisMode(DspChain::AnalysisMode::kCorrelation);
    const auto last = RunChain(chain, signal);
    EXPECT_EQ(last.activeBph, kBph);
    EXPECT_TRUE(last.rateValid);
    EXPECT_NEAR(last.secPerDay, 0.0, 1.5);
    EXPECT_NEAR(last.beatErrorMs, 2.0, 0.5);
}

TEST(DspChain, CorrelationDisambiguatesDoubledPeriods) {
    // 36000 bph (100 ms) vs 18000 bph (200 ms) is the only 2x pair in the
    // standard set; folding a fast watch at the doubled period shows two
    // peaks and must not win.
    {
        const std::vector<float> signal =
            LowSnrWatchSignal(30, 3600.0 / 36000 * kSampleRate);
        DspChain chain(kSampleRate);
        chain.SetAnalysisMode(DspChain::AnalysisMode::kCorrelation);
        EXPECT_EQ(RunChain(chain, signal).activeBph, 36000);
    }
    {
        const std::vector<float> signal =
            LowSnrWatchSignal(30, 3600.0 / 18000 * kSampleRate);
        DspChain chain(kSampleRate);
        chain.SetAnalysisMode(DspChain::AnalysisMode::kCorrelation);
        EXPECT_EQ(RunChain(chain, signal).activeBph, 18000);
    }
}

TEST(DspChain, CorrelationLockMatrixAllRatesAllBands) {
    // The "any watch" guarantee: every supported rate, with its tick energy
    // landing in each of the three correlation bands, under rumble-heavy
    // colored noise like a real room — all must lock in correlation mode.
    constexpr double kCarriers[] = {1500.0, 5000.0, 11000.0};

    for (const int bph : deadaccurate::BeatRateDetector::kStandardRatesBph) {
        for (const double carrierHz : kCarriers) {
            const double period = 3600.0 / bph * kSampleRate;
            Lcg lcg{static_cast<uint32_t>(bph + static_cast<int>(carrierHz))};
            std::vector<float> signal(static_cast<size_t>(20) * kSampleRate);
            // Rumble: low-passed noise 20 dB above the broadband floor.
            float rumble = 0.0f;
            for (auto& sample : signal) {
                rumble += 0.026f * (0.10f * lcg.Next() - rumble);
                sample = rumble + 0.01f * lcg.Next();
            }
            for (size_t beat = 0;; ++beat) {
                const auto start = static_cast<size_t>(beat * period);
                if (start + 96 >= signal.size()) break;
                for (int j = 0; j < 96; ++j) {
                    signal[start + j] += static_cast<float>(
                        0.05 * std::exp(-j / 24.0) *
                        std::sin(2.0 * M_PI * carrierHz * j / kSampleRate));
                }
            }

            DspChain chain(kSampleRate);
            chain.SetAnalysisMode(DspChain::AnalysisMode::kCorrelation);
            const auto last = RunChain(chain, signal);
            if (last.activeBph != bph) {
                std::printf("  matrix miss: bph=%d carrier=%.0f -> active=%d det=%d\n",
                            bph, carrierHz, last.activeBph, last.detectedBph);
            }
            EXPECT_EQ(last.activeBph, bph);
        }
    }
}

TEST(DspChain, CorrelationDisambiguates14400From28800) {
    // The second 2x pair once vintage rates joined the candidate set.
    for (const int bph : {14400, 28800}) {
        const std::vector<float> signal =
            LowSnrWatchSignal(30, 3600.0 / bph * kSampleRate);
        DspChain chain(kSampleRate);
        chain.SetAnalysisMode(DspChain::AnalysisMode::kCorrelation);
        EXPECT_EQ(RunChain(chain, signal).activeBph, bph);
    }
}

TEST(DspChain, CorrelationWorksAt44100) {
    // 44.1 kHz makes a "1 ms" bin 44 samples = 0.99773 ms; folding with a
    // nominal 1 ms would smear the profile to nothing. Locks AND measures.
    constexpr int kFs = 44100;
    const double idealPeriod = 3600.0 / 28800 * kFs;
    // 1 part in 6000 fast -> +14.4 s/day: large enough for the fold's
    // phase-drift resolution over a 60 s window (tiny drifts are edge
    // mode's territory), and a nominal-1ms-bin bug would still smear this
    // to a mislock.
    const double period = idealPeriod * (1.0 - 1.0 / 6000.0);

    Lcg lcg{77};
    std::vector<float> signal(static_cast<size_t>(60) * kFs);
    for (auto& sample : signal) {
        sample = 0.02f * lcg.Next();
    }
    for (size_t beat = 0;; ++beat) {
        const auto start = static_cast<size_t>(beat * period);
        if (start + 96 >= signal.size()) break;
        for (int j = 0; j < 96; ++j) {
            signal[start + j] += static_cast<float>(
                0.04 * std::exp(-j / 22.0) * std::sin(2.0 * M_PI * 5000.0 * j / kFs));
        }
    }

    DspChain chain(kFs);
    chain.SetAnalysisMode(DspChain::AnalysisMode::kCorrelation);
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
    EXPECT_EQ(last.activeBph, 28800);
    EXPECT_TRUE(last.rateValid);
    if (std::fabs(last.secPerDay - 14.4) > 1.5) {
        std::printf("  44100 rate: %.3f s/d (expected ~14.4)\n", last.secPerDay);
    }
    EXPECT_NEAR(last.secPerDay, 14.4, 1.5);
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
