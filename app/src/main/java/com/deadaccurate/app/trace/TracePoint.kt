package com.deadaccurate.app.trace

/** One dot on the tape; rejected outliers are drawn dimmed (FR-6). */
data class TracePoint(val deviationMs: Float, val accepted: Boolean)
