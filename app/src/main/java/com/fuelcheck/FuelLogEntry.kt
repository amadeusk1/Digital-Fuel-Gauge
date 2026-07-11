package com.fuelcheck

data class FuelLogEntry(
    val timestampMs: Long,
    val odometerKm: Double,
    val litersAdded: Double,
    val remainingLiters: Double,
    val isFullTank: Boolean
)
