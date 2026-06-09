package com.example.service

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.location.Location
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.core.app.NotificationCompat
import com.example.MainActivity
import com.example.data.Trip
import com.example.data.OsmRepository
import com.example.data.TripRepository
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Foreground service that owns all GPS tracking and trip state.
 * Because it runs in the foreground (with a persistent notification),
 * Android keeps it alive when the screen is off or the app is minimized.
 *
 * The UI binds to this service and reads its StateFlows.
 */
class LocationTrackingService : Service() {

    private val binder = LocalBinder()
    inner class LocalBinder : Binder() {
        fun getService(): LocationTrackingService = this@LocationTrackingService
    }

    private lateinit var repo: TripRepository
    private val fusedClient by lazy { LocationServices.getFusedLocationProviderClient(this) }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    // --- State exposed to the UI ---
    val isTracking = MutableStateFlow(false)
    val currentSpeedKmh = MutableStateFlow(0.0)
    val maxSpeedKmh = MutableStateFlow(0.0)
    val avgSpeedKmh = MutableStateFlow(0.0)
    val distanceKm = MutableStateFlow(0.0)
    val durationSeconds = MutableStateFlow(0L)
    val gpsStatus = MutableStateFlow("Press Start to begin GPS tracking")

    val speedLimit = MutableStateFlow(80)
    val speedUnit = MutableStateFlow("kmh")
    val alertSoundEnabled = MutableStateFlow(true)
    val savedTrips = MutableStateFlow<List<Trip>>(emptyList())

    // --- Road context from OpenStreetMap (empty / -1 mean "unknown") ---
    val roadName = MutableStateFlow("")
    val roadMaxSpeedKmh = MutableStateFlow(-1.0)
    val restAreaName = MutableStateFlow("")
    val restAreaDistanceKm = MutableStateFlow(-1.0)

    // --- Internal accumulators ---
    private var totalSpeed = 0.0
    private var speedReadings = 0
    private var lastLocation: Location? = null
    private var lastFixTime = 0L
    private var startElapsed = 0L
    private var segmentStart = 0L
    private var timerJob: Job? = null
    private var infoJob: Job? = null
    private var alertActive = false
    private var alertJob: Job? = null
    private var toneGen: ToneGenerator? = null
    private var currentToneVolume = -1
    private var overLimitCount = 0
    private var isVibrating = false
    private val vibrator: Vibrator? by lazy {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            (getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }
    }

    private val osm = OsmRepository()

    private val locationRequest = LocationRequest.Builder(
        Priority.PRIORITY_HIGH_ACCURACY, 1000L
    ).setMinUpdateIntervalMillis(500L).build()

