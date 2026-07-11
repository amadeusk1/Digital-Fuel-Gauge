package com.fuelcheck

import android.content.Context

object FuelLogStore {

    fun add(context: Context, entry: FuelLogEntry) {
        VehicleStore.updateActive(context) { vehicle ->
            vehicle.copy(fuelLog = listOf(entry) + vehicle.fuelLog)
        }
    }

    fun load(context: Context): List<FuelLogEntry> {
        return VehicleStore.active(context)?.fuelLog.orEmpty()
    }
}
