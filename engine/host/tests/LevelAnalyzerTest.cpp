#include <cmath>
#include <vector>

#include "TestFramework.h"
#include "deadaccurate/LevelAnalyzer.h"

using deadaccurate::LevelAnalyzer;

TEST(LevelAnalyzer, ConstantSignalReadsItsOwnLevel) {
    LevelAnalyzer analyzer(100);
    std::vector<float> samples(100, 0.5f);
    std::vector<LevelAnalyzer::Level> out;
    analyzer.Push(samples.data(), samples.size(), out);

    EXPECT_EQ(out.size(), 1u);
    // RMS and peak of a constant 0.5 are both 0.5 -> -6.02 dBFS.
    EXPECT_NEAR(out[0].rmsDb, -6.02, 0.05);
    EXPECT_NEAR(out[0].peakDb, -6.02, 0.05);
}

TEST(LevelAnalyzer, FullScaleSineReadsMinus3dbRmsAndZeroDbPeak) {
    constexpr size_t kHop = 1000;
    LevelAnalyzer analyzer(kHop);
    std::vector<float> sine(kHop);
    for (size_t i = 0; i < kHop; ++i) {
        // 10 full cycles across the hop so the RMS is exact.
        sine[i] = std::sin(2.0 * M_PI * 10.0 * static_cast<double>(i) / kHop);
    }
    std::vector<LevelAnalyzer::Level> out;
    analyzer.Push(sine.data(), sine.size(), out);

    EXPECT_EQ(out.size(), 1u);
    EXPECT_NEAR(out[0].rmsDb, -3.01, 0.05);
    EXPECT_NEAR(out[0].peakDb, 0.0, 0.05);
}

TEST(LevelAnalyzer, SilenceReadsTheFloor) {
    LevelAnalyzer analyzer(10);
    std::vector<float> silence(10, 0.0f);
    std::vector<LevelAnalyzer::Level> out;
    analyzer.Push(silence.data(), silence.size(), out);

    EXPECT_EQ(out.size(), 1u);
    EXPECT_EQ(out[0].rmsDb, -120.0f);
    EXPECT_EQ(out[0].peakDb, -120.0f);
}

TEST(LevelAnalyzer, EmitsAcrossChunkBoundaries) {
    LevelAnalyzer analyzer(100);
    std::vector<float> chunk(30, 0.25f);
    std::vector<LevelAnalyzer::Level> out;
    // 10 chunks of 30 samples = 300 samples = 3 complete hops.
    for (int i = 0; i < 10; ++i) {
        analyzer.Push(chunk.data(), chunk.size(), out);
    }
    EXPECT_EQ(out.size(), 3u);
    for (const auto& level : out) {
        EXPECT_NEAR(level.rmsDb, -12.04, 0.05);
    }
}
