package com.deadaccurate.app.settings

/** Which capture input to use (FR-2/FR-7). */
enum class InputPreference {
    /** Wired headset (piezo) when present, else built-in mic. */
    AUTO,

    /** Always the built-in mic, even with a wired input connected. */
    BUILT_IN,
}

/** Which detection path drives the readouts. */
enum class AnalysisMode(val native: Int) {
    /** Per-tick gate edges: precise and fast, needs piezo-level signal. */
    EDGE(com.deadaccurate.engine.AnalysisModeNative.EDGE),

    /** Energy folding: works at phone-mic signal levels, settles slower. */
    CORRELATION(com.deadaccurate.engine.AnalysisModeNative.CORRELATION),
}

/** Settings persisted across launches (FR-7). */
data class UserSettings(
    val gateTrimDb: Float = 0f,
    /** null = auto-detect the beat rate. */
    val bphOverride: Int? = null,
    val inputPreference: InputPreference = InputPreference.AUTO,
    val onboardingDismissed: Boolean = false,
    /**
     * Correction added to the measured rate, in s/day. The audio crystal's
     * ppm error shifts every reading by a constant s/day offset
     * (docs/03-signal-processing.md §7), so a single stored offset —
     * measured once against a reference — restores absolute accuracy.
     */
    val clockCalSecPerDay: Float = 0f,
    val analysisMode: AnalysisMode = AnalysisMode.EDGE,
    /**
     * Lift angle in degrees for the amplitude formula — a per-calibre
     * datum (most modern movements ~52; check the calibre's spec sheet).
     */
    val liftAngleDeg: Float = 52f,
)
