package com.digitalfuelgauge

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

data class Vehicle(
    val id: String,
    val name: String,
    val consumption: Float,
    val tankCapacity: Float,
    val lastFullKm: Float = Float.NaN,
    val lastRemainingL: Float = Float.NaN,
    val lastRemainingPct: Float = Float.NaN,
    val lastFullLocked: Boolean = false,
    val fuelLog: List<FuelLogEntry> = emptyList()
)

object VehicleStore {

    const val PREFS_NAME = FuelAddedActivity.PREFS_NAME

    private const val KEY_VEHICLES_JSON = "vehicles_json"
    private const val KEY_ACTIVE_VEHICLE_ID = "active_vehicle_id"

    // Legacy flat keys (migration only)
    private const val LEGACY_TANK = FuelAddedActivity.KEY_TANK_CAPACITY
    private const val LEGACY_CONSUMPTION = FuelAddedActivity.KEY_CONSUMPTION
    private const val LEGACY_LAST_FULL_KM = FuelAddedActivity.KEY_LAST_FULL_KM
    private const val LEGACY_LAST_FULL_LOCKED = FuelAddedActivity.KEY_LAST_FULL_LOCKED
    private const val LEGACY_LAST_REMAINING_L = FuelAddedActivity.KEY_LAST_REMAINING_L
    private const val LEGACY_LAST_REMAINING_PCT = FuelAddedActivity.KEY_LAST_REMAINING_PCT
    private const val LEGACY_FUEL_LOG = "fuel_log"

    private const val LEGACY_PREFS_NAME = "fuelcheck_prefs"
    private const val KEY_PREFS_FILE_MIGRATED = "prefs_file_migrated"

    fun ensureMigrated(context: Context) {
        migratePrefsFileIfNeeded(context)
        migrateIfNeeded(context)
    }

