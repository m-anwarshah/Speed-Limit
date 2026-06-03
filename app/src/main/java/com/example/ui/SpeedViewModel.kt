package com.example.ui

import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.Trip
import com.example.service.LocationTrackingService
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Binds to LocationTrackingService and mirrors its StateFlows so the existing
 * Compose UI keeps working unchanged. All real tracking lives in the service,
 * which survives screen-off and app-minimized.
 */
class SpeedViewModel(app: Application) : AndroidViewModel(app) {

    private var service: LocationTrackingService? = null
    private var bound = false
    private val mirrorJobs = mutableListOf<Job>()

    // Local mirrors the UI collects from.
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
    private val _speedLimit = MutableStateFlow(80)
    val speedLimit: StateFlow<Int> = _speedLimit.asStateFlow()
    private val _speedUnit = MutableStateFlow("kmh")
    val speedUnit: StateFlow<String> = _speedUnit.asStateFlow()
    private val _alertSoundEnabled = MutableStateFlow(true)
    val alertSoundEnabled: StateFlow<Boolean> = _alertSoundEnabled.asStateFlow()
    private val _savedTrips = MutableStateFlow<List<Trip>>(emptyList())
    val savedTrips: StateFlow<List<Trip>> = _savedTrips.asStateFlow()

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val svc = (binder as LocationTrackingService.LocalBinder).getService()
            service = svc
            bound = true
            mirror(svc)
        }
        override fun onServiceDisconnected(name: ComponentName?) {
            bound = false
            service = null
        }
    }

    init {
        val ctx = getApplication<Application>()
        val intent = Intent(ctx, LocationTrackingService::class.java)
        // Bind only: this creates the service so the UI can read saved trips and
        // settings, but does NOT promote it to a foreground (location) service.
        // Foreground start is deferred to startTracking(), i.e. until the user has
        // granted location permission — which avoids a SecurityException/crash on
        // Android 14+.
        ctx.bindService(intent, connection, Context.BIND_AUTO_CREATE)
    }

    private fun <T> mirrorFlow(src: StateFlow<T>, dst: MutableStateFlow<T>) {
        mirrorJobs += viewModelScope.launch { src.collect { dst.value = it } }
    }

    private fun mirror(svc: LocationTrackingService) {
        mirrorFlow(svc.isTracking, _isTracking)
        mirrorFlow(svc.currentSpeedKmh, _currentSpeedKmh)
        mirrorFlow(svc.maxSpeedKmh, _maxSpeedKmh)
        mirrorFlow(svc.avgSpeedKmh, _avgSpeedKmh)
        mirrorFlow(svc.distanceKm, _distanceKm)
        mirrorFlow(svc.durationSeconds, _durationSeconds)
        mirrorFlow(svc.gpsStatus, _gpsStatus)
        mirrorFlow(svc.speedLimit, _speedLimit)
        mirrorFlow(svc.speedUnit, _speedUnit)
        mirrorFlow(svc.alertSoundEnabled, _alertSoundEnabled)
        mirrorFlow(svc.savedTrips, _savedTrips)
    }

    // --- Pass-through actions ---
    fun startTracking() {
        val ctx = getApplication<Application>()
        val intent = Intent(ctx, LocationTrackingService::class.java)
            .setAction(LocationTrackingService.ACTION_START)
        // Permission is granted by the time this is called, so it's safe to start
        // the foreground location service. onStartCommand begins GPS tracking, and
        // the service then keeps running when the app is minimized.
        ContextCompat.startForegroundService(ctx, intent)
    }
    fun pauseTracking() { service?.pauseTracking() }
    fun resetTrip() { service?.resetTrip() }
    fun setSpeedLimit(v: Int) { service?.setSpeedLimit(v) }
    fun setSpeedUnit(u: String) { service?.setSpeedUnit(u) }
    fun setAlertSoundEnabled(e: Boolean) { service?.setAlertSoundEnabled(e) }
    fun saveTrip() { service?.saveTrip() }
    fun deleteTrip(trip: Trip) { service?.deleteTrip(trip) }
    fun clearAllTrips() { service?.clearAllTrips() }

    override fun onCleared() {
        super.onCleared()
        mirrorJobs.forEach { it.cancel() }
        if (bound) {
            try { getApplication<Application>().unbindService(connection) } catch (_: Exception) {}
            bound = false
        }
        // Note: we do NOT stop the service here, so tracking continues
        // when the activity is destroyed/minimized.
    }
}
