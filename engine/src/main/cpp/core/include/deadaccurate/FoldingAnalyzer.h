#pragma once

#include <array>
#include <cstddef>
#include <cstdint>
#include <deque>
#include <vector>

namespace deadaccurate {

// Low-SNR analysis ("correlation mode"): instead of deciding per tick
// whether the envelope crossed a gate, energy is folded by candidate beat
// periods so hundreds of beats integrate coherently — ticks stack into a
// profile peak while noise averages flat. Works when individual ticks are
// only a few dB above ambient (a phone microphone). The trade is latency:
// estimates firm up over tens of seconds instead of per tick.
//
// Multiband: tick energy lands in different bands on different hardware —
// a real phone-mic recording put it at 8-16 kHz while room noise dominated
// below 3 kHz. The analyzer takes several band-limited envelope channels,
// scores every candidate rate in every channel, and locks on the best.
class FoldingAnalyzer {
public:
    static constexpr int kChannels = 3;

    struct Snapshot {
        int activeBph = 0;    // override or folding lock; 0 = searching
        int detectedBph = 0;  // folding lock (keeps reporting under override)
        bool overridden = false;
        bool rateValid = false;
        float secPerDay = 0.0f;
        int beatCount = 0;         // beats integrated into the profile
        float beatErrorMs = -1.0f; // negative = not yet measurable
        bool phaseValid = false;
        float phaseDeviationMs = 0.0f;  // wrapped to ±period/2, for the trace
        float periodMs = 0.0f;
    };

    explicit FoldingAnalyzer(int sampleRate);

    // Positive pins the rate; 0 returns to automatic identification.
    void SetBphOverride(int bph);

    // Feed one sample of each band-limited envelope channel. Returns true
    // when a fresh snapshot is ready (every ~0.5 s) and fills *out.
    bool Push(const float* channelEnvelopes, Snapshot* out);

    // Diagnostic: the current fold score for a rate in one channel.
    double ScoreForDebug(int bph, int channel) const;

private:
    struct Score {
        int bph = 0;
        double value = 0.0;
        int channel = 0;
    };

    void AddBin();
    void EvaluateLock();
    // Folds channel history at periodMs into `profile` (kScoreSlots wide);
    // returns false while there is too little history.
    bool FoldProfile(int channel, double periodMs,
                     std::array<double, 64>& profile) const;
    Score ScorePeriod(int bph) const;
    double ScoreChannel(int channel, double periodMs) const;
    // True when folding at twice the period shows a single peak — i.e. the
    // real beat period is 2x and this candidate is a half-period alias.
    bool HalfPeriodAlias(int channel, double periodMs) const;
    void ResetProfiles();
    void UpdateProfiles(double binTimeMs, float centered);
    void TakeSnapshot();
    void EstimatePhaseAndRate();
    void EstimateBeatError();

    // --- binning ---
    static constexpr double kBinMs = 1.0;
    static constexpr int kWindowBins = 30000;   // 30 s scoring history
    static constexpr int kSnapshotBins = 500;   // snapshot every 0.5 s
    // Fast mean-removal: real phone input pumps with AGC at sub-hertz
    // rates, which would otherwise inflate the fold profile's sigma and
    // suppress the score. 0.5 s is still 2.5x the longest beat period.
    static constexpr double kMeanSeconds = 0.5;

    // --- fold scoring / lock ---
    // The fold profile is a per-slot MEAN, and ticks are narrower than a
    // slot: finer slots concentrate the peak (score grows ~sqrt(slots) up
    // to the tick width). 64 slots ~= 2 ms at 28800 bph, about tick width.
    static constexpr int kScoreSlots = 64;
    static constexpr int kProfileSlots = 64;
    static constexpr int kMinScoreBins = 2000;
    static constexpr double kLockScore = 6.0;
    static constexpr double kLockMargin = 1.3;   // best/runner-up ratio
    static constexpr int kLockStreak = 2;
    static constexpr double kUnlockScore = 3.5;
    // A profile with a second peak near half a period apart means the true
    // period is half the candidate (ticks every P/2): penalize it.
    static constexpr double kDoublePeakFraction = 0.55;
    static constexpr int kDoublePeakMinSlots = 20;
    // Half-period-alias veto: single-peak threshold for the 2x fold.
    static constexpr double kAliasSecondPeakFraction = 0.35;
    static constexpr double kAliasPenalty = 0.3;
    static constexpr double kAliasMinScore = 3.0;

    // --- phase tracking ---
    static constexpr double kProfileBeta = 0.02;  // per-slot EW update
    // The EW profile lags a drifting phase while it settles (~2.5 time
    // constants); phase points recorded before that would bias the slope.
    static constexpr int kMinBeatsForPhase = 125;
    static constexpr int kMinBeatsForBeatError = 150;
    static constexpr double kMinRateSpanSeconds = 10.0;
    static constexpr int kMinRatePoints = 15;
    static constexpr int kMaxPhasePoints = 240;    // ~2 min of history
    static constexpr double kSecondsPerDayPerMsPerSec = 86.4;
    static constexpr double kBeatErrorPeakFraction = 0.45;
    static constexpr int kBeatErrorSearchSlots = 12;

    const int binSize_;
    const float meanAlpha_;

    // per-channel bin accumulation and centered-energy history (rings)
    std::array<float, kChannels> binSum_{};
    std::array<float, kChannels> runningMean_{};
    std::array<float, kChannels> lastCentered_{};
    std::array<std::vector<float>, kChannels> history_;
    int binFill_ = 0;
    int64_t binIndex_ = 0;
    int64_t historyCount_ = 0;

    // lock state
    int overrideBph_ = 0;
    int lockedBph_ = 0;
    int candidateBph_ = 0;
    int candidateStreak_ = 0;
    int activeBph_ = 0;
    double activePeriodMs_ = 0.0;
    int profileChannel_ = 1;
    bool channelChosen_ = false;

    // fold profiles for the active period (fine slots, chosen channel)
    std::array<double, kProfileSlots> profileP_{};
    std::array<double, 2 * kProfileSlots> profile2P_{};
    int64_t profileBins_ = 0;

    // phase history for the rate fit
    bool phaseStarted_ = false;
    double lastPhaseMs_ = 0.0;
    double unwrappedPhaseMs_ = 0.0;
    std::deque<std::pair<double, double>> phasePoints_;  // (tSec, unwrapped)

    int binsSinceSnapshot_ = 0;
    Snapshot snapshot_;
};

}  // namespace deadaccurate
