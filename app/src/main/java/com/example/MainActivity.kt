package com.example

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.data.Trip
import com.example.ui.SpeedViewModel
import com.example.ui.theme.*

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MyApplicationTheme {
                Scaffold(
                    modifier = Modifier
                        .fillMaxSize()
                        .testTag("main_screen"),
                    contentWindowInsets = WindowInsets.safeDrawing
                ) { innerPadding ->
                    SpeedometerAppRoot(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(innerPadding)
                    )
                }
            }
        }
    }
}

@Composable
fun SpeedometerAppRoot(
    modifier: Modifier = Modifier,
    viewModel: SpeedViewModel = viewModel()
) {
    val context = LocalContext.current
    val scrollState = rememberScrollState()

    var hasPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(
                context, Manifest.permission.ACCESS_FINE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(
                context, Manifest.permission.ACCESS_COARSE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED
        )
    }

    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        hasPermission = permissions[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
                        permissions[Manifest.permission.ACCESS_COARSE_LOCATION] == true
        if (hasPermission) {
            viewModel.startTracking()
        }
    }

    Box(
        modifier = modifier
            .background(
                Brush.verticalGradient(
                    colors = listOf(SlateDark, Color(0xFF0F141B))
                )
            )
            .padding(horizontal = 16.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(scrollState),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(modifier = Modifier.height(16.dp))

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center,
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(
                    imageVector = Icons.Default.LocationOn,
                    contentDescription = "Satellite Icon",
                    tint = AccentCyan,
                    modifier = Modifier.size(28.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "Vehicle Speed Meter",
                    color = TextWhite,
                    fontWeight = FontWeight.Bold,
                    fontSize = 24.sp,
                    fontFamily = FontFamily.SansSerif,
                    modifier = Modifier.testTag("app_title")
                )
            }
            Text(
                text = "GPS speed of the vehicle you are sitting in",
                color = TextMuted,
                fontSize = 13.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .padding(top = 4.dp, bottom = 20.dp)
                    .testTag("app_subtitle")
            )

            val isTracking by viewModel.isTracking.collectAsStateWithLifecycle()
            val currentSpeedKmh by viewModel.currentSpeedKmh.collectAsStateWithLifecycle()
            val maxSpeedKmh by viewModel.maxSpeedKmh.collectAsStateWithLifecycle()
            val avgSpeedKmh by viewModel.avgSpeedKmh.collectAsStateWithLifecycle()
            val distanceKm by viewModel.distanceKm.collectAsStateWithLifecycle()
            val durationSeconds by viewModel.durationSeconds.collectAsStateWithLifecycle()
            val gpsStatus by viewModel.gpsStatus.collectAsStateWithLifecycle()
            val speedLimit by viewModel.speedLimit.collectAsStateWithLifecycle()
            val speedUnit by viewModel.speedUnit.collectAsStateWithLifecycle()
            val savedTrips by viewModel.savedTrips.collectAsStateWithLifecycle()

            val isMph = speedUnit == "mph"
            val displaySpeed = if (isMph) currentSpeedKmh * 0.621371 else currentSpeedKmh
            val displayMax = if (isMph) maxSpeedKmh * 0.621371 else maxSpeedKmh
            val displayAvg = if (isMph) avgSpeedKmh * 0.621371 else avgSpeedKmh
            val displayDistance = if (isMph) distanceKm * 0.621371 else distanceKm
            val distanceUnitLabel = if (isMph) "mi" else "km"
            val speedUnitLabel = if (isMph) "mph" else "km/h"

            SpeedDisplayCard(
                speed = displaySpeed,
                unit = speedUnitLabel,
                gpsStatus = gpsStatus,
                isTracking = isTracking,
                modifier = Modifier.padding(bottom = 16.dp)
            )

            val isAlertTriggered = speedLimit > 0 && displaySpeed > speedLimit.toDouble()

            AnimatedVisibility(
                visible = isAlertTriggered,
                enter = expandVertically() + fadeIn(),
                exit = shrinkVertically() + fadeOut()
            ) {
                SpeedAlertBanner(
                    limit = speedLimit,
                    unit = speedUnitLabel,
                    modifier = Modifier.padding(bottom = 16.dp)
                )
            }

            TelemetryGrid(
                maxSpeed = displayMax,
                avgSpeed = displayAvg,
                distance = displayDistance,
                distanceUnit = distanceUnitLabel,
                speedUnit = speedUnitLabel,
                durationSeconds = durationSeconds,
                modifier = Modifier.padding(bottom = 16.dp)
            )

            val alertSoundEnabled by viewModel.alertSoundEnabled.collectAsStateWithLifecycle()
            SettingsCard(
                speedLimit = speedLimit,
                speedUnit = speedUnit,
                alertSoundEnabled = alertSoundEnabled,
                onLimitChange = { viewModel.setSpeedLimit(it) },
                onUnitChange = { viewModel.setSpeedUnit(it) },
                onAlertSoundToggle = { viewModel.setAlertSoundEnabled(it) },
                modifier = Modifier.padding(bottom = 18.dp)
            )

            ControlsRow(
                isTracking = isTracking,
                hasPermission = hasPermission,
                onRequestPermissions = {
                    launcher.launch(
                        arrayOf(
                            Manifest.permission.ACCESS_FINE_LOCATION,
                            Manifest.permission.ACCESS_COARSE_LOCATION
                        )
                    )
                },
                onStart = { viewModel.startTracking() },
                onPause = { viewModel.pauseTracking() },
                modifier = Modifier.padding(bottom = 12.dp)
            )

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 20.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Button(
                    onClick = { viewModel.resetTrip() },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color.Transparent,
                        contentColor = AccentRed
                    ),
                    modifier = Modifier
                        .weight(1f)
                        .height(48.dp)
                        .border(1.dp, AccentRed.copy(alpha = 0.5f), RoundedCornerShape(12.dp))
                        .testTag("reset_trip_button"),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Icon(Icons.Default.Refresh, contentDescription = "Reset", modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Reset Trip", fontWeight = FontWeight.Bold)
                }

                Button(
                    onClick = { viewModel.saveTrip() },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = AccentCyan,
                        contentColor = SlateDark
                    ),
                    modifier = Modifier
                        .weight(1f)
                        .height(48.dp)
                        .testTag("save_trip_button"),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Icon(Icons.Default.Check, contentDescription = "Save", modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Save Trip", fontWeight = FontWeight.Bold)
                }
            }

            SavedTripsHistoryList(
                trips = savedTrips,
                isMph = isMph,
                onDelete = { viewModel.deleteTrip(it) },
                onClearAll = { viewModel.clearAllTrips() },
                modifier = Modifier.padding(bottom = 20.dp)
            )

            Text(
                text = "Use on a real mobile device with GPS receiver enabled. Precise speed measure requires satellite connection outdoors. For safety, do not operate the app while driving.",
                color = TextMuted,
                fontSize = 11.sp,
                lineHeight = 15.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .padding(horizontal = 8.dp)
                    .padding(bottom = 32.dp)
                    .testTag("safety_disclaimer")
            )
        }

        if (!hasPermission) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.85f))
                    .clickable(enabled = false) {},
                contentAlignment = Alignment.Center
            ) {
                Card(
                    colors = CardDefaults.cardColors(containerColor = SlateNavy),
                    modifier = Modifier
                        .fillMaxWidth(0.9f)
                        .border(1.dp, SlateSteel, RoundedCornerShape(24.dp)),
                    shape = RoundedCornerShape(24.dp)
                ) {
                    Column(
                        modifier = Modifier.padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Icon(
                            imageVector = Icons.Default.Warning,
                            contentDescription = "Warning",
                            tint = AccentGold,
                            modifier = Modifier.size(48.dp)
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            text = "GPS Location Required",
                            color = TextWhite,
                            fontWeight = FontWeight.Bold,
                            fontSize = 20.sp,
                            textAlign = TextAlign.Center
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "Speed Meter uses the device's internal GPS hardware receiver to capture accurate speed recordings without mobile internet. Please accept location permissions to proceed.",
                            color = TextMuted,
                            fontSize = 13.sp,
                            lineHeight = 18.sp,
                            textAlign = TextAlign.Center
                        )
                        Spacer(modifier = Modifier.height(24.dp))
                        Button(
                            onClick = {
                                launcher.launch(
                                    arrayOf(
                                        Manifest.permission.ACCESS_FINE_LOCATION,
                                        Manifest.permission.ACCESS_COARSE_LOCATION
                                    )
                                )
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = AccentGreen, contentColor = SlateDark),
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(48.dp)
                                .testTag("request_permission_button"),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Text("Grant Location Permissions", fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun SpeedDisplayCard(
    speed: Double,
    unit: String,
    gpsStatus: String,
    isTracking: Boolean,
    modifier: Modifier = Modifier
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = SlateNavy),
        modifier = modifier
            .fillMaxWidth()
            .border(1.dp, SlateSteel, RoundedCornerShape(26.dp)),
        shape = RoundedCornerShape(26.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 28.dp, horizontal = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            val roundedSpeed = Math.round(speed).toInt()
            Text(
                text = "$roundedSpeed",
                color = if (roundedSpeed > 0) AccentGreen else TextWhite,
                fontSize = 84.sp,
                fontWeight = FontWeight.Black,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier.testTag("current_speed_value")
            )

            Text(
                text = unit,
                color = TextMuted,
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier
                    .padding(top = 4.dp, bottom = 12.dp)
                    .testTag("current_speed_unit")
            )

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center,
                modifier = Modifier
                    .clip(RoundedCornerShape(12.dp))
                    .background(SlateSteel.copy(alpha = 0.5f))
                    .padding(vertical = 6.dp, horizontal = 12.dp)
            ) {
                val dotColor = if (isTracking) AccentGreen else AccentGold
                val infiniteDotTransition = rememberInfiniteTransition(label = "dot")
                val dotAlpha by infiniteDotTransition.animateFloat(
                    initialValue = 0.4f,
                    targetValue = 1f,
                    animationSpec = infiniteRepeatable(
                        animation = tween(800, easing = LinearEasing),
                        repeatMode = RepeatMode.Reverse
                    ),
                    label = "dotAlpha"
                )

                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .scale(if (isTracking) dotAlpha else 1f)
                        .clip(CircleShape)
                        .background(dotColor)
                )

                Spacer(modifier = Modifier.width(8.dp))

                Text(
                    text = gpsStatus,
                    color = TextWhite,
                    fontSize = 12.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.testTag("gps_status")
                )
            }
        }
    }
}

@Composable
fun SpeedAlertBanner(
    limit: Int,
    unit: String,
    modifier: Modifier = Modifier
) {
    val infiniteTransition = rememberInfiniteTransition(label = "flash")
    val bannerScale by infiniteTransition.animateFloat(
        initialValue = 0.98f,
        targetValue = 1.02f,
        animationSpec = infiniteRepeatable(
            animation = tween(600, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "bannerScale"
    )

    Card(
        colors = CardDefaults.cardColors(containerColor = AccentRed),
        modifier = modifier
            .fillMaxWidth()
            .scale(bannerScale)
            .border(1.5.dp, Color.White, RoundedCornerShape(16.dp))
            .testTag("speed_alert"),
        shape = RoundedCornerShape(16.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            Icon(
                imageVector = Icons.Default.Warning,
                contentDescription = "Alert logo",
                tint = Color.White,
                modifier = Modifier.size(24.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = "SPEED LIMIT EXCEEDED! (> $limit $unit)",
                color = Color.White,
                fontWeight = FontWeight.Bold,
                fontSize = 14.sp,
                textAlign = TextAlign.Center
            )
        }
    }
}

@Composable
fun TelemetryGrid(
    maxSpeed: Double,
    avgSpeed: Double,
    distance: Double,
    distanceUnit: String,
    speedUnit: String,
    durationSeconds: Long,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            StatsCard(
                label = "MAX SPEED",
                value = "${Math.round(maxSpeed)} $speedUnit",
                icon = Icons.Default.Speed,
                iconColor = AccentRed,
                modifier = Modifier
                    .weight(1f)
                    .testTag("stat_max_speed")
            )
            StatsCard(
                label = "AVERAGE SPEED",
                value = "${Math.round(avgSpeed)} $speedUnit",
                icon = Icons.Default.Star,
                iconColor = AccentGold,
                modifier = Modifier
                    .weight(1f)
                    .testTag("stat_avg_speed")
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            StatsCard(
                label = "DISTANCE",
                value = String.format("%.2f %s", distance, distanceUnit),
                icon = Icons.Default.LocationOn,
                iconColor = AccentCyan,
                modifier = Modifier
                    .weight(1f)
                    .testTag("stat_distance")
            )

            val hours = durationSeconds / 3600
            val minutes = (durationSeconds % 3600) / 60
            val seconds = durationSeconds % 60
            val timeString = if (hours > 0) {
                String.format("%02d:%02d:%02d", hours, minutes, seconds)
            } else {
                String.format("%02d:%02d", minutes, seconds)
            }

            StatsCard(
                label = "DURATION",
                value = timeString,
                icon = Icons.Default.Schedule,
                iconColor = TextWhite,
                modifier = Modifier
                    .weight(1f)
                    .testTag("stat_duration")
            )
        }
    }
}

@Composable
fun StatsCard(
    label: String,
    value: String,
    icon: ImageVector,
    iconColor: Color,
    modifier: Modifier = Modifier
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = SlateNavy),
        modifier = modifier.border(1.dp, SlateSteel.copy(alpha = 0.5f), RoundedCornerShape(18.dp)),
        shape = RoundedCornerShape(18.dp)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = iconColor,
                    modifier = Modifier.size(14.dp)
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = label,
                    color = TextMuted,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 0.5.sp
                )
            }
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = value,
                color = TextWhite,
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace
            )
        }
    }
}

