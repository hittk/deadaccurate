#pragma once

#include <cstdint>
#include <deque>
#include <vector>

namespace deadaccurate {

// Balance amplitude from tick micro-structure (M5.3). Each tick is three
// sub-pulses — unlocking, impulse, drop — and the first-to-last interval
// ("lift time") is how long the balance takes to sweep the lift angle
// through its swing center. With oscillation period T (two beats) and
// lift angle γ:
//
//   amplitude = γ / (2 · sin(π · liftTime / T))
//
// A 28800 movement at 270° with γ=52° has liftTime ≈ 7.7 ms, so the
// sub-pulses sit a few ms apart: this consumes the *band-passed* signal
// with its own fast follower — the edge path's 5 ms-release envelope
// (built for gating) would smear the pulses together.
class AmplitudeAnalyzer {
public:
    struct Estimate {
        bool valid = false;
        float amplitudeDeg = 0.0f;
        float liftTimeMs = 0.0f;  // median over the recent window
    };

    explicit AmplitudeAnalyzer(int sampleRate);

    // One band-passed sample (rectified internally). Call for every frame.
    void Push(float bandPassed);

    // The edge path detected a tick onset at this absolute frame. Analysis
    // is deferred until the sub-pulse window has fully streamed in.
    void OnTick(int64_t frameIndex);

    // Current estimate for the active rate and configured lift angle.
    Estimate Current(int bph, double liftAngleDeg) const;

    void Reset();

private:
    void ProcessPending();
    void AnalyzeWindow(int64_t startFrame);

    // Fast follower: sharp enough to keep ~2 ms sub-pulses distinct.
    static constexpr double kAttackMs = 0.15;
    static constexpr double kReleaseMs = 1.2;
    // Window: 2 ms before the onset (leading edge of the unlocking pulse)
    // to 32 ms after (a 150° 18000 movement lifts for ~28 ms).
    static constexpr double kPreMs = 2.0;
    static constexpr double kPostMs = 32.0;
    // Sub-pulse acceptance: local maxima above this fraction of the window
    // peak, at least this far apart.
    static constexpr double kPulseFraction = 0.30;
    static constexpr double kMinPulseSepMs = 1.5;
    static constexpr int kMinPulses = 2;
    static constexpr int kMaxPulses = 5;
    static constexpr double kMinLiftMs = 1.5;
    static constexpr double kMaxLiftMs = 30.0;
    // Aggregation: median over fresh ticks; the spread gate rejects
    // signals whose sub-pulse structure is not actually resolvable.
    static constexpr int kMaxSamples = 64;
    static constexpr int kMinSamplesForEstimate = 10;
    static constexpr double kFreshSeconds = 10.0;
    static constexpr double kMaxSpreadFraction = 0.20;
    static constexpr double kMinSpreadFloorMs = 0.4;
    // Plausible mechanical range; outside it the lift angle is wrong or
    // the pulses were misread — show nothing rather than nonsense.
    static constexpr double kMinAmplitudeDeg = 90.0;
    static constexpr double kMaxAmplitudeDeg = 360.0;

    const int sampleRate_;
    const float attackCoeff_;
    const float releaseCoeff_;
    const int preFrames_;
    const int windowFrames_;
    const int minSepFrames_;

    float env_ = 0.0f;
    std::vector<float> ring_;
    int64_t frames_ = 0;

    std::deque<int64_t> pendingTicks_;
    // (tick frame, measured lift ms)
    std::deque<std::pair<int64_t, double>> liftTimes_;
};

}  // namespace deadaccurate
