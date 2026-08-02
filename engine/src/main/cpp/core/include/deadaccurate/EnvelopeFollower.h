#pragma once

#include <cmath>

namespace deadaccurate {

// Full-wave rectifier + fast-attack / slow-release one-pole smoother
// (docs/03-signal-processing.md section 2). Both the gate and the tick
// detector consume this envelope.
class EnvelopeFollower {
public:
    EnvelopeFollower(int sampleRate, double attackMs, double releaseMs)
        : attackAlpha_(Alpha(sampleRate, attackMs)),
          releaseAlpha_(Alpha(sampleRate, releaseMs)) {}

    float Process(float x) {
        const float rectified = std::fabs(x);
        const float alpha = rectified > envelope_ ? attackAlpha_ : releaseAlpha_;
        envelope_ += alpha * (rectified - envelope_);
        return envelope_;
    }

    void Reset() { envelope_ = 0.0f; }

private:
    static float Alpha(int sampleRate, double timeConstantMs) {
        const double timeConstantSeconds = timeConstantMs / 1000.0;
        return static_cast<float>(
            1.0 - std::exp(-1.0 / (timeConstantSeconds * sampleRate)));
    }

    const float attackAlpha_;
    const float releaseAlpha_;
    float envelope_ = 0.0f;
};

}  // namespace deadaccurate
