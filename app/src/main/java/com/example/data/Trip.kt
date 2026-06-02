package com.example.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * A single saved trip. Speeds/distance stored canonically in metric (km, km/h);
 * the UI converts to mph when needed.
 */
data class Trip(
    val id: Long,
    val dateTimeString: String,
    val distanceKm: Double,
    val maxSpeedKmh: Double,
    val avgSpeedKmh: Double,
    val durationSeconds: Long
)

/**
 * Lightweight persistence with SharedPreferences + JSON.
 * Keeps the build simple (no Room/database dependency).
 */
class TripRepository(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences("speed_meter_prefs", Context.MODE_PRIVATE)

    private val KEY_TRIPS = "saved_trips"
    private val KEY_LIMIT = "speed_limit"
    private val KEY_UNIT = "speed_unit"
    private val KEY_SOUND = "alert_sound"

    fun loadTrips(): List<Trip> {
        val raw = prefs.getString(KEY_TRIPS, null) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                Trip(
                    id = o.optLong("id", System.currentTimeMillis()),
                    dateTimeString = o.optString("dateTimeString", ""),
                    distanceKm = o.optDouble("distanceKm", 0.0),
                    maxSpeedKmh = o.optDouble("maxSpeedKmh", 0.0),
                    avgSpeedKmh = o.optDouble("avgSpeedKmh", 0.0),
                    durationSeconds = o.optLong("durationSeconds", 0L)
                )
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun saveTrips(trips: List<Trip>) {
        val arr = JSONArray()
        trips.forEach { t ->
            val o = JSONObject()
            o.put("id", t.id)
            o.put("dateTimeString", t.dateTimeString)
            o.put("distanceKm", t.distanceKm)
            o.put("maxSpeedKmh", t.maxSpeedKmh)
            o.put("avgSpeedKmh", t.avgSpeedKmh)
            o.put("durationSeconds", t.durationSeconds)
            arr.put(o)
        }
        prefs.edit().putString(KEY_TRIPS, arr.toString()).apply()
    }

    // --- Settings persistence ---
    fun loadSpeedLimit(): Int = prefs.getInt(KEY_LIMIT, 80)
    fun saveSpeedLimit(v: Int) = prefs.edit().putInt(KEY_LIMIT, v).apply()

    fun loadSpeedUnit(): String = prefs.getString(KEY_UNIT, "kmh") ?: "kmh"
    fun saveSpeedUnit(v: String) = prefs.edit().putString(KEY_UNIT, v).apply()

    fun loadAlertSound(): Boolean = prefs.getBoolean(KEY_SOUND, true)
    fun saveAlertSound(v: Boolean) = prefs.edit().putBoolean(KEY_SOUND, v).apply()
}
