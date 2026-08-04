// Scratch diagnostic: feed the synthetic low-SNR fixture straight into
// FoldingAnalyzer and print per-channel scores over time.
#include <cmath>
#include <cstdint>
#include <cstdio>
#include <cstring>
#include <cstdlib>
#include <fstream>
#include <vector>

#include "deadaccurate/Biquad.h"
#include "deadaccurate/EnvelopeFollower.h"
#include "deadaccurate/FoldingAnalyzer.h"

using namespace deadaccurate;

int main(int argc, char** argv) {
    constexpr int kFs = 48000;
    std::vector<float> signal;
    if (argc > 1 && std::strcmp(argv[1], "-") != 0) {
        std::ifstream f(argv[1], std::ios::binary);
        std::vector<char> bytes((std::istreambuf_iterator<char>(f)),
                                std::istreambuf_iterator<char>());
        // Assume the app's own 44-byte-header 16-bit mono output.
        const size_t n = (bytes.size() - 44) / 2;
        signal.resize(n);
        for (size_t i = 0; i < n; ++i) {
            int16_t v;
            std::memcpy(&v, bytes.data() + 44 + 2 * i, 2);
            signal[i] = v / 32768.0f;
        }
        std::printf("loaded %zu samples (%.1f s)\n", n, n / 48000.0);
    } else if (argc > 3) {
        // "-" bph carrierHz : the lock-matrix generator (rumble noise).
        const int bph = atoi(argv[2]);
        const double carrier = atof(argv[3]);
        const double period = 3600.0 / bph * kFs;
        uint32_t lcg = static_cast<uint32_t>(bph + static_cast<int>(carrier));
        signal.assign(20 * kFs, 0.0f);
        float rumble = 0.0f;
        for (auto& s : signal) {
            lcg = lcg * 1664525u + 1013904223u;
            const float w1 = ((static_cast<float>(lcg >> 8) / (1 << 24)) * 2.0f - 1.0f);
            lcg = lcg * 1664525u + 1013904223u;
            const float w2 = ((static_cast<float>(lcg >> 8) / (1 << 24)) * 2.0f - 1.0f);
            rumble += 0.026f * (0.10f * w1 - rumble);
            s = rumble + 0.01f * w2;
        }
        for (size_t beat = 0;; ++beat) {
            const auto start = static_cast<size_t>(beat * period);
            if (start + 96 >= signal.size()) break;
            for (int j = 0; j < 96; ++j) {
                signal[start + j] += static_cast<float>(
                    0.05 * std::exp(-j / 24.0) * std::sin(2.0 * M_PI * carrier * j / kFs));
            }
        }
    } else {
        uint32_t lcg = 99;
        signal.assign(60 * kFs, 0.0f);
        for (auto& s : signal) {
            lcg = lcg * 1664525u + 1013904223u;
            s = 0.02f * ((static_cast<float>(lcg >> 8) / (1 << 24)) * 2.0f - 1.0f);
        }
        const double period = 5999.0;
        const int beatErrorFrames = argc > 2 ? atoi(argv[2]) : 0;
        for (size_t beat = 0;; ++beat) {
            const auto start = static_cast<size_t>(beat * period) +
                               (beat % 2 == 1 ? beatErrorFrames : 0);
            if (start + 96 >= signal.size()) break;
            for (int j = 0; j < 96; ++j) {
                signal[start + j] += static_cast<float>(
                    0.04 * std::exp(-j / 24.0) * std::sin(2.0 * M_PI * 5000.0 * j / kFs));
            }
        }
    }

    const double edges[4] = {800, 3000, 8000, 16000};
    std::vector<SteepBandPassFilter> bands;
    std::vector<EnvelopeFollower> envs;
    for (int c = 0; c < 3; ++c) {
        bands.emplace_back(kFs, edges[c], edges[c + 1]);
        envs.emplace_back(kFs, 0.5, 5.0);
    }
    FoldingAnalyzer folding(kFs);
    FoldingAnalyzer::Snapshot snap;
    int64_t i = 0;
    for (const float s : signal) {
        float e[3];
        for (int c = 0; c < 3; ++c) e[c] = std::fabs(bands[c].Process(s));
        if (folding.Push(e, &snap) && (i / (kFs / 2)) % 10 == 0) {
            std::printf("t=%5.1f active=%d det=%d | 21600: %4.1f %4.1f %4.1f | 28800: %4.1f %4.1f %4.1f | 43200x10800: %4.1f %4.1f\n",
                        static_cast<double>(i) / kFs, snap.activeBph, snap.detectedBph,
                        folding.ScoreForDebug(21600, 0), folding.ScoreForDebug(21600, 1),
                        folding.ScoreForDebug(21600, 2),
                        folding.ScoreForDebug(28800, 0), folding.ScoreForDebug(28800, 1),
                        folding.ScoreForDebug(28800, 2),
                        folding.ScoreForDebug(14400, 0), folding.ScoreForDebug(16200, 0));
        }
        ++i;
    }
    return 0;
}