@Composable
fun SettingsCard(
    speedLimit: Int,
    speedUnit: String,
    alertSoundEnabled: Boolean,
    onLimitChange: (Int) -> Unit,
    onUnitChange: (String) -> Unit,
    onAlertSoundToggle: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = SlateNavy),
        modifier = modifier
            .fillMaxWidth()
            .border(1.dp, SlateSteel, RoundedCornerShape(18.dp)),
        shape = RoundedCornerShape(18.dp)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = "SPEED MEASURE UNIT",
                color = TextMuted,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier
                    .padding(bottom = 8.dp)
                    .align(Alignment.Start)
            )

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(SlateDark)
                    .padding(4.dp),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                val options = listOf("kmh" to "km/h", "mph" to "mph")
                options.forEach { (key, name) ->
                    val isSelected = speedUnit == key
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(8.dp))
                            .background(if (isSelected) AccentCyan else Color.Transparent)
                            .clickable { onUnitChange(key) }
                            .padding(vertical = 8.dp)
                            .testTag("unit_option_$key"),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = name,
                            color = if (isSelected) SlateDark else TextWhite,
                            fontWeight = FontWeight.Bold,
                            fontSize = 13.sp
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            Text(
                text = "MAXIMUM SPEED LIMIT ALERT",
                color = TextMuted,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier
                    .padding(bottom = 8.dp)
                    .align(Alignment.Start)
            )

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center,
                modifier = Modifier.fillMaxWidth()
            ) {
                IconButton(
                    onClick = { if (speedLimit > 5) onLimitChange(speedLimit - 5) },
                    modifier = Modifier
                        .size(40.dp)
                        .clip(CircleShape)
                        .background(SlateSteel)
                        .testTag("decrement_limit_button")
                ) {
                    Icon(Icons.Default.KeyboardArrowDown, contentDescription = "Decrease limit", tint = TextWhite)
                }

                Text(
                    text = "$speedLimit",
                    color = TextWhite,
                    fontSize = 28.sp,
                    fontWeight = FontWeight.Black,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier
                        .padding(horizontal = 24.dp)
                        .testTag("speed_limit_value_display")
                )

                IconButton(
                    onClick = { onLimitChange(speedLimit + 5) },
                    modifier = Modifier
                        .size(40.dp)
                        .clip(CircleShape)
                        .background(SlateSteel)
                        .testTag("increment_limit_button")
                ) {
                    Icon(Icons.Default.KeyboardArrowUp, contentDescription = "Increase limit", tint = TextWhite)
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(SlateDark)
                    .clickable { onAlertSoundToggle(!alertSoundEnabled) }
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.Warning,
                        contentDescription = "Warning Logo",
                        tint = if (alertSoundEnabled) AccentGold else TextMuted,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Column {
                        Text(
                            text = "Warning Ring Sound",
                            color = TextWhite,
                            fontWeight = FontWeight.Bold,
                            fontSize = 13.sp
                        )
                        Text(
                            text = if (alertSoundEnabled) "Alarm audio loop is ON" else "Alarm audio loop is OFF",
                            color = TextMuted,
                            fontSize = 11.sp
                        )
                    }
                }

                Switch(
                    checked = alertSoundEnabled,
                    onCheckedChange = onAlertSoundToggle,
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = AccentGreen,
                        checkedTrackColor = AccentGreen.copy(alpha = 0.5f),
                        uncheckedThumbColor = TextMuted,
                        uncheckedTrackColor = SlateSteel
                    ),
                    modifier = Modifier.testTag("sound_alert_toggle")
                )
            }
        }
    }
}

