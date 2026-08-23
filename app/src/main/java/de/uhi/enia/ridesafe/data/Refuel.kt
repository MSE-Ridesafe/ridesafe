package de.uhi.enia.ridesafe.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.ColumnInfo

/** A historical refueling event. Monetary and volume values use scaled integers for exact storage. */
@Entity(
    tableName = "refuels",
    indices = [
        Index("timestampEpochMs"),
        Index("vehicleId"),
        Index("journeyAnchorRideId"),
        Index("stationSavedAddressId"),
    ],
)
data class Refuel(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val vehicleId: Long,
    val timestampEpochMs: Long,
    val fuelAmountMilliliters: Long,
    val totalPriceMinor: Long,
    val currencyCode: String,
    val odometerMeters: Long,
    val stationAddress: String? = null,
    val stationSavedAddressId: Long? = null,
    @ColumnInfo(defaultValue = "0") val isFullTank: Boolean = false,
    val journeyAnchorRideId: Long? = null,
)
