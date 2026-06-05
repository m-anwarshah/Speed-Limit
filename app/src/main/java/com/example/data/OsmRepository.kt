package com.example.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** Current road, as derived from OpenStreetMap. Fields are null when unknown. */
data class RoadInfo(val name: String?, val maxSpeedKmh: Double?)

/** Nearest rest/service area. Distance is straight-line ("as the crow flies"). */
data class RestArea(val name: String?, val distanceKm: Double)

/**
 * Looks up road name, posted speed limit, and the nearest rest area from the free
 * OpenStreetMap Overpass API. All calls run on Dispatchers.IO and fail soft
 * (return null) so the speedometer keeps working even with no/poor connectivity.
 *
 * Note: coverage is uneven — many roads have no `maxspeed` tag, and rest areas are
 * not mapped everywhere — so callers should treat missing data as normal.
 */
class OsmRepository {

    private suspend fun overpass(query: String): JSONObject? = withContext(Dispatchers.IO) {
        var conn: HttpURLConnection? = null
        try {
            conn = (URL(OVERPASS_URL).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 8000
                readTimeout = 22000
                doOutput = true
                setRequestProperty("User-Agent", USER_AGENT)
                setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            }
            conn.outputStream.use { it.write(("data=" + URLEncoder.encode(query, "UTF-8")).toByteArray()) }
            if (conn.responseCode in 200..299) {
                JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
            } else null
        } catch (_: Exception) {
            null
        } finally {
            conn?.disconnect()
        }
    }

    /** Nearest drivable road at the given point, with its name and speed limit (if tagged). */
    suspend fun fetchRoadInfo(lat: Double, lon: Double): RoadInfo? {
        val q = """
            [out:json][timeout:10];
            way(around:30,$lat,$lon)[highway][highway!~"footway|path|cycleway|steps|pedestrian|construction|service|track|bridleway|corridor|platform"];
            out tags;
        """.trimIndent()
        val json = overpass(q) ?: return null
        val els = json.optJSONArray("elements") ?: return RoadInfo(null, null)

        var bestTags: JSONObject? = null
        var bestScore = -1
        for (i in 0 until els.length()) {
            val tags = els.getJSONObject(i).optJSONObject("tags") ?: continue
            val score = roadClassScore(tags.optString("highway", "")) +
                (if (tags.has("name")) 10 else 0)
            if (score > bestScore) {
                bestScore = score
                bestTags = tags
            }
        }
        val tags = bestTags ?: return RoadInfo(null, null)
        val name = tags.optString("name", "")
            .ifBlank { tags.optString("ref", "") }
            .ifBlank { null }
        return RoadInfo(name, parseMaxSpeedKmh(tags.optString("maxspeed", "")))
    }

    /** Closest rest_area / motorway services within ~60 km, by straight-line distance. */
    suspend fun fetchNearestRestArea(lat: Double, lon: Double): RestArea? {
        val q = """
            [out:json][timeout:25];
            (
              node(around:60000,$lat,$lon)[highway=rest_area];
              way(around:60000,$lat,$lon)[highway=rest_area];
              node(around:60000,$lat,$lon)[highway=services];
              way(around:60000,$lat,$lon)[highway=services];
            );
            out center;
        """.trimIndent()
        val json = overpass(q) ?: return null
        val els = json.optJSONArray("elements") ?: return null

        var bestDist = Double.MAX_VALUE
        var bestName: String? = null
        for (i in 0 until els.length()) {
            val el = els.getJSONObject(i)
            val pLat: Double
            val pLon: Double
            if (el.has("lat") && el.has("lon")) {
                pLat = el.getDouble("lat"); pLon = el.getDouble("lon")
            } else {
                val c = el.optJSONObject("center") ?: continue
                pLat = c.getDouble("lat"); pLon = c.getDouble("lon")
            }
            val d = haversineKm(lat, lon, pLat, pLon)
            if (d < bestDist) {
                bestDist = d
                bestName = el.optJSONObject("tags")?.optString("name", "")?.ifBlank { null }
            }
        }
        return if (bestDist == Double.MAX_VALUE) null else RestArea(bestName, bestDist)
    }

    private fun roadClassScore(highway: String): Int = when (highway) {
        "motorway", "motorway_link" -> 8
        "trunk", "trunk_link" -> 7
        "primary", "primary_link" -> 6
        "secondary", "secondary_link" -> 5
        "tertiary", "tertiary_link" -> 4
        "unclassified" -> 3
        "residential" -> 2
        "living_street" -> 1
        else -> 0
    }

    /** OSM maxspeed can be "50", "30 mph", "RU:urban", "none"… → km/h, or null if not numeric. */
    private fun parseMaxSpeedKmh(raw: String): Double? {
        if (raw.isBlank()) return null
        val lower = raw.lowercase()
        val num = Regex("\\d+(?:\\.\\d+)?").find(lower)?.value?.toDoubleOrNull() ?: return null
        return if (lower.contains("mph")) num * 1.609344 else num // OSM default unit is km/h
    }

    private fun haversineKm(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val r = 6371.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2) * sin(dLat / 2) +
            cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) *
            sin(dLon / 2) * sin(dLon / 2)
        return r * 2 * atan2(sqrt(a), sqrt(1 - a))
    }

    companion object {
        private const val OVERPASS_URL = "https://overpass-api.de/api/interpreter"
        private const val USER_AGENT = "SpeedMeter/1.0 (Android; OSM road info)"
    }
}
