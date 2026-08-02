#include <cstdint>

#include "TestFramework.h"
#include "deadaccurate/BeatRateDetector.h"

using deadaccurate::BeatRateDetector;

namespace {

constexpr int kSampleRate = 48000;

int64_t PeriodFrames(int bph) {
    return static_cast<int64_t>(3600.0 / bph * kSampleRate);
}

}  // namespace

TEST(BeatRateDetector, LocksEveryStandardRateFromCleanOnsets) {
    for (const int bph : BeatRateDetector::kStandardRatesBph) {
        BeatRateDetector detector(kSampleRate);
        const int64_t period = PeriodFrames(bph);
        for (int i = 0; i < 40; ++i) {
            detector.AddOnset(i * period);
        }
        EXPECT_TRUE(detector.locked());
        EXPECT_EQ(detector.lockedBph(), bph);
    }
}

TEST(BeatRateDetector, LocksDespiteMissedTicksAndJitter) {
    BeatRateDetector detector(kSampleRate);
    const int64_t period = PeriodFrames(28800);  // 6000 frames
    int64_t jitter = 0;
    for (int i = 0; i < 60; ++i) {
        if (i % 5 == 4) {
            continue;  // every 5th tick missed -> double intervals
        }
        jitter = (jitter + 17) % 40;  // deterministic +/-0.4 ms wobble
        detector.AddOnset(i * period + jitter - 20);
    }
    EXPECT_TRUE(detector.locked());
    EXPECT_EQ(detector.lockedBph(), 28800);
}

TEST(BeatRateDetector, StaysSearchingOnRandomIntervals) {
    BeatRateDetector detector(kSampleRate);
    // LCG-spaced onsets: no consistent period to lock onto.
    uint32_t state = 99;
    int64_t frame = 0;
    for (int i = 0; i < 60; ++i) {
        state = state * 1664525u + 1013904223u;
        frame += 3000 + static_cast<int64_t>(state % 9000);
        detector.AddOnset(frame);
    }
    EXPECT_TRUE(!detector.locked());
}

TEST(BeatRateDetector, UnlocksWhenTheSignalDegrades) {
    BeatRateDetector detector(kSampleRate);
    const int64_t period = PeriodFrames(28800);
    int64_t frame = 0;
    for (int i = 0; i < 40; ++i) {
        frame += period;
        detector.AddOnset(frame);
    }
    EXPECT_TRUE(detector.locked());

    // Interval structure collapses; the 8 s window flushes clean intervals
    // out and the locked rate's score decays below the unlock threshold.
    uint32_t state = 7;
    for (int i = 0; i < 80 && detector.locked(); ++i) {
        state = state * 1664525u + 1013904223u;
        frame += 2500 + static_cast<int64_t>(state % 8000);
        detector.AddOnset(frame);
    }
    EXPECT_TRUE(!detector.locked());
}

TEST(BeatRateDetector, DistinguishesNeighboringRates) {
    // 25200 vs 28800: periods 142.9 ms vs 125 ms — must not cross-lock.
    BeatRateDetector detector(kSampleRate);
    const int64_t period = PeriodFrames(25200);
    for (int i = 0; i < 40; ++i) {
        detector.AddOnset(i * period);
    }
    EXPECT_EQ(detector.lockedBph(), 25200);
}
