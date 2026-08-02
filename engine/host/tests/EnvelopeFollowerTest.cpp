#include "TestFramework.h"
#include "deadaccurate/EnvelopeFollower.h"

using deadaccurate::EnvelopeFollower;

namespace {
constexpr int kSampleRate = 48000;
}

TEST(EnvelopeFollower, AttacksFast) {
    EnvelopeFollower follower(kSampleRate, 0.5, 5.0);
    float env = 0.0f;
    // 1 ms of full-scale input = two attack time constants -> ~0.86.
    for (int i = 0; i < 48; ++i) {
        env = follower.Process(1.0f);
    }
    EXPECT_TRUE(env > 0.8f);
}

TEST(EnvelopeFollower, ReleasesSlow) {
    EnvelopeFollower follower(kSampleRate, 0.5, 5.0);
    for (int i = 0; i < 480; ++i) {
        follower.Process(1.0f);
    }
    float env = 1.0f;
    // 5 ms of silence = one release time constant -> ~1/e of the start.
    for (int i = 0; i < 240; ++i) {
        env = follower.Process(0.0f);
    }
    EXPECT_NEAR(env, 0.37, 0.08);
}

TEST(EnvelopeFollower, RectifiesNegativeInput) {
    EnvelopeFollower follower(kSampleRate, 0.5, 5.0);
    float env = 0.0f;
    for (int i = 0; i < 48; ++i) {
        env = follower.Process(-1.0f);
    }
    EXPECT_TRUE(env > 0.8f);
}
