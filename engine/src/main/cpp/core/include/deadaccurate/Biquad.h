#pragma once

namespace deadaccurate {

// One second-order IIR section (RBJ cookbook coefficients), transposed
// direct form II with double state for numerical safety at audio rates.
class Biquad {
public:
    static Biquad LowPass(double sampleRate, double cutoffHz, double q);
    static Biquad HighPass(double sampleRate, double cutoffHz, double q);

    float Process(float x) {
        const double y = b0_ * x + z1_;
        z1_ = b1_ * x - a1_ * y + z2_;
        z2_ = b2_ * x - a2_ * y;
        return static_cast<float>(y);
    }

    void Reset() {
        z1_ = 0.0;
        z2_ = 0.0;
    }

private:
    Biquad(double b0, double b1, double b2, double a1, double a2)
        : b0_(b0), b1_(b1), b2_(b2), a1_(a1), a2_(a2) {}

    double b0_, b1_, b2_, a1_, a2_;
    double z1_ = 0.0;
    double z2_ = 0.0;
};

// The tick pre-filter (docs/03-signal-processing.md section 1): 2nd-order
// Butterworth high-pass at `lowCutHz` cascaded with 2nd-order Butterworth
// low-pass at `highCutHz` — 4th order overall, stripping handling rumble
// and room noise below the band and piezo resonance spikes above it.
class BandPassFilter {
public:
    BandPassFilter(double sampleRate, double lowCutHz, double highCutHz);

    float Process(float x) { return lowPass_.Process(highPass_.Process(x)); }

    void Reset();

private:
    Biquad highPass_;
    Biquad lowPass_;
};

// Two cascaded BandPassFilters: 4th-order skirts. The correlation bands
// need this — on real phone recordings the sub-kHz rumble sits 25 dB above
// the tick band, and 2nd-order roll-off leaks enough of it to bury the
// fold score.
class SteepBandPassFilter {
public:
    SteepBandPassFilter(double sampleRate, double lowCutHz, double highCutHz)
        : first_(sampleRate, lowCutHz, highCutHz),
          second_(sampleRate, lowCutHz, highCutHz) {}

    float Process(float x) { return second_.Process(first_.Process(x)); }

private:
    BandPassFilter first_;
    BandPassFilter second_;
};

}  // namespace deadaccurate