    private val locationCallback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            result.lastLocation?.let { handleLocation(it) }
        }
    }

    override fun onCreate() {
        super.onCreate()
        repo = TripRepository(this)
        speedLimit.value = repo.loadSpeedLimit()
        speedUnit.value = repo.loadSpeedUnit()
        alertSoundEnabled.value = repo.loadAlertSound()
        savedTrips.value = repo.loadTrips()
        createChannel()
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Only enter the foreground (with the location service type) when explicitly
        // asked to start tracking. By this point the user has granted location
        // permission, so promoting to a location foreground service is safe.
        when (intent?.action) {
            ACTION_START -> startTracking()
            ACTION_STOP -> pauseTracking()
        }
        // NOT_STICKY: don't let the system recreate an empty service after a kill
        // (it couldn't resume the trip anyway, and would only show a stale state).
        return START_NOT_STICKY
    }

    // --- Tracking control ---
    @SuppressLint("MissingPermission")
    fun startTracking() {
        if (isTracking.value) return
        isTracking.value = true
        gpsStatus.value = "Starting GPS..."

        startForegroundWithNotification()

        segmentStart = SystemClock.elapsedRealtime()
        startTimer()
        startInfoUpdates()

        fusedClient.requestLocationUpdates(
            locationRequest, locationCallback, Looper.getMainLooper()
        )
    }

    fun pauseTracking() {
        if (!isTracking.value) return
        isTracking.value = false
        fusedClient.removeLocationUpdates(locationCallback)
        startElapsed += (SystemClock.elapsedRealtime() - segmentStart) / 1000
        timerJob?.cancel()
        timerJob = null
        infoJob?.cancel()
        infoJob = null
        gpsStatus.value = "Tracking paused"
        lastLocation = null
        lastFixTime = 0L
        // Clear road context so stale data isn't shown while stopped.
        roadName.value = ""
        roadMaxSpeedKmh.value = -1.0
        restAreaName.value = ""
        restAreaDistanceKm.value = -1.0
        // No more GPS readings will arrive while paused, and the alarm is only
        // cleared by an incoming reading — so silence it explicitly here.
        overLimitCount = 0
        stopAlertLoop()
        stopVibrate()
        stopForeground(STOP_FOREGROUND_REMOVE)
        // End the "started" state so the service doesn't linger in the background.
        // While the app is open it stays alive via the binding (state is preserved);
        // once the app closes and unbinds, Android tears it down cleanly.
        stopSelf()
    }

    fun resetTrip() {
        pauseTracking()
        currentSpeedKmh.value = 0.0
        maxSpeedKmh.value = 0.0
        avgSpeedKmh.value = 0.0
        distanceKm.value = 0.0
        durationSeconds.value = 0L
        totalSpeed = 0.0
        speedReadings = 0
        startElapsed = 0L
        lastLocation = null
        gpsStatus.value = "Trip reset. Press Start to begin again."
        stopAlertLoop()
    }

    private fun startTimer() {
        timerJob?.cancel()
        timerJob = scope.launch {
            while (isTracking.value) {
                val now = SystemClock.elapsedRealtime()
                val live = (now - segmentStart) / 1000
                durationSeconds.value = startElapsed + live

                // GPS-loss watchdog: if no fix has arrived for a while, don't keep
                // beeping the alarm or showing a stale speed. Normal updates resume
                // (and overwrite this) as soon as a new fix comes in.
                if (lastFixTime > 0 && now - lastFixTime > GPS_TIMEOUT_MS) {
                    gpsStatus.value = "GPS signal lost — waiting for fix..."
                    currentSpeedKmh.value = 0.0
                    stopAlertLoop()
                }
                delay(1000)
            }
        }
    }

    // Periodically refreshes road name + speed limit (every ~15 s) and the nearest
    // rest area (every ~90 s) from OpenStreetMap while tracking. Fails soft.
    private fun startInfoUpdates() {
        infoJob?.cancel()
        infoJob = scope.launch {
            var tick = 0
            while (isTracking.value) {
                val loc = lastLocation
                if (loc != null) {
                    val info = try { osm.fetchRoadInfo(loc.latitude, loc.longitude) } catch (_: Exception) { null }
                    if (info != null) {
                        roadName.value = info.name ?: ""
                        roadMaxSpeedKmh.value = info.maxSpeedKmh ?: -1.0
                    }
                    if (tick % 6 == 0) {
                        val ra = try { osm.fetchNearestRestArea(loc.latitude, loc.longitude) } catch (_: Exception) { null }
                        if (ra != null) {
                            restAreaName.value = ra.name ?: ""
                            restAreaDistanceKm.value = ra.distanceKm
                        }
                    }
                }
                tick++
                delay(15_000)
            }
        }
    }

    private fun handleLocation(loc: Location) {
        lastFixTime = SystemClock.elapsedRealtime()
        val accuracy = if (loc.hasAccuracy()) loc.accuracy.toDouble() else Double.MAX_VALUE
        gpsStatus.value = "GPS active | Accuracy: ${Math.round(loc.accuracy)} m"

        var speedMps = if (loc.hasSpeed()) loc.speed.toDouble() else 0.0
        var meters = 0.0
        var secs = 0.0

        val accurate = accuracy <= DISTANCE_ACCURACY_THRESHOLD_M
        lastLocation?.let { prev ->
            meters = prev.distanceTo(loc).toDouble()
            secs = (loc.time - prev.time) / 1000.0
            // Derive speed from movement only when the fix is accurate. With the
            // screen off / battery saver on, low-accuracy fixes make the position
            // "jump", which would otherwise look like a burst of speed and trip the
            // alarm while the phone is actually sitting still.
            if (secs > 0 && (!loc.hasSpeed() || speedMps <= 0.0)) {
                speedMps = if (accurate) meters / secs else 0.0
            }
        }
        lastLocation = loc

        if (speedMps < 0) speedMps = 0.0
        var kmh = speedMps * 3.6
        if (kmh > MAX_PLAUSIBLE_KMH) kmh = 0.0   // discard impossible GPS spikes
        if (kmh < MIN_SPEED_KMH) kmh = 0.0       // suppress jitter at standstill

        // Only accumulate distance for accurate fixes while actually moving, so a
        // parked phone's GPS drift doesn't silently add kilometres over time.
        if (kmh > 0.0 && secs > 0 && accurate) {
            distanceKm.value += meters / 1000.0
        }

        currentSpeedKmh.value = kmh
        if (kmh > maxSpeedKmh.value) maxSpeedKmh.value = kmh
        if (kmh > 0) {
            totalSpeed += kmh
            speedReadings++
            avgSpeedKmh.value = totalSpeed / speedReadings
        }

        checkAlert(kmh)
    }

    private fun checkAlert(currentKmh: Double) {
        val limit = speedLimit.value
        if (limit <= 0) { overLimitCount = 0; stopAlertLoop(); stopVibrate(); return }
        val displaySpeed = if (speedUnit.value == "mph") currentKmh * 0.621371 else currentKmh
        val over = displaySpeed > limit

        // Debounce: require a few consecutive over-limit readings before alerting,
        // so a single bad/jumpy GPS fix can't trigger a false alarm.
        overLimitCount = if (over) overLimitCount + 1 else 0
        val confirmedOver = overLimitCount >= ALERT_CONFIRM_READINGS

        if (confirmedOver) {
            if (alertSoundEnabled.value) {
                stopVibrate()
                startAlertLoop()   // escalating beep until back under the limit
            } else {
                stopAlertLoop()
                startVibrate()     // sound is off → vibrate instead
            }
        } else {
            stopAlertLoop()
            stopVibrate()
        }
    }

    // Repeating beep that continues for as long as the speed stays over the limit,
    // independent of how often GPS updates arrive.
    private fun startAlertLoop() {
        if (alertJob?.isActive == true) return
        alertActive = true
        alertJob = scope.launch {
            var volume = ALERT_START_VOLUME
            try {
                while (alertActive) {
                    // Ramp the beep from quiet to loud, then hold at full volume.
                    // A ToneGenerator's volume is fixed per instance, so while ramping
                    // we recreate it at the new volume.
                    if (toneGen == null || volume != currentToneVolume) {
                        toneGen?.release()
                        toneGen = ToneGenerator(AudioManager.STREAM_ALARM, volume)
                        currentToneVolume = volume
                    }
                    toneGen?.startTone(ToneGenerator.TONE_CDMA_HIGH_L, 400)
                    delay(700) // beep ~every 0.7s
                    if (volume < 100) volume = (volume + ALERT_VOLUME_STEP).coerceAtMost(100)
                }
            } catch (_: Exception) {}
        }
    }

    private fun stopAlertLoop() {
        alertActive = false
        alertJob?.cancel()
        alertJob = null
        try { toneGen?.stopTone() } catch (_: Exception) {}
    }

    private fun startVibrate() {
        if (isVibrating) return
        val v = vibrator ?: return
        // Repeating buzz: vibrate 450 ms, pause 350 ms, repeat from index 0.
        val pattern = longArrayOf(0, 450, 350)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                v.vibrate(VibrationEffect.createWaveform(pattern, 0))
            } else {
                @Suppress("DEPRECATION")
                v.vibrate(pattern, 0)
            }
            isVibrating = true
        } catch (_: Exception) {}
    }

    private fun stopVibrate() {
        if (!isVibrating) return
        try { vibrator?.cancel() } catch (_: Exception) {}
        isVibrating = false
    }

    // --- Settings ---
    fun setSpeedLimit(v: Int) {
        val c = v.coerceIn(0, 400); speedLimit.value = c; repo.saveSpeedLimit(c)
    }
    fun setSpeedUnit(u: String) { speedUnit.value = u; repo.saveSpeedUnit(u) }
    fun setAlertSoundEnabled(e: Boolean) {
        alertSoundEnabled.value = e
        repo.saveAlertSound(e)
        // Re-evaluate immediately so we switch between beep and vibrate (or silence)
        // without waiting for the next GPS fix.
        checkAlert(currentSpeedKmh.value)
    }

    // --- Trip history ---
    fun saveTrip() {
        if (distanceKm.value <= 0.0 && durationSeconds.value <= 0L) {
            gpsStatus.value = "Nothing to save yet."; return
        }
        val fmt = SimpleDateFormat("MMM d, yyyy  HH:mm", Locale.getDefault())
        val trip = Trip(
            id = System.currentTimeMillis(),
            dateTimeString = fmt.format(Date()),
            distanceKm = distanceKm.value,
            maxSpeedKmh = maxSpeedKmh.value,
            avgSpeedKmh = avgSpeedKmh.value,
            durationSeconds = durationSeconds.value
        )
        val updated = (listOf(trip) + savedTrips.value).take(50)
        savedTrips.value = updated; repo.saveTrips(updated)
        gpsStatus.value = "Trip saved successfully."
    }
    fun deleteTrip(trip: Trip) {
        val u = savedTrips.value.filter { it.id != trip.id }
        savedTrips.value = u; repo.saveTrips(u)
    }
    fun clearAllTrips() { savedTrips.value = emptyList(); repo.saveTrips(emptyList()) }

    // --- Foreground notification ---
    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, "Speed Tracking",
                NotificationManager.IMPORTANCE_LOW
            ).apply { description = "Keeps GPS speed tracking active" }
            val mgr = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            mgr.createNotificationChannel(channel)
        }
    }

    private fun startForegroundWithNotification() {
        val openIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        // Lets the user stop tracking straight from the notification shade.
        val stopIntent = PendingIntent.getService(
            this, 1,
            Intent(this, LocationTrackingService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Speed Meter active")
            .setContentText("Tracking your speed in the background")
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setContentIntent(openIntent)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop", stopIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIF_ID, notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
            )
        } else {
            startForeground(NOTIF_ID, notification)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        fusedClient.removeLocationUpdates(locationCallback)
        timerJob?.cancel()
        infoJob?.cancel()
        alertJob?.cancel()
        stopVibrate()
        toneGen?.release()
        scope.cancel()
    }

    companion object {
        const val CHANNEL_ID = "speed_tracking_channel"
        const val NOTIF_ID = 1
        const val ACTION_START = "com.example.service.action.START_TRACKING"
        const val ACTION_STOP = "com.example.service.action.STOP_TRACKING"
        private const val GPS_TIMEOUT_MS = 5000L
        // Speeds below this (km/h) are treated as standstill jitter and shown as 0.
        private const val MIN_SPEED_KMH = 1.0
        // Fixes worse than this accuracy (metres) don't contribute to distance/speed.
        private const val DISTANCE_ACCURACY_THRESHOLD_M = 25.0
        // Speeds above this (km/h) are treated as GPS spikes and discarded.
        private const val MAX_PLAUSIBLE_KMH = 360.0
        // Consecutive over-limit readings required before the alarm sounds (debounce).
        private const val ALERT_CONFIRM_READINGS = 2
        // Beep volume ramps from this up to 100 (ToneGenerator's 0–100 scale).
        private const val ALERT_START_VOLUME = 35
        private const val ALERT_VOLUME_STEP = 12
    }
}
