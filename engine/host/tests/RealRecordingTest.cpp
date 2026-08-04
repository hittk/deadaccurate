#include <cstdint>
#include <cstring>
#include <fstream>
#include <vector>

#include "TestFramework.h"
#include "deadaccurate/DspChain.h"

using deadaccurate::DspChain;

namespace {

// Loads the app's own recorder output (44-byte header, 16-bit PCM mono).
std::vector<float> LoadFixture(const char* name, int* sampleRate) {
    std::ifstream file(std::string(FIXTURES_DIR) + "/" + name, std::ios::binary);
    std::vector<char> bytes((std::istreambuf_iterator<char>(file)),
                            std::istreambuf_iterator<char>());
    std::vector<float> samples;
    if (bytes.size() <= 44 || std::memcmp(bytes.data(), "RIFF", 4) != 0) {
        return samples;
    }
    std::memcpy(sampleRate, bytes.data() + 24, 4);
    const size_t n = (bytes.size() - 44) / 2;
    samples.resize(n);
    for (size_t i = 0; i < n; ++i) {
        int16_t v;
        std::memcpy(&v, bytes.data() + 44 + 2 * i, 2);
        samples[i] = v / 32768.0f;
    }
    return samples;
}

}  // namespace

// The recording that drove the correlation-mode tuning: a real 28800 bph
// movement on a phone's built-in mic (voice-recognition source, AGC), tick
// energy in the 8-16 kHz band, ambient rumble dominating below 3 kHz.
// Edge mode is blind here; correlation mode must lock. If a DSP change
// breaks this test, it breaks the app on real phones.
TEST(RealRecording, CorrelationLocksThePhoneMicMovement) {
    int sampleRate = 0;
    const std::vector<float> samples = LoadFixture("phone_mic_28800.wav", &sampleRate);
    EXPECT_TRUE(!samples.empty());
    EXPECT_EQ(sampleRate, 48000);

    DspChain chain(sampleRate);
    chain.SetAnalysisMode(DspChain::AnalysisMode::kCorrelation);
    DspChain::Output output;
    DspChain::RateFrame last{};
    constexpr size_t kChunk = 4096;
    for (size_t offset = 0; offset < samples.size(); offset += kChunk) {
        const size_t n = std::min(kChunk, samples.size() - offset);
        chain.Process(samples.data() + offset, n, output);
        for (const auto& rate : output.rates) {
            last = rate;
        }
    }

    EXPECT_EQ(last.activeBph, 28800);
    EXPECT_EQ(last.detectedBph, 28800);
    // The reference timegrapher app read ~0.3 ms on this movement; the
    // 30 s clip ends before the rate window fills, so beat error is the
    // measurable quantity here.
    EXPECT_TRUE(last.beatErrorMs >= 0.0f);
    EXPECT_TRUE(last.beatErrorMs < 0.6f);
}
