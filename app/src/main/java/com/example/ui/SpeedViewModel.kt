package com.example.ui

import android.annotation.SuppressLint
import android.app.Application
import android.location.Location
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Looper
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.Trip
import com.example.data.TripRepository
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class SpeedViewModel(app: Application) : AndroidViewModel(app) {

    private val repo = TripRepository(app)
    private val fusedClient = LocationServices.getFusedLocationProviderClient(app)

    // --- Public state exposed to the UI ---
    private val _isTracking = MutableStateFlow(false)
    val isTracking: StateFlow<Boolean> = _isTracking.asStateFlow()

    private val _currentSpeedKmh = MutableStateFlow(0.0)
    val currentSpeedKmh: StateFlow<Double> = _currentSpeedKmh.asStateFlow()

    private val _maxSpeedKmh = MutableStateFlow(0.0)
    val maxSpeedKmh: StateFlow<Double> = _maxSpeedKmh.asStateFlow()

    private val _avgSpeedKmh = MutableStateFlow(0.0)
    val avgSpeedKmh: StateFlow<Double> = _avgSpeedKmh.asStateFlow()

    private val _distanceKm = MutableStateFlow(0.0)
    val distanceKm: StateFlow<Double> = _distanceKm.asStateFlow()

    private val _durationSeconds = MutableStateFlow(0L)
    val durationSeconds: StateFlow<Long> = _durationSeconds.asStateFlow()

    private val _gpsStatus = MutableStateFlow("Press Start to begin GPS tracking")
    val gpsStatus: StateFlow<String> = _gpsStatus.asStateFlow()

    private val _speedLimit = MutableStateFlow(repo.loadSpeedLimit())
    val speedLimit: StateFlow<Int> = _speedLimit.asStateFlow()

    private val _speedUnit = MutableStateFlow(repo.loadSpeedUnit())
    val speedUnit: StateFlow<String> = _speedUnit.asStateFlow()

    private val _alertSoundEnabled = MutableStateFlow(repo.loadAlertSound())
    val alertSoundEnabled: StateFlow<Boolean> = _alertSoundEnabled.asStateFlow()

    private val _savedTrips = MutableStateFlow(repo.loadTrips())
    val savedTrips: StateFlow<List<Trip>> = _savedTrips.asStateFlow()

    // --- Internal trip accumulators ---
    private var totalSpeed = 0.0
    private var speedReadings = 0
    private var lastLocation: Location? = null

    private var startElapsed = 0L          // accumulated seconds from earlier segments
    private var segmentStart = 0L          // wall-clock ms when current segment began
    private var timerJob: Job? = null

    private var alertActive = false
    private var toneGen: ToneGenerator? = null

    private val locationRequest = LocationRequest.Builder(
        Priority.PRIORITY_HIGH_ACCURACY, 1000L
    ).setMinUpdateIntervalMillis(500L).build()

    private val locationCallback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            result.lastLocation?.let { handleLocation(it) }
        }
    }

    // --- Tracking control ---
    @SuppressLint("MissingPermission")
    fun startTracking() {
        if (_isTracking.value) return
        _isTracking.value = true
        _gpsStatus.value = "Starting GPS..."

        segmentStart = System.currentTimeMillis()
        startTimer()

        fusedClient.requestLocationUpdates(
            locationRequest, locationCallback, Looper.getMainLooper()
        )
    }

    fun pauseTracking() {
        if (!_isTracking.value) return
        _isTracking.value = false
        fusedClient.removeLocationUpdates(locationCallback)
        // bank the elapsed time of this segment
        startElapsed += (System.currentTimeMillis() - segmentStart) / 1000
        timerJob?.cancel()
        timerJob = null
        _gpsStatus.value = "Tracking paused"
        lastLocation = null // avoid a distance jump after a long pause
    }

    fun resetTrip() {
        pauseTracking()
        _currentSpeedKmh.value = 0.0
        _maxSpeedKmh.value = 0.0
        _avgSpeedKmh.value = 0.0
        _distanceKm.value = 0.0
        _durationSeconds.value = 0L
        totalSpeed = 0.0
        speedReadings = 0
        startElapsed = 0L
        lastLocation = null
        _gpsStatus.value = "Trip reset. Press Start to begin again."
        stopAlertTone()
    }

    private fun startTimer() {
        timerJob?.cancel()
        timerJob = viewModelScope.launch {
            while (_isTracking.value) {
                val live = (System.currentTimeMillis() - segmentStart) / 1000
                _durationSeconds.value = startElapsed + live
                delay(1000)
            }
        }
    }

    private fun handleLocation(loc: Location) {
        _gpsStatus.value = "GPS active | Accuracy: ${Math.round(loc.accuracy)} m"

        // Speed: prefer hardware speed, fall back to distance/time.
        var speedMps = if (loc.hasSpeed()) loc.speed.toDouble() else 0.0

        lastLocation?.let { prev ->
            val meters = prev.distanceTo(loc).toDouble()
            val secs = (loc.time - prev.time) / 1000.0
            if (meters > 0 && secs > 0) {
                _distanceKm.value += meters / 1000.0
                if (!loc.hasSpeed() || speedMps <= 0.0) {
                    speedMps = meters / secs
                }
            }
        }
        lastLocation = loc

        if (speedMps < 0) speedMps = 0.0
        var kmh = speedMps * 3.6
        if (kmh < 1.0) kmh = 0.0   // suppress GPS jitter at standstill

        _currentSpeedKmh.value = kmh
        if (kmh > _maxSpeedKmh.value) _maxSpeedKmh.value = kmh
        if (kmh > 0) {
            totalSpeed += kmh
            speedReadings++
            _avgSpeedKmh.value = totalSpeed / speedReadings
        }

        checkAlert(kmh)
    }

    private fun checkAlert(currentKmh: Double) {
        val limit = _speedLimit.value
        if (limit <= 0) { stopAlertTone(); return }

        // Compare in the displayed unit so the limit matches what the user sees.
        val displaySpeed = if (_speedUnit.value == "mph") currentKmh * 0.621371 else currentKmh
        val over = displaySpeed > limit

        if (over && !alertActive) {
            alertActive = true
            if (_alertSoundEnabled.value) playAlertTone()
        } else if (!over && alertActive) {
            alertActive = false
            stopAlertTone()
        }
    }

    private fun playAlertTone() {
        try {
            if (toneGen == null) toneGen = ToneGenerator(AudioManager.STREAM_ALARM, 80)
            toneGen?.startTone(ToneGenerator.TONE_CDMA_HIGH_L, 600)
        } catch (_: Exception) { }
    }

    private fun stopAlertTone() {
        try {
            toneGen?.stopTone()
        } catch (_: Exception) { }
    }

    // --- Settings ---
    fun setSpeedLimit(v: Int) {
        val clamped = v.coerceIn(0, 400)
        _speedLimit.value = clamped
        repo.saveSpeedLimit(clamped)
    }

    fun setSpeedUnit(u: String) {
        _speedUnit.value = u
        repo.saveSpeedUnit(u)
    }

    fun setAlertSoundEnabled(enabled: Boolean) {
        _alertSoundEnabled.value = enabled
        repo.saveAlertSound(enabled)
        if (!enabled) stopAlertTone()
    }

    // --- Trip history ---
    fun saveTrip() {
        if (_distanceKm.value <= 0.0 && _durationSeconds.value <= 0L) {
            _gpsStatus.value = "Nothing to save yet."
            return
        }
        val fmt = SimpleDateFormat("MMM d, yyyy  HH:mm", Locale.getDefault())
        val trip = Trip(
            id = System.currentTimeMillis(),
            dateTimeString = fmt.format(Date()),
            distanceKm = _distanceKm.value,
            maxSpeedKmh = _maxSpeedKmh.value,
            avgSpeedKmh = _avgSpeedKmh.value,
            durationSeconds = _durationSeconds.value
        )
        val updated = (listOf(trip) + _savedTrips.value).take(50)
        _savedTrips.value = updated
        repo.saveTrips(updated)
        _gpsStatus.value = "Trip saved successfully."
    }

    fun deleteTrip(trip: Trip) {
        val updated = _savedTrips.value.filter { it.id != trip.id }
        _savedTrips.value = updated
        repo.saveTrips(updated)
    }

    fun clearAllTrips() {
        _savedTrips.value = emptyList()
        repo.saveTrips(emptyList())
    }

    override fun onCleared() {
        super.onCleared()
        fusedClient.removeLocationUpdates(locationCallback)
        timerJob?.cancel()
        toneGen?.release()
    }
}
