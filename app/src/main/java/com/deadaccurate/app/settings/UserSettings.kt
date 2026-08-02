package com.deadaccurate.app.settings

/** Which capture input to use (FR-2/FR-7). */
enum class InputPreference {
    /** Wired headset (piezo) when present, else built-in mic. */
    AUTO,

    /** Always the built-in mic, even with a wired input connected. */
    BUILT_IN,
}

/** Settings persisted across launches (FR-7). */
data class UserSettings(
    val gateTrimDb: Float = 0f,
    /** null = auto-detect the beat rate. */
    val bphOverride: Int? = null,
    val inputPreference: InputPreference = InputPreference.AUTO,
    val onboardingDismissed: Boolean = false,
)
