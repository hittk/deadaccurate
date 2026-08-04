#pragma once

#include <cstddef>
#include <vector>

namespace deadaccurate {

// Adaptive noise gate on the envelope signal (docs/03-signal-processing.md
// section 3): percentile-calibrated floor, open/close hysteresis, hold time,
// slow floor adaptation while closed, and a user trim on top of the margin.
class NoiseGate {
public:
    struct Config {
        double calibrationSeconds = 1.0;
        int calibrationDecimation = 16;   // envelope is smooth; 1-in-16 is plenty
        // Median: robust to the watch already ticking during calibration
        // (docs/03-signal-processing.md section 3).
        double calibrationPercentile = 0.5;
        float marginDb = 12.0f;
        float hysteresisDb = 6.0f;
        double holdMs = 15.0;
        double adaptSeconds = 2.0;        // floor adaptation time constant
        // A tick burst holds the gate open for tens of ms; continuously
        // open for this long means the floor estimate is stale (e.g. the
        // input path's AGC ramped ambient past the threshold after
        // calibration) and the gate must re-measure or it will never see
        // another tick edge.
        double stuckOpenSeconds = 3.0;
    };

    explicit NoiseGate(int sampleRate);  // default Config
    NoiseGate(int sampleRate, const Config& config);

    // Re-estimate the ambient floor; the gate stays closed while calibrating.
    void StartCalibration();

    // User slider, roughly +/-15 dB around the calibrated threshold (FR-3).
    void SetTrimDb(float trimDb);

    // One envelope sample in, gate state out.
    bool Process(float envelope);

    bool calibrating() const { return calibrating_; }
    bool open() const { return open_; }
    float openThresholdLinear() const { return floor_ * openFactor_; }
    float openThresholdDb() const;

private:
    void FinishCalibration();
    void UpdateFactors();

    const int sampleRate_;
    const Config config_;
    const int holdFrames_;
    const int stuckOpenFrames_;
    const float adaptAlpha_;

    bool calibrating_ = false;
    std::vector<float> calibrationSamples_;
    size_t calibrationTarget_ = 0;
    int decimationCounter_ = 0;

    float floor_;
    float trimDb_ = 0.0f;
    float openFactor_ = 1.0f;   // linear factor for margin + trim
    float closeFactor_ = 1.0f;  // openFactor_ minus hysteresis

    bool open_ = false;
    int holdRemaining_ = 0;
    int openStreak_ = 0;
};

}  // namespace deadaccurate
