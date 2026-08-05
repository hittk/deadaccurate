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
    static constexpr int kChannels = 4;

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
        // Fold score of the active rate in each band — the acoustic
        // signature used to recognize a specific movement (different
        // calibres put their tick energy in different bands). All zero
        // while searching.
        std::array<float, kChannels> bandScores{};
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
    // Bins between the overload floor and now — what scoring may fold.
    int64_t UsableBins() const;
    // Folds channel history at periodMs into `profile` (kScoreSlots wide);
    // returns false while there is too little history.
    bool FoldProfile(int channel, double periodMs,
                     std::array<double, 64>& profile) const;
    Score ScorePeriod(int bph) const;
    // Max over the rate-offset grid for one channel.
    double ScoreChannel(int channel, double periodMs) const;
    double ScoreChannelAt(int channel, double periodMs) const;
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
    // Winsorization: a handling knock (placing the watch on the mic) is
    // 1e2-1e5x the ambient bin energy, and folded at *any* period it
    // forges a false peak for every candidate for the whole 30 s window —
    // real recordings showed all 8 rates scoring above the lock threshold
    // until the knock aged out. Centered bins are clipped to a multiple of
    // a running |centered| scale (floored by a fraction of the running
    // mean so it is sane from the first bin). Tick bins are only a few x
    // the scale and periodic, so they survive; solitary knocks do not.
    static constexpr float kClipScaleRatio = 12.0f;
    static constexpr float kClipScaleFloor = 0.25f;
    // A gross overload (a placement knock, orders of magnitude beyond the
    // clip limit) means the acoustic setup itself changed — history from
    // before it describes a different physical coupling and only slows the
    // lock. A *solitary* overload floors the usable history just past the
    // knock; recurring overloads (a strong-ticking watch overloads every
    // beat) never advance the floor, so scoring cannot be starved.
    static constexpr float kOverloadRatio = 30.0f;
    static constexpr int kOverloadGuardBins = 1000;   // knock duration cover
    static constexpr int kOverloadSpacingBins = 2000; // "solitary" = >2 s apart

    // --- fold scoring / lock ---
    // The fold profile is a per-slot MEAN, and ticks are narrower than a
    // slot: finer slots concentrate the peak (score grows ~sqrt(slots) up
    // to the tick width). 64 slots ~= 2 ms at 28800 bph, about tick width.
    static constexpr int kScoreSlots = 64;
    static constexpr int kProfileSlots = 64;
    static constexpr int kMinScoreBins = 2000;
    static constexpr double kLockScore = 6.0;
    // Best/runner-up ratio. Deliberately mild: harmonically related
    // candidates (a 21600 watch folds into three even peaks at the 28800
    // period) legitimately score ~80% of the true rate, and the lock-matrix
    // test proves no cross-locks at this setting.
    static constexpr double kLockMargin = 1.15;
    static constexpr int kLockStreak = 2;
    static constexpr double kUnlockScore = 3.5;
    // A profile with a second peak near half a period apart means the true
    // period is half the candidate (ticks every P/2): penalize it.
    static constexpr double kDoublePeakFraction = 0.55;
    static constexpr int kDoublePeakMinSlots = 20;
    // A watch far off its nominal rate slides across the fold window (a
    // +200 s/day movement drifts 69 ms over 30 s — 26 slots): score at
    // several period offsets and take the best, so badly-off watches
    // still lock. Offsets cover roughly ±260 s/day.
    static constexpr double kScoreRateOffsets[5] = {-3.0e-3, -1.5e-3, 0.0,
                                                    1.5e-3, 3.0e-3};
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
    // Exact duration of one bin. At 44.1 kHz a "1 ms" bin is 44 samples =
    // 0.99773 ms; folding with a nominal 1 ms would drift 0.23% per period
    // and smear the profile to nothing over a 30 s window.
    const double binDurationMs_;
    const float meanAlpha_;

    // per-channel bin accumulation and centered-energy history (rings)
    std::array<float, kChannels> binSum_{};
    std::array<float, kChannels> runningMean_{};
    std::array<float, kChannels> absScale_{};
    std::array<float, kChannels> lastCentered_{};
    std::array<std::vector<float>, kChannels> history_;
    int binFill_ = 0;
    int64_t binIndex_ = 0;
    int64_t historyCount_ = 0;
    int64_t usableFloorBin_ = 0;
    int64_t lastOverloadBin_ = -kOverloadSpacingBins - 1;

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
    int lastPeakSlot_ = -1;
    double lastPhaseMs_ = 0.0;
    double unwrappedPhaseMs_ = 0.0;
    std::deque<std::pair<double, double>> phasePoints_;  // (tSec, unwrapped)

    int binsSinceSnapshot_ = 0;
    Snapshot snapshot_;
};

}  // namespace deadaccurate