@Composable
fun ControlsRow(
    isTracking: Boolean,
    hasPermission: Boolean,
    onRequestPermissions: () -> Unit,
    onStart: () -> Unit,
    onPause: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        if (!isTracking) {
            Button(
                onClick = { if (hasPermission) onStart() else onRequestPermissions() },
                colors = ButtonDefaults.buttonColors(
                    containerColor = AccentGreen,
                    contentColor = SlateDark
                ),
                modifier = Modifier
                    .weight(1f)
                    .height(56.dp)
                    .testTag("start_tracking_button"),
                shape = RoundedCornerShape(16.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.PlayArrow,
                    contentDescription = "Start Icon",
                    modifier = Modifier.size(24.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(text = "START TRACKING", fontWeight = FontWeight.Black, fontSize = 15.sp)
            }
        } else {
            Button(
                onClick = onPause,
                colors = ButtonDefaults.buttonColors(
                    containerColor = AccentGold,
                    contentColor = SlateDark
                ),
                modifier = Modifier
                    .weight(1f)
                    .height(56.dp)
                    .testTag("pause_tracking_button"),
                shape = RoundedCornerShape(16.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Pause,
                    contentDescription = "Pause Icon",
                    modifier = Modifier.size(24.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(text = "PAUSE TRACKING", fontWeight = FontWeight.Black, fontSize = 15.sp)
            }
        }
    }
}

@Composable
fun SavedTripsHistoryList(
    trips: List<Trip>,
    isMph: Boolean,
    onDelete: (Trip) -> Unit,
    onClearAll: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Trip History",
                color = TextWhite,
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.testTag("history_header")
            )

            if (trips.isNotEmpty()) {
                Text(
                    text = "Clear All",
                    color = AccentRed,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier
                        .clickable { onClearAll() }
                        .padding(8.dp)
                        .testTag("clear_history_button")
                )
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        if (trips.isEmpty()) {
            Card(
                colors = CardDefaults.cardColors(containerColor = SlateNavy.copy(alpha = 0.5f)),
                modifier = Modifier
                    .fillMaxWidth()
                    .border(1.dp, SlateSteel.copy(alpha = 0.3f), RoundedCornerShape(14.dp)),
                shape = RoundedCornerShape(14.dp)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = "No saved trips yet.",
                        color = TextMuted,
                        fontSize = 14.sp,
                        textAlign = TextAlign.Center
                    )
                    Text(
                        text = "Complete tracking and tap Save to record your drive statistics here.",
                        color = TextMuted.copy(alpha = 0.7f),
                        fontSize = 11.sp,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }
            }
        } else {
            trips.forEach { trip ->
                TripHistoryItem(
                    trip = trip,
                    isMph = isMph,
                    onDelete = { onDelete(trip) },
                    modifier = Modifier.padding(bottom = 8.dp)
                )
            }
        }
    }
}

@Composable
fun TripHistoryItem(
    trip: Trip,
    isMph: Boolean,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier
) {
    val distanceUnit = if (isMph) "mi" else "km"
    val speedUnit = if (isMph) "mph" else "km/h"

    val displayDistance = if (isMph) trip.distanceKm * 0.621371 else trip.distanceKm
    val displayMax = if (isMph) trip.maxSpeedKmh * 0.621371 else trip.maxSpeedKmh
    val displayAvg = if (isMph) trip.avgSpeedKmh * 0.621371 else trip.avgSpeedKmh

    Card(
        colors = CardDefaults.cardColors(containerColor = SlateNavy),
        modifier = modifier
            .fillMaxWidth()
            .border(1.dp, SlateSteel, RoundedCornerShape(14.dp))
            .testTag("trip_history_item"),
        shape = RoundedCornerShape(14.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = trip.dateTimeString,
                    color = TextWhite,
                    fontWeight = FontWeight.Bold,
                    fontSize = 13.sp
                )

                Spacer(modifier = Modifier.height(6.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Column {
                        Text("DISTANCE", color = TextMuted, fontSize = 9.sp, fontWeight = FontWeight.Bold)
                        Text(String.format("%.2f %s", displayDistance, distanceUnit), color = AccentCyan, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }

                    Column {
                        Text("MAX SPEED", color = TextMuted, fontSize = 9.sp, fontWeight = FontWeight.Bold)
                        Text("${Math.round(displayMax)} $speedUnit", color = AccentRed, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }

                    Column {
                        Text("AVG SPEED", color = TextMuted, fontSize = 9.sp, fontWeight = FontWeight.Bold)
                        Text("${Math.round(displayAvg)} $speedUnit", color = AccentGreen, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }

            IconButton(
                onClick = onDelete,
                modifier = Modifier
                    .clip(CircleShape)
                    .background(SlateSteel.copy(alpha = 0.3f))
                    .size(36.dp)
                    .testTag("delete_trip_item_button")
            ) {
                Icon(
                    imageVector = Icons.Default.Delete,
                    contentDescription = "Delete item",
                    tint = AccentRed.copy(alpha = 0.8f),
                    modifier = Modifier.size(16.dp)
                )
            }
        }
    }
}
