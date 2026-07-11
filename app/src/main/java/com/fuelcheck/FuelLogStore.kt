package com.fuelcheck

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

data class FuelLogEntry(
    val timestampMs: Long,
    val odometerKm: Double,
    val litersAdded: Double,
    val remainingLiters: Double,
    val isFullTank: Boolean
)

object FuelLogStore {

    private const val KEY_FUEL_LOG = "fuel_log"

    fun add(prefs: SharedPreferences, entry: FuelLogEntry) {
        val entries = load(prefs).toMutableList()
        entries.add(0, entry)
        save(prefs, entries)
    }

    fun load(prefs: SharedPreferences): List<FuelLogEntry> {
        val raw = prefs.getString(KEY_FUEL_LOG, null) ?: return emptyList()
        return try {
            val array = JSONArray(raw)
            buildList {
                for (i in 0 until array.length()) {
                    val obj = array.getJSONObject(i)
                    add(
                        FuelLogEntry(
                            timestampMs = obj.getLong("timestampMs"),
                            odometerKm = obj.getDouble("odometerKm"),
                            litersAdded = obj.getDouble("litersAdded"),
                            remainingLiters = obj.getDouble("remainingLiters"),
                            isFullTank = obj.getBoolean("isFullTank")
                        )
                    )
                }
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun save(prefs: SharedPreferences, entries: List<FuelLogEntry>) {
        val array = JSONArray()
        entries.forEach { entry ->
            array.put(
                JSONObject().apply {
                    put("timestampMs", entry.timestampMs)
                    put("odometerKm", entry.odometerKm)
                    put("litersAdded", entry.litersAdded)
                    put("remainingLiters", entry.remainingLiters)
                    put("isFullTank", entry.isFullTank)
                }
            )
        }
        prefs.edit().putString(KEY_FUEL_LOG, array.toString()).apply()
    }

    fun prefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(FuelAddedActivity.PREFS_NAME, Context.MODE_PRIVATE)
    }
}
