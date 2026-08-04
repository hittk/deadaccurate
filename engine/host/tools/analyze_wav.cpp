// Offline analysis harness: run a WAV recording through the exact DspChain
// in both modes and print what each one sees. This is how real phone
// recordings become tuning data:
//   ./analyze_wav recording.wav
#include <cstdint>
#include <cstdio>
#include <cstring>
#include <fstream>
#include <vector>

#include "deadaccurate/DspChain.h"

namespace {

struct Wav {
    int sampleRate = 0;
    std::vector<float> samples;
};

bool ParseWav(const char* path, Wav* out) {
    std::ifstream file(path, std::ios::binary);
    if (!file) {
        std::fprintf(stderr, "cannot open %s\n", path);
        return false;
    }
    std::vector<char> bytes((std::istreambuf_iterator<char>(file)),
                            std::istreambuf_iterator<char>());
    if (bytes.size() < 44 || std::memcmp(bytes.data(), "RIFF", 4) != 0 ||
        std::memcmp(bytes.data() + 8, "WAVE", 4) != 0) {
        std::fprintf(stderr, "not a WAV file\n");
        return false;
    }
    auto u16 = [&](size_t p) {
        return static_cast<uint16_t>(static_cast<uint8_t>(bytes[p]) |
                                     (static_cast<uint8_t>(bytes[p + 1]) << 8));
    };
    auto u32 = [&](size_t p) {
        return static_cast<uint32_t>(static_cast<uint8_t>(bytes[p]) |
                                     (static_cast<uint8_t>(bytes[p + 1]) << 8) |
                                     (static_cast<uint8_t>(bytes[p + 2]) << 16) |
                                     (static_cast<uint8_t>(bytes[p + 3]) << 24));
    };
    int format = 0;
    int channels = 0;
    int bits = 0;
    size_t pos = 12;
    while (pos + 8 <= bytes.size()) {
        const uint32_t size = u32(pos + 4);
        const size_t body = pos + 8;
        if (body + size > bytes.size()) break;
        if (std::memcmp(bytes.data() + pos, "fmt ", 4) == 0) {
            format = u16(body);
            channels = u16(body + 2);
            out->sampleRate = static_cast<int>(u32(body + 4));
            bits = u16(body + 14);
        } else if (std::memcmp(bytes.data() + pos, "data", 4) == 0) {
            if (format != 1 || bits != 16 || channels < 1 || channels > 2) {
                std::fprintf(stderr, "need 16-bit PCM mono/stereo (format %d, %d bits, %d ch)\n",
                             format, bits, channels);
                return false;
            }
            const size_t frames = size / 2 / static_cast<size_t>(channels);
            out->samples.resize(frames);
            for (size_t i = 0; i < frames; ++i) {
                float sum = 0.0f;
                for (int c = 0; c < channels; ++c) {
                    const size_t p = body + (i * static_cast<size_t>(channels) +
                                             static_cast<size_t>(c)) * 2;
                    sum += static_cast<int16_t>(u16(p)) / 32768.0f;
                }
                out->samples[i] = sum / static_cast<float>(channels);
            }
        }
        pos = body + size + (size & 1);
    }
    return !out->samples.empty() && out->sampleRate > 0;
}

void Run(const Wav& wav, deadaccurate::DspChain::AnalysisMode mode, const char* name) {
    deadaccurate::DspChain chain(wav.sampleRate);
    chain.SetAnalysisMode(mode);
    deadaccurate::DspChain::Output output;
    deadaccurate::DspChain::RateFrame last{};
    int ticks = 0;
    int accepted = 0;
    size_t frames = 0;
    constexpr size_t kChunk = 4096;
    for (size_t offset = 0; offset < wav.samples.size(); offset += kChunk) {
        const size_t n = std::min(kChunk, wav.samples.size() - offset);
        chain.Process(wav.samples.data() + offset, n, output);
        for (const auto& tick : output.ticks) {
            ++ticks;
            if (tick.accepted) ++accepted;
        }
        for (const auto& rate : output.rates) {
            last = rate;
            const double t = static_cast<double>(offset) / wav.sampleRate;
            std::printf("[%s] t=%5.1fs active=%d detected=%d valid=%d rate=%+7.2f s/d be=%5.2f ms n=%d\n",
                        name, t, rate.activeBph, rate.detectedBph, rate.rateValid ? 1 : 0,
                        rate.secPerDay, rate.beatErrorMs, rate.tickCount);
        }
        frames += n;
    }
    std::printf("== %s: %.1f s, ticks=%d (accepted %d), final active=%d detected=%d valid=%d rate=%+.2f s/d be=%.2f ms\n",
                name, static_cast<double>(frames) / wav.sampleRate, ticks, accepted,
                last.activeBph, last.detectedBph, last.rateValid ? 1 : 0, last.secPerDay,
                last.beatErrorMs);
    if (mode == deadaccurate::DspChain::AnalysisMode::kCorrelation) {
        std::printf("   band signature (score per 0.8-3k / 3-8k / 8-16k):\n");
        for (const int bph : {14400, 16200, 18000, 19800, 21600, 25200, 28800, 36000}) {
            std::printf("     %5d: %5.1f %5.1f %5.1f\n", bph,
                        chain.FoldingScoreForDebug(bph, 0),
                        chain.FoldingScoreForDebug(bph, 1),
                        chain.FoldingScoreForDebug(bph, 2));
        }
    }
}

}  // namespace

int main(int argc, char** argv) {
    if (argc != 2) {
        std::fprintf(stderr, "usage: %s recording.wav\n", argv[0]);
        return 1;
    }
    Wav wav;
    if (!ParseWav(argv[1], &wav)) {
        return 1;
    }
    std::printf("%s: %d Hz, %.1f s\n", argv[1], wav.sampleRate,
                static_cast<double>(wav.samples.size()) / wav.sampleRate);
    Run(wav, deadaccurate::DspChain::AnalysisMode::kEdge, "edge");
    Run(wav, deadaccurate::DspChain::AnalysisMode::kCorrelation, "corr");
    return 0;
}
