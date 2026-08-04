#include <cstdint>
#include <cstring>
#include <fstream>
#include <sstream>
#include <string>
#include <vector>

#include "TestFramework.h"
#include "deadaccurate/DspChain.h"

using deadaccurate::DspChain;

namespace {

// Loads the app's own recorder output (44-byte header, 16-bit PCM mono).
std::vector<float> LoadFixture(const std::string& name, int* sampleRate) {
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

struct FixtureSpec {
    std::string file;
    int expectedBph = 0;
    double maxBeatErrorMs = -1.0;  // negative = don't check
};

std::vector<FixtureSpec> LoadManifest() {
    std::vector<FixtureSpec> specs;
    std::ifstream manifest(std::string(FIXTURES_DIR) + "/manifest.txt");
    std::string line;
    while (std::getline(manifest, line)) {
        if (line.empty() || line[0] == '#') {
            continue;
        }
        std::istringstream row(line);
        FixtureSpec spec;
        row >> spec.file >> spec.expectedBph;
        row >> spec.maxBeatErrorMs;  // optional; stays negative on failure
        if (!spec.file.empty() && spec.expectedBph > 0) {
            specs.push_back(spec);
        }
    }
    return specs;
}

}  // namespace

// Every recorded watch in fixtures/manifest.txt must lock at its known rate
// in correlation mode. Real recordings are the ground truth the synthetic
// fixtures approximate — a DSP change that breaks one breaks the build.
// Catalogue with movement identities: docs/05-movement-catalogue.md.
TEST(RealRecording, EveryManifestFixtureLocksAtItsKnownRate) {
    const std::vector<FixtureSpec> specs = LoadManifest();
    EXPECT_TRUE(!specs.empty());

    for (const auto& spec : specs) {
        int sampleRate = 0;
        const std::vector<float> samples = LoadFixture(spec.file, &sampleRate);
        if (samples.empty()) {
            std::printf("  missing fixture: %s\n", spec.file.c_str());
            EXPECT_TRUE(!samples.empty());
            continue;
        }

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

        if (last.activeBph != spec.expectedBph) {
            std::printf("  %s: expected %d, got active=%d detected=%d\n",
                        spec.file.c_str(), spec.expectedBph, last.activeBph,
                        last.detectedBph);
        }
        EXPECT_EQ(last.activeBph, spec.expectedBph);
        EXPECT_EQ(last.detectedBph, spec.expectedBph);
        if (spec.maxBeatErrorMs >= 0.0 && last.beatErrorMs >= 0.0f) {
            EXPECT_TRUE(last.beatErrorMs < spec.maxBeatErrorMs);
        }
    }
}
