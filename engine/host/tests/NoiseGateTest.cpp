#include "TestFramework.h"
#include "deadaccurate/NoiseGate.h"

using deadaccurate::NoiseGate;

namespace {

// Small sample rate keeps the counts human-readable: hold = 15 frames,
// calibration = 1000 frames.
constexpr int kSampleRate = 1000;
constexpr float kAmbient = 0.001f;

NoiseGate CalibratedGate() {
    NoiseGate gate(kSampleRate);
    for (int i = 0; i < kSampleRate; ++i) {
        gate.Process(kAmbient);
    }
    return gate;
}

}  // namespace

TEST(NoiseGate, StaysClosedWhileCalibrating) {
    NoiseGate gate(kSampleRate);
    EXPECT_TRUE(gate.calibrating());
    // A loud burst during calibration must not open the gate.
    for (int i = 0; i < 10; ++i) {
        EXPECT_TRUE(!gate.Process(0.5f));
    }
}

TEST(NoiseGate, OpensOnBurstAboveMarginAndNotOnAmbient) {
    NoiseGate gate = CalibratedGate();
    EXPECT_TRUE(!gate.calibrating());
    EXPECT_TRUE(!gate.Process(kAmbient));
    // 12 dB margin over a 0.001 floor -> threshold ~0.004.
    EXPECT_TRUE(!gate.Process(0.003f));
    EXPECT_TRUE(gate.Process(0.01f));
}

TEST(NoiseGate, HoldBridgesGapsInsideOneTick) {
    NoiseGate gate = CalibratedGate();
    EXPECT_TRUE(gate.Process(0.01f));
    // 10 frames (10 ms) below the close threshold: shorter than the 15 ms
    // hold, so the multi-pulse tick stays one burst.
    for (int i = 0; i < 10; ++i) {
        EXPECT_TRUE(gate.Process(0.0005f));
    }
    EXPECT_TRUE(gate.Process(0.01f));
}

TEST(NoiseGate, ClosesAfterHoldExpires) {
    NoiseGate gate = CalibratedGate();
    EXPECT_TRUE(gate.Process(0.01f));
    bool open = true;
    for (int i = 0; i < 40 && open; ++i) {
        open = gate.Process(0.0005f);
    }
    EXPECT_TRUE(!open);
}

TEST(NoiseGate, TrimRaisesAndLowersTheThreshold) {
    NoiseGate gate = CalibratedGate();
    const float baseline = gate.openThresholdDb();

    // Threshold arithmetic first (Process also adapts the floor slightly,
    // so behavior checks come after).
    gate.SetTrimDb(10.0f);
    EXPECT_NEAR(gate.openThresholdDb(), baseline + 10.0f, 0.01);
    gate.SetTrimDb(-10.0f);
    EXPECT_NEAR(gate.openThresholdDb(), baseline - 10.0f, 0.01);

    gate.SetTrimDb(10.0f);
    EXPECT_TRUE(!gate.Process(0.01f));  // no longer clears the raised gate
    gate.SetTrimDb(-10.0f);
    EXPECT_TRUE(gate.Process(0.0015f));  // faint tick now clears it
}

TEST(NoiseGate, AdaptsFloorTowardAmbientDrift) {
    NoiseGate gate = CalibratedGate();
    const float before = gate.openThresholdDb();
    // Ambient creeps up 6 dB; several adaptation time constants later the
    // threshold has followed.
    for (int i = 0; i < kSampleRate * 8; ++i) {
        gate.Process(0.002f);
    }
    EXPECT_TRUE(gate.openThresholdDb() > before + 4.0f);
}

TEST(NoiseGate, RecalibratesWhenStuckOpen) {
    // Field-observed failure: input AGC ramps ambient far above the
    // calibrated threshold after the calibration window, jamming the gate
    // open so no tick edge can ever fire. The watchdog must re-measure.
    NoiseGate gate = CalibratedGate();  // floor 0.001, threshold ~0.004
    EXPECT_TRUE(gate.Process(0.05f));   // ambient jumps and stays high

    // Held open for 3 s -> watchdog triggers a recalibration.
    bool sawRecalibration = false;
    for (int i = 0; i < kSampleRate * 4 && !sawRecalibration; ++i) {
        gate.Process(0.05f);
        sawRecalibration = gate.calibrating();
    }
    EXPECT_TRUE(sawRecalibration);

    // Finish calibrating against the new ambient; the gate now sits closed
    // under it and a louder tick opens it again.
    for (int i = 0; i < kSampleRate; ++i) {
        gate.Process(0.05f);
    }
    EXPECT_TRUE(!gate.calibrating());
    EXPECT_TRUE(!gate.Process(0.05f));
    EXPECT_TRUE(gate.Process(0.5f));
}

TEST(NoiseGate, TickBurstsDoNotTriggerTheWatchdog) {
    NoiseGate gate = CalibratedGate();
    // 8 seconds of realistic ticking: 30 ms bursts every 125 ms.
    for (int beat = 0; beat < 64; ++beat) {
        for (int i = 0; i < 30; ++i) {
            gate.Process(0.05f);
        }
        for (int i = 0; i < 95; ++i) {
            gate.Process(kAmbient);
        }
        EXPECT_TRUE(!gate.calibrating());
    }
}

TEST(NoiseGate, MedianCalibrationIgnoresTicksInTheWindow) {
    NoiseGate gate(kSampleRate);
    // 20% of calibration samples are tick-elevated; the median must land on
    // ambient, keeping the threshold well below the ticks.
    for (int i = 0; i < kSampleRate; ++i) {
        gate.Process(i % 5 == 0 ? 0.2f : kAmbient);
    }
    EXPECT_TRUE(!gate.calibrating());
    EXPECT_TRUE(gate.Process(0.01f));
}
