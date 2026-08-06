package com.deadaccurate.app.watchlog

import java.io.File
import java.io.IOException
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/**
 * File-backed store for the watch log (a JSON document in app storage).
 * Takes the [file] directly (not a Context) so JVM tests run against a
 * temp file. All I/O happens on [Dispatchers.IO]; the in-memory state is
 * the source of truth between writes.
 */
class WatchLogRepository(private val file: File) {

    private val _watches = MutableStateFlow<List<WatchEntry>>(emptyList())
    val watches: StateFlow<List<WatchEntry>> = _watches

    suspend fun load() {
        val loaded = withContext(Dispatchers.IO) {
            if (!file.exists()) {
                emptyList()
            } else {
                try {
                    parse(file.readText())
                } catch (_: JSONException) {
                    // A corrupt log must not brick the app; start fresh but
                    // keep the bad file aside for post-mortem.
                    file.copyTo(File(file.parentFile, file.name + ".bad"), overwrite = true)
                    emptyList()
                } catch (_: IOException) {
                    emptyList()
                }
            }
        }
        _watches.value = loaded
    }

    /**
     * Appends [measurement] to an existing watch ([watchId]) or creates a
     * new one named [newWatchName]. A non-blank [movementRef] labels (or
     * relabels) the watch's calibre. Returns the updated entry.
     */
    suspend fun saveMeasurement(
        watchId: String?,
        newWatchName: String?,
        movementRef: String?,
        measurement: Measurement,
    ): WatchEntry {
        val current = _watches.value
        val existing = watchId?.let { id -> current.find { it.id == id } }
        val ref = movementRef?.trim()?.takeIf { it.isNotEmpty() }
        val updated = existing?.copy(
            movementRef = ref ?: existing.movementRef,
            measurements = listOf(measurement) + existing.measurements,
        ) ?: WatchEntry(
            id = UUID.randomUUID().toString(),
            name = newWatchName?.trim()?.takeIf { it.isNotEmpty() } ?: "Watch ${current.size + 1}",
            movementRef = ref,
            measurements = listOf(measurement),
        )
        _watches.value =
            if (existing != null) {
                current.map { if (it.id == existing.id) updated else it }
            } else {
                current + updated
            }
        persist()
        return updated
    }

    /**
     * Renames a watch and/or corrects its movement label (a mislabeled
     * movement would otherwise teach the recognizer wrong sounds forever).
     * Blank name keeps the old one; blank movement clears the label.
     */
    suspend fun updateWatch(watchId: String, name: String, movementRef: String) {
        _watches.value = _watches.value.map { watch ->
            if (watch.id == watchId) {
                watch.copy(
                    name = name.trim().takeIf { it.isNotEmpty() } ?: watch.name,
                    movementRef = movementRef.trim().takeIf { it.isNotEmpty() },
                )
            } else {
                watch
            }
        }
        persist()
    }

    suspend fun deleteWatch(watchId: String) {
        _watches.value = _watches.value.filterNot { it.id == watchId }
        persist()
    }

    private suspend fun persist() {
        val json = serialize(_watches.value)
        withContext(Dispatchers.IO) {
            try {
                file.parentFile?.mkdirs()
                val tmp = File(file.parentFile, file.name + ".tmp")
                tmp.writeText(json)
                if (!tmp.renameTo(file)) {
                    file.writeText(json)
                }
            } catch (_: IOException) {
                // Losing one write is recoverable; crashing the session is not.
            }
        }
    }

    private companion object {
        fun serialize(watches: List<WatchEntry>): String {
            val root = JSONObject()
            root.put("version", 1)
            val array = JSONArray()
            for (watch in watches) {
                val w = JSONObject()
                w.put("id", watch.id)
                w.put("name", watch.name)
                watch.movementRef?.let { w.put("movementRef", it) }
                val ms = JSONArray()
                for (m in watch.measurements) {
                    val obj = JSONObject()
                    obj.put("timestampMs", m.timestampMs)
                    obj.put("bph", m.bph)
                    obj.put("secPerDay", m.secPerDay.toDouble())
                    m.beatErrorMs?.let { obj.put("beatErrorMs", it.toDouble()) }
                    m.amplitudeDeg?.let { obj.put("amplitudeDeg", it.toDouble()) }
                    obj.put("mode", m.mode)
                    obj.put("input", m.input)
                    val energies = JSONArray()
                    for (e in m.bandEnergies) energies.put(e.toDouble())
                    obj.put("bandEnergies", energies)
                    ms.put(obj)
                }
                w.put("measurements", ms)
                array.put(w)
            }
            root.put("watches", array)
            return root.toString()
        }

        fun parse(text: String): List<WatchEntry> {
            val root = JSONObject(text)
            val array = root.getJSONArray("watches")
            val watches = ArrayList<WatchEntry>(array.length())
            for (i in 0 until array.length()) {
                val w = array.getJSONObject(i)
                val ms = w.getJSONArray("measurements")
                val measurements = ArrayList<Measurement>(ms.length())
                for (j in 0 until ms.length()) {
                    val m = ms.getJSONObject(j)
                    // Pre-0.4.5 logs stored SNR scores under "bandScores";
                    // those are not comparable to energies and are simply
                    // not loaded — old readings keep their numbers, they
                    // just no longer teach the recognizer.
                    val energiesJson = m.optJSONArray("bandEnergies") ?: JSONArray()
                    val energies = List(energiesJson.length()) { k ->
                        energiesJson.getDouble(k).toFloat()
                    }
                    measurements.add(
                        Measurement(
                            timestampMs = m.getLong("timestampMs"),
                            bph = m.getInt("bph"),
                            secPerDay = m.getDouble("secPerDay").toFloat(),
                            beatErrorMs = if (m.has("beatErrorMs")) {
                                m.getDouble("beatErrorMs").toFloat()
                            } else {
                                null
                            },
                            amplitudeDeg = if (m.has("amplitudeDeg")) {
                                m.getDouble("amplitudeDeg").toFloat()
                            } else {
                                null
                            },
                            mode = m.optString("mode", ""),
                            bandEnergies = energies,
                            input = m.optString("input", ""),
                        ),
                    )
                }
                watches.add(
                    WatchEntry(
                        id = w.getString("id"),
                        name = w.getString("name"),
                        movementRef = if (w.has("movementRef")) w.getString("movementRef") else null,
                        measurements = measurements,
                    ),
                )
            }
            return watches
        }
    }
}