    fun prefs(context: Context): SharedPreferences {
        migratePrefsFileIfNeeded(context)
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    private fun migratePrefsFileIfNeeded(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        if (prefs.getBoolean(KEY_PREFS_FILE_MIGRATED, false)) return
        if (prefs.all.isNotEmpty()) {
            prefs.edit().putBoolean(KEY_PREFS_FILE_MIGRATED, true).apply()
            return
        }

        val legacy = context.getSharedPreferences(LEGACY_PREFS_NAME, Context.MODE_PRIVATE)
        if (legacy.all.isEmpty()) {
            prefs.edit().putBoolean(KEY_PREFS_FILE_MIGRATED, true).apply()
            return
        }

        val editor = prefs.edit()
        legacy.all.forEach { (key, value) ->
            when (value) {
                is String -> editor.putString(key, value)
                is Int -> editor.putInt(key, value)
                is Long -> editor.putLong(key, value)
                is Float -> editor.putFloat(key, value)
                is Boolean -> editor.putBoolean(key, value)
                is Set<*> -> @Suppress("UNCHECKED_CAST")
                editor.putStringSet(key, value as Set<String>)
            }
        }
        editor.putBoolean(KEY_PREFS_FILE_MIGRATED, true).commit()
        legacy.edit().clear().commit()
    }

    fun hasVehicles(context: Context): Boolean {
        migrateIfNeeded(context)
        return loadAll(context).isNotEmpty()
    }

    fun loadAll(context: Context): List<Vehicle> {
        migrateIfNeeded(context)
        val prefs = prefs(context)
        val raw = prefs.getString(KEY_VEHICLES_JSON, null) ?: return emptyList()
        return try {
            parseVehicles(JSONArray(raw))
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun active(context: Context): Vehicle? {
        val vehicles = loadAll(context)
        if (vehicles.isEmpty()) return null
        val activeId = prefs(context).getString(KEY_ACTIVE_VEHICLE_ID, null)
        return vehicles.firstOrNull { it.id == activeId } ?: vehicles.first()
    }

    fun setActiveId(context: Context, id: String) {
        prefs(context).edit().putString(KEY_ACTIVE_VEHICLE_ID, id).apply()
    }

    fun save(context: Context, vehicle: Vehicle, setActive: Boolean = false) {
        val vehicles = loadAll(context).toMutableList()
        val index = vehicles.indexOfFirst { it.id == vehicle.id }
        if (index >= 0) {
            vehicles[index] = vehicle
        } else {
            vehicles.add(vehicle)
        }
        persist(context, vehicles, if (setActive) vehicle.id else null)
    }

    fun createFullTankVehicle(
        context: Context,
        name: String,
        consumption: Double,
        tankCapacity: Double,
        odometerKm: Double,
        setActive: Boolean = true
    ): Vehicle {
        val vehicle = Vehicle(
            id = UUID.randomUUID().toString(),
            name = name.trim(),
            consumption = consumption.toFloat(),
            tankCapacity = tankCapacity.toFloat(),
            lastFullKm = odometerKm.toFloat(),
            lastRemainingL = tankCapacity.toFloat(),
            lastRemainingPct = 100f,
            lastFullLocked = true,
            fuelLog = listOf(
                FuelLogEntry(
                    timestampMs = System.currentTimeMillis(),
                    odometerKm = odometerKm,
                    litersAdded = tankCapacity,
                    remainingLiters = tankCapacity,
                    isFullTank = true
                )
            )
        )
        save(context, vehicle, setActive = setActive)
        return vehicle
    }

    fun updateActive(context: Context, transform: (Vehicle) -> Vehicle): Vehicle? {
        val current = active(context) ?: return null
        val updated = transform(current)
        save(context, updated, setActive = true)
        return updated
    }

    fun clearAll(context: Context) {
        prefs(context).edit().clear().commit()
    }

    private fun persist(context: Context, vehicles: List<Vehicle>, newActiveId: String?) {
        val prefs = prefs(context)
        val array = JSONArray()
        vehicles.forEach { array.put(vehicleToJson(it)) }

        val editor = prefs.edit()
            .putString(KEY_VEHICLES_JSON, array.toString())

        val activeId = when {
            newActiveId != null -> newActiveId
            else -> {
                val existing = prefs.getString(KEY_ACTIVE_VEHICLE_ID, null)
                when {
                    existing != null && vehicles.any { it.id == existing } -> existing
                    vehicles.isNotEmpty() -> vehicles.first().id
                    else -> null
                }
            }
        }
        if (activeId != null) {
            editor.putString(KEY_ACTIVE_VEHICLE_ID, activeId)
        } else {
            editor.remove(KEY_ACTIVE_VEHICLE_ID)
        }

        // Drop legacy flat keys once vehicles are stored
        editor
            .remove(LEGACY_TANK)
            .remove(LEGACY_CONSUMPTION)
            .remove(LEGACY_LAST_FULL_KM)
            .remove(LEGACY_LAST_FULL_LOCKED)
            .remove(LEGACY_LAST_REMAINING_L)
            .remove(LEGACY_LAST_REMAINING_PCT)
            .remove(LEGACY_FUEL_LOG)
            .apply()
    }

    @Synchronized
    private fun migrateIfNeeded(context: Context) {
        val prefs = prefs(context)
        val existing = prefs.getString(KEY_VEHICLES_JSON, null)
        if (!existing.isNullOrBlank() && existing != "[]") return

        val consumption = prefs.getFloat(LEGACY_CONSUMPTION, Float.NaN)
        val tankCapacity = prefs.getFloat(LEGACY_TANK, Float.NaN)
        val hasLegacy = (!consumption.isNaN() && consumption > 0f) ||
            (!tankCapacity.isNaN() && tankCapacity > 0f) ||
            !prefs.getString(LEGACY_FUEL_LOG, null).isNullOrBlank()

        if (!hasLegacy) return

        val vehicle = Vehicle(
            id = UUID.randomUUID().toString(),
            name = "My car",
            consumption = if (!consumption.isNaN() && consumption > 0f) consumption else Float.NaN,
            tankCapacity = if (!tankCapacity.isNaN() && tankCapacity > 0f) tankCapacity else Float.NaN,
            lastFullKm = prefs.getFloat(LEGACY_LAST_FULL_KM, Float.NaN),
            lastRemainingL = prefs.getFloat(LEGACY_LAST_REMAINING_L, Float.NaN),
            lastRemainingPct = prefs.getFloat(LEGACY_LAST_REMAINING_PCT, Float.NaN),
            lastFullLocked = prefs.getBoolean(LEGACY_LAST_FULL_LOCKED, false),
            fuelLog = parseFuelLog(prefs.getString(LEGACY_FUEL_LOG, null))
        )
        persist(context, listOf(vehicle), vehicle.id)
    }

    private fun parseVehicles(array: JSONArray): List<Vehicle> {
        return buildList {
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                add(
                    Vehicle(
                        id = obj.getString("id"),
                        name = obj.getString("name"),
                        consumption = obj.optFloatOrNaN("consumption"),
                        tankCapacity = obj.optFloatOrNaN("tankCapacity"),
                        lastFullKm = obj.optFloatOrNaN("lastFullKm"),
                        lastRemainingL = obj.optFloatOrNaN("lastRemainingL"),
                        lastRemainingPct = obj.optFloatOrNaN("lastRemainingPct"),
                        lastFullLocked = obj.optBoolean("lastFullLocked", false),
                        fuelLog = parseFuelLogArray(obj.optJSONArray("fuelLog"))
                    )
                )
            }
        }
    }

    private fun vehicleToJson(vehicle: Vehicle): JSONObject {
        return JSONObject().apply {
            put("id", vehicle.id)
            put("name", vehicle.name)
            putFloat("consumption", vehicle.consumption)
            putFloat("tankCapacity", vehicle.tankCapacity)
            putFloat("lastFullKm", vehicle.lastFullKm)
            putFloat("lastRemainingL", vehicle.lastRemainingL)
            putFloat("lastRemainingPct", vehicle.lastRemainingPct)
            put("lastFullLocked", vehicle.lastFullLocked)
            put("fuelLog", fuelLogToJson(vehicle.fuelLog))
        }
    }

    private fun JSONObject.putFloat(key: String, value: Float) {
        if (value.isNaN()) {
            put(key, JSONObject.NULL)
        } else {
            put(key, value.toDouble())
        }
    }

    private fun JSONObject.optFloatOrNaN(key: String): Float {
        if (!has(key) || isNull(key)) return Float.NaN
        val value = optDouble(key, Double.NaN)
        return if (value.isNaN()) Float.NaN else value.toFloat()
    }

    private fun parseFuelLog(raw: String?): List<FuelLogEntry> {
        if (raw.isNullOrBlank()) return emptyList()
        return try {
            parseFuelLogArray(JSONArray(raw))
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun parseFuelLogArray(array: JSONArray?): List<FuelLogEntry> {
        if (array == null) return emptyList()
        return buildList {
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
    }

    fun fuelLogToJson(entries: List<FuelLogEntry>): JSONArray {
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
        return array
    }
}
