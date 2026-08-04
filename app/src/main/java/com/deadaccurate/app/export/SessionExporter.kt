package com.deadaccurate.app.export

import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.File
import java.util.Locale

/** One tick as logged for export. */
data class SessionTick(val timeMs: Double, val deviationMs: Float, val accepted: Boolean)

/**
 * Builds and shares the session CSV (M5.4): a self-describing summary
 * header followed by the per-tick log. [buildCsv] is pure and JVM-tested;
 * [writeForSharing] handles the FileProvider plumbing.
 */
object SessionExporter {

    data class Summary(
        val source: String,
        val bph: Int,
        val rawSecPerDay: Float,
        val clockCalSecPerDay: Float,
        val beatErrorMs: Float?,
        val sampleRate: Int?,
    )

    fun buildCsv(summary: Summary, ticks: List<SessionTick>): String {
        val out = StringBuilder()
        out.append("# DeadAccurate session export\n")
        out.append("key,value\n")
        out.append("source,${summary.source}\n")
        out.append("beat_rate_bph,${summary.bph}\n")
        out.append(row("rate_s_per_day", summary.rawSecPerDay + summary.clockCalSecPerDay))
        out.append(row("rate_raw_s_per_day", summary.rawSecPerDay))
        out.append(row("clock_cal_s_per_day", summary.clockCalSecPerDay))
        out.append("beat_error_ms,${summary.beatErrorMs?.let { format(it) } ?: ""}\n")
        out.append("sample_rate_hz,${summary.sampleRate ?: ""}\n")
        out.append("ticks,${ticks.size}\n")
        out.append("\n")
        out.append("tick_index,time_s,deviation_ms,accepted\n")
        ticks.forEachIndexed { index, tick ->
            out.append(index)
                .append(',')
                .append(String.format(Locale.US, "%.4f", tick.timeMs / MS_PER_S))
                .append(',')
                .append(format(tick.deviationMs))
                .append(',')
                .append(if (tick.accepted) '1' else '0')
                .append('\n')
        }
        return out.toString()
    }

    /** Writes [csv] to the app cache and returns a shareable content Uri. */
    fun writeForSharing(context: Context, csv: String): Uri =
        writeBytesForSharing(context, EXPORT_FILE, csv.toByteArray(Charsets.UTF_8))

    /** Shares any generated file (CSV export, diagnostic WAV) via cache. */
    fun writeBytesForSharing(context: Context, fileName: String, bytes: ByteArray): Uri {
        val dir = File(context.cacheDir, EXPORT_DIR).apply { mkdirs() }
        val file = File(dir, fileName)
        file.writeBytes(bytes)
        return FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            file,
        )
    }

    private fun row(key: String, value: Float): String = "$key,${format(value)}\n"

    // Locale.US: the CSV must use dot decimals regardless of device locale.
    private fun format(value: Float): String = String.format(Locale.US, "%.3f", value)

    private const val MS_PER_S = 1000.0
    private const val EXPORT_DIR = "exports"
    private const val EXPORT_FILE = "deadaccurate-session.csv"
}
