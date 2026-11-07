package com.childprotector.app.ui.driver

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.os.Build
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Base64
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.result.launch
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import org.json.JSONObject

import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import java.util.concurrent.Executors

import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

import com.childprotector.app.*
import com.childprotector.app.location.DriverLocationService
import com.childprotector.app.ui.common.*

@Composable
fun DriverTabs(vm: AppVM, tab: Int) {
    when (tab) {
        0 -> HomeTab(vm)
        1 -> ListTab("Student Roster 🎒", vm.children) {
            "${it.optString("name")} · ID: ${it.optString("studentId")} · Route: ${it.optString("route")}"
        }
        2 -> BoardTab(vm)
        3 -> TripsTab(vm)
        4 -> GpsTab(vm)
        5 -> SosTab(vm)
    }
}

@Composable
fun BoardTab(vm: AppVM) {
    var student by remember { mutableStateOf("") }
    var trip by remember { mutableStateOf("") }
    var phase by remember { mutableStateOf("🌅 MORNING (Pick-Up from Home)") }
    val ctx = LocalContext.current
    var cameraOk by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.CAMERA) ==
                    PackageManager.PERMISSION_GRANTED
        )
    }
    var cameraMessage by remember { mutableStateOf("") }
    var frameAvailable by remember { mutableStateOf(false) }
    var faceStatus by remember { mutableStateOf(FaceMatchStatus("Select a student to begin.")) }
    var manualOverride by remember { mutableStateOf(false) }
    var scanSubmitted by remember { mutableStateOf(false) }
    var showFaceAbout by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var arrivalBusy by remember { mutableStateOf(false) }

    val scope = rememberCoroutineScope()
    val selectedChild = vm.children.find { it.optString("id") == student }
    val registeredPhoto = selectedChild?.optString("photo").orEmpty()
        .takeUnless { it.isBlank() || it == "null" }.orEmpty()
    val destination = if (phase.contains("MORNING", ignoreCase = true)) "SCHOOL" else "HOME"
    val hasBoardingScan = vm.scans.any {
        it.optString("studentId") == student &&
                it.optString("tripId") == trip &&
                it.optString("phase") == phase
    }
    val arrivalRecorded = vm.arrivals.any {
        it.optString("studentId") == student &&
                it.optString("tripId") == trip &&
                it.optString("destination") == destination
    }

    val cam = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        cameraOk = granted
        cameraMessage = if (granted) "" else
            "Camera permission denied. Allow camera access to capture boarding evidence."
    }
    val analyzer = remember(registeredPhoto, cameraOk, student) {
        if (cameraOk && student.isNotBlank()) {
            OnDeviceFaceMatchAnalyzer(
                ctx,
                registeredPhoto,
                onStatus = { faceStatus = it },
                onFrameAvailable = { frameAvailable = true }
            )
        } else {
            null
        }
    }

    DisposableEffect(analyzer) {
        onDispose { analyzer?.close() }
    }

    LaunchedEffect(student, registeredPhoto) {
        faceStatus = if (student.isBlank()) {
            FaceMatchStatus("Select a student to begin.")
        } else if (registeredPhoto.isBlank()) {
            FaceMatchStatus("This student has no registered photo. Use manual override only after visual verification.")
        } else {
            FaceMatchStatus("Align one face in the camera to begin verification.")
        }
        frameAvailable = false
        manualOverride = false
        scanSubmitted = false
    }

    LazyColumn(Modifier.padding(16.dp)) {
        item {
            Text(
                "📷 Face Scan & Boarding Verification",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                color = CutePurplePrimary
            )

            Text(
                "Driver scans student face to verify against registered photo.",
                color = Color.DarkGray
            )

            Spacer(Modifier.height(12.dp))

            Card(Modifier.fillMaxWidth()) {
                Choice(
                    "Student",
                    vm.children.map {
                        it.optString("id") to ("👧 " + it.optString("name") + " (ID: " + it.optString("studentId") + ")")
                    },
                    student
                ) {
                    student = it
                    faceStatus = FaceMatchStatus("Align one face in the camera to begin verification.")
                    frameAvailable = false
                    manualOverride = false
                    scanSubmitted = false
                    cameraMessage = ""
                }

                Spacer(Modifier.height(8.dp))

                Choice(
                    "Active Trip",
                    vm.trips.filter {
                        it.optString("status") == "ACTIVE"
                    }.map {
                        it.optString("id") to
                                (it.optString("route") + " · " + it.optString("vehicle"))
                    },
                    trip
                ) {
                    trip = it
                }

                Spacer(Modifier.height(8.dp))

                Choice(
                    "Trip Phase / Time",
                    listOf(
                        "🌅 MORNING (Pick-Up from Home)" to "🌅 Morning Pick-Up (Home -> School)",
                        "🌆 EVENING (Drop-Off from School)" to "🌆 Evening Drop-Off (School -> Home)"
                    ),
                    phase
                ) {
                    phase = it
                }

                Spacer(Modifier.height(12.dp))

                if (cameraOk) {
                    if (analyzer != null) {
                        CameraPreview(analyzer) { error ->
                            cameraMessage = error
                            faceStatus = FaceMatchStatus(error)
                            frameAvailable = false
                        }
                    }
                } else {
                    Button(
                        onClick = {
                            cam.launch(Manifest.permission.CAMERA)
                        },
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.PhotoCamera, null)
                        Spacer(Modifier.width(8.dp))
                        Text("Start Face Camera Preview")
                    }
                }

                if (cameraMessage.isNotBlank()) {
                    Spacer(Modifier.height(6.dp))
                    Text(cameraMessage, color = MaterialTheme.colorScheme.error)
                }

                if (registeredPhoto.isBlank() && student.isNotBlank()) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "No registered reference photo is available; automatic face matching cannot run.",
                        color = MaterialTheme.colorScheme.error
                    )
                }

                Spacer(Modifier.height(8.dp))
                Text(faceStatus.message, color = if (faceStatus.confirmed) CutePurplePrimary else Color.DarkGray)
                if (faceStatus.score != null) {
                    Text(
                        "Cosine match score: ${"%.3f".format(java.util.Locale.US, faceStatus.score)}" +
                                if (faceStatus.confirmed) " · confirmed" else " · not confirmed",
                        color = if (faceStatus.confirmed) CutePurplePrimary else Color.DarkGray,
                        fontWeight = FontWeight.Bold
                    )
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(
                        checked = manualOverride,
                        onCheckedChange = { manualOverride = it }
                    )
                    Text("I verified the student's identity visually")
                }
                Text(
                    "Manual override is recorded as MANUAL and does not count as a face-match pass.",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.Gray
                )
                TextButton(onClick = { showFaceAbout = true }) {
                    Text("About face verification")
                }

                Spacer(Modifier.height(12.dp))

                BoardingConfirmButton(
                    studentSelected = student.isNotBlank(),
                    tripSelected = trip.isNotBlank(),
                    cameraGranted = cameraOk,
                    frameAvailable = frameAvailable,
                    faceMatchConfirmed = faceStatus.confirmed,
                    manualOverride = manualOverride,
                    busy = busy,
                    scanSubmitted = scanSubmitted,
                    onClick = {
                        scope.launch {
                            busy = true
                            try {
                                val currentFrame = analyzer?.latestFrameBase64()
                                    ?: error("No camera frame is available. Wait for the camera, then try again.")
                                val faceMatchPassed = faceStatus.confirmed && !manualOverride
                                vm.message = vm.apiClient()
                                    .scan(
                                        student,
                                        trip,
                                        phase,
                                        faceStatus.score.takeIf { faceMatchPassed },
                                        currentFrame,
                                        null,
                                        null,
                                        null,
                                        faceMatchPassed,
                                        if (manualOverride) "MANUAL" else "FACE_MATCH"
                                    )
                                    .optString("message", "Boarding recorded and confirmed!")
                                scanSubmitted = true
                                vm.refresh()
                            } catch (e: Exception) {
                                vm.message = e.message ?: "Boarding failed"
                            } finally {
                                busy = false
                            }
                        }
                    }
                )

                if (scanSubmitted || hasBoardingScan) {
                    Spacer(Modifier.height(8.dp))
                    if (arrivalRecorded) {
                        Text(
                            "Arrival at ${destination.lowercase()} recorded. Linked parent accounts can see this update.",
                            color = CutePurplePrimary,
                            fontWeight = FontWeight.Bold
                        )
                    } else {
                        Button(
                            enabled = !busy && !arrivalBusy && student.isNotBlank() && trip.isNotBlank(),
                            onClick = {
                                scope.launch {
                                    arrivalBusy = true
                                    try {
                                        val arrival = vm.apiClient().confirmArrival(trip, student, destination)
                                        vm.arrivals = listOf(arrival) + vm.arrivals.filterNot {
                                            it.optString("id") == arrival.optString("id")
                                        }
                                        vm.message =
                                            "Arrival at ${destination.lowercase()} recorded. Linked parents can see this update."
                                        vm.refresh()
                                    } catch (exception: Exception) {
                                        vm.message = exception.message ?: "Could not record student arrival."
                                    } finally {
                                        arrivalBusy = false
                                    }
                                }
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                if (arrivalBusy) "Saving arrival..."
                                else "Confirm ${destination.lowercase()} arrival"
                            )
                        }
                        Text(
                            "Tap only after the student reaches ${destination.lowercase()}.",
                            style = MaterialTheme.typography.bodySmall,
                            color = Color.Gray
                        )
                    }
                }

                if (vm.message.isNotBlank()) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        vm.message,
                        color = CutePurplePrimary,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }

    if (showFaceAbout) {
        AlertDialog(
            onDismissRequest = { showFaceAbout = false },
            confirmButton = {
                TextButton(onClick = { showFaceAbout = false }) { Text("Got it") }
            },
            title = { Text("About face verification") },
            text = {
                Text(
                    "This is an experimental prototype using YuNet (MIT) for face detection and " +
                            "SFace (Apache-2.0) for matching. It has no liveness detection; accuracy can vary. " +
                            "A face match must not be the only safety control. A driver must confirm boarding."
                )
            }
        )
    }
}

@Composable
fun BoardingConfirmButton(
    studentSelected: Boolean,
    tripSelected: Boolean,
    cameraGranted: Boolean,
    frameAvailable: Boolean,
    faceMatchConfirmed: Boolean,
    manualOverride: Boolean,
    busy: Boolean,
    scanSubmitted: Boolean,
    onClick: () -> Unit
) {
    Button(
        enabled = studentSelected && tripSelected && cameraGranted && frameAvailable &&
                !busy && !scanSubmitted && (faceMatchConfirmed || manualOverride),
        onClick = onClick,
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Text(if (busy) "Confirming..." else "Confirm Student Boarding", fontWeight = FontWeight.Bold)
    }
}

@Composable
fun CameraPreview(
    analyzer: OnDeviceFaceMatchAnalyzer,
    onError: (String) -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val currentOnError by rememberUpdatedState(onError)
    var previewView by remember { mutableStateOf<PreviewView?>(null) }

    AndroidView(
        factory = { viewContext ->
            PreviewView(viewContext).also { previewView = it }
        },
        modifier = Modifier
            .fillMaxWidth()
            .height(220.dp)
            .clip(RoundedCornerShape(16.dp))
    )

    DisposableEffect(previewView, lifecycleOwner, analyzer) {
        val view = previewView
        if (view == null) {
            onDispose { }
        } else {
            val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
            val executor = Executors.newSingleThreadExecutor()
            val preview = Preview.Builder().build().also {
                it.surfaceProvider = view.surfaceProvider
            }
            val imageAnalysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                .build()
                .also { it.setAnalyzer(executor, analyzer) }
            var cameraProvider: ProcessCameraProvider? = null
            var disposed = false

            cameraProviderFuture.addListener({
                if (!disposed) {
                    try {
                        val provider = cameraProviderFuture.get()
                        cameraProvider = provider
                        provider.bindToLifecycle(
                            lifecycleOwner,
                            CameraSelector.DEFAULT_FRONT_CAMERA,
                            preview,
                            imageAnalysis
                        )
                    } catch (e: Exception) {
                        currentOnError("Camera could not start: ${e.message ?: "unknown camera error"}")
                    }
                }
            }, ContextCompat.getMainExecutor(context))

            onDispose {
                disposed = true
                imageAnalysis.clearAnalyzer()
                cameraProvider?.unbind(preview, imageAnalysis)
                executor.shutdownNow()
            }
        }
    }
}

@Composable
fun TripsTab(vm: AppVM) {
    var route by remember { mutableStateOf("") }
    var vehicle by remember { mutableStateOf("") }
    var driver by remember { mutableStateOf("") }
    var tripType by remember { mutableStateOf("🌅 Morning Pick-Up (Home -> School)") }

    val scope = rememberCoroutineScope()

    LazyColumn(Modifier.padding(16.dp)) {
        item {
            Text(
                "🚌 Trip Schedule & Route Management",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                color = CutePurplePrimary
            )

            Spacer(Modifier.height(8.dp))

            Card(Modifier.fillMaxWidth()) {
                Choice(
                    "Schedule / Trip Type",
                    listOf(
                        "🌅 Morning Pick-Up (Home -> School)" to "🌅 Morning Pick-Up (Home -> School)",
                        "🌆 Evening Drop-Off (School -> Home)" to "🌆 Evening Drop-Off (School -> Home)"
                    ),
                    tripType
                ) {
                    tripType = it
                }

                Spacer(Modifier.height(8.dp))

                listOf(
                    "Route Name" to route,
                    "Vehicle Reg / Bus No." to vehicle,
                    "Driver Name" to driver
                ).forEach { (l, v) ->
                    OutlinedTextField(
                        value = v,
                        onValueChange = { nv ->
                            when (l) {
                                "Route Name" -> route = nv
                                "Vehicle Reg / Bus No." -> vehicle = nv
                                else -> driver = nv
                            }
                        },
                        label = { Text(l) },
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(6.dp))
                }

                Spacer(Modifier.height(6.dp))

                Button(
                    onClick = {
                        scope.launch {
                            try {
                                vm.message = "Trip started ($tripType)"
                                vm.apiClient().createTrip(route, vehicle, driver, tripType)
                                vm.refresh()
                            } catch (e: Exception) {
                                vm.message = e.message ?: "Trip failed"
                            }
                        }
                    },
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Start Active Trip", fontWeight = FontWeight.Bold)
                }
            }

            Spacer(Modifier.height(12.dp))
        }

        items(vm.trips) { t ->
            Card(Modifier.fillMaxWidth()) {
                Text(
                    t.optString("route") + " · " +
                            t.optString("vehicle"),
                    fontWeight = FontWeight.Bold
                )
                Text("Schedule: " + t.optString("tripType", "Morning Pick-Up"), color = Color.Gray, style = MaterialTheme.typography.bodySmall)

                Text("Status: " + t.optString("status"), color = CutePurplePrimary, fontWeight = FontWeight.Bold)

                if (t.optString("status") == "ACTIVE") {
                    Spacer(Modifier.height(6.dp))
                    Button(
                        onClick = {
                            scope.launch {
                                try {
                                    vm.apiClient().endTrip(t.optString("id"))
                                    vm.refresh()
                                } catch (e: Exception) {
                                    vm.message = e.message ?: "Could not end trip"
                                }
                            }
                        },
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = CutePinkSecondary)
                    ) {
                        Text("End Trip", fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}

@Composable
fun GpsTab(vm: AppVM) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val preferences = remember(context) {
        context.getSharedPreferences(DriverLocationService.STATUS_PREFS, android.content.Context.MODE_PRIVATE)
    }
    var running by remember { mutableStateOf(preferences.getBoolean(DriverLocationService.KEY_RUNNING, false)) }
    var lastUpload by remember { mutableLongStateOf(preferences.getLong(DriverLocationService.KEY_LAST_UPLOAD, 0L)) }
    var gpsNow by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var gpsError by remember { mutableStateOf(preferences.getString(DriverLocationService.KEY_ERROR, "").orEmpty()) }
    val activeTrip = vm.trips.firstOrNull { it.optString("status").equals("ACTIVE", true) }
    val ctx = LocalContext.current
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val locationGranted =
            permissions[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
                    permissions[Manifest.permission.ACCESS_COARSE_LOCATION] == true
        val notificationGranted = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                permissions[Manifest.permission.POST_NOTIFICATIONS] == true ||
                ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED
        if (locationGranted && notificationGranted) {
            val tripId = activeTrip?.optString("id").orEmpty()
            if (tripId.isBlank()) {
                gpsError = "No active trip is available for GPS publishing."
            } else {
                try {
                    val serviceIntent = Intent(ctx, DriverLocationService::class.java)
                        .setAction(DriverLocationService.ACTION_START)
                        .putExtra(DriverLocationService.EXTRA_TRIP_ID, tripId)
                    ContextCompat.startForegroundService(ctx, serviceIntent)
                    running = true
                    gpsError = ""
                } catch (exception: Exception) {
                    gpsError = "Could not start GPS service: ${exception.message ?: "unknown error"}"
                }
            }
        } else if (!locationGranted) {
            gpsError = "Location permission denied. Allow location to broadcast GPS."
        } else {
            gpsError = "Notification permission is required to show the live GPS foreground-service notification."
        }
    }

    LaunchedEffect(Unit) {
        while (true) {
            running = preferences.getBoolean(DriverLocationService.KEY_RUNNING, false)
            lastUpload = preferences.getLong(DriverLocationService.KEY_LAST_UPLOAD, 0L)
            gpsNow = System.currentTimeMillis()
            gpsError = preferences.getString(DriverLocationService.KEY_ERROR, "").orEmpty()
            kotlinx.coroutines.delay(1_000)
        }
    }

    Column(Modifier.padding(16.dp)) {
        Text(
            "📡 Driver Live GPS Tracking",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            color = CutePurplePrimary
        )

        Spacer(Modifier.height(8.dp))

        Card(Modifier.fillMaxWidth()) {
            Text("Initiate driver phone GPS location to broadcast live coordinates to parents and school admin. GPS publishing stays visible in a notification.")

            Spacer(Modifier.height(12.dp))

            Button(
                enabled = running || activeTrip != null,
                onClick = {
                    if (running) {
                        ctx.startService(
                            Intent(ctx, DriverLocationService::class.java)
                                .setAction(DriverLocationService.ACTION_STOP)
                        )
                        running = false
                    } else {
                        gpsError = ""
                        val permissions = buildList {
                            add(Manifest.permission.ACCESS_FINE_LOCATION)
                            add(Manifest.permission.ACCESS_COARSE_LOCATION)
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                add(Manifest.permission.POST_NOTIFICATIONS)
                            }
                        }.toTypedArray()
                        launcher.launch(permissions)
                    }
                },
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (running) CutePinkSecondary else CutePurplePrimary
                ),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    if (running) "Stop Live GPS Broadcasting" else "Initiate Live GPS Tracking",
                    fontWeight = FontWeight.Bold
                )
            }

            Spacer(Modifier.height(8.dp))

            val uploadAge = if (lastUpload == 0L) Long.MAX_VALUE
            else (gpsNow - lastUpload).coerceAtLeast(0L)
            Text(
                when {
                    !running -> "GPS publishing is stopped. Start an active trip and initiate GPS tracking."
                    lastUpload == 0L -> "GPS service started; waiting for a location fix and successful upload."
                    uploadAge > 120_000L -> "GPS service is active, but the last uploaded location is stale."
                    else -> "✓ GPS location recently uploaded to the active trip."
                },
                color = if (running && uploadAge <= 120_000L) CutePurplePrimary
                else if (gpsError.isNotBlank()) MaterialTheme.colorScheme.error else Color.Gray,
                fontWeight = FontWeight.Bold
            )
            Text(
                if (lastUpload == 0L) "Last upload unavailable · STALE"
                else {
                    val age = uploadAge
                    "Last upload ${java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date(lastUpload))} " +
                            "(${age / 60_000} min ago)" + if (age > 120_000L) " · STALE" else ""
                },
                color = if (lastUpload == 0L || uploadAge > 120_000L) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
                style = MaterialTheme.typography.bodySmall
            )
            if (gpsError.isNotBlank()) {
                Spacer(Modifier.height(8.dp))
                Text(gpsError, color = MaterialTheme.colorScheme.error)
            }
        }
    }
}

@Composable
fun SosTab(vm: AppVM) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val locationClient = remember(context) {
        LocationServices.getFusedLocationProviderClient(context)
    }
    var showConfirmation by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var outcome by remember { mutableStateOf("") }
    var succeeded by remember { mutableStateOf(false) }

    suspend fun submitSos() {
        busy = true
        outcome = ""
        try {
            val location = getCurrentLocation(locationClient)
                ?: locationClient.lastLocation.awaitLocation()
                ?: error("Unable to get GPS location. SOS was not sent.")
            val trip = vm.trips.firstOrNull { it.optString("status").equals("ACTIVE", true) }
            vm.apiClient().sos(
                trip?.optString("vehicle") ?: "Unknown",
                trip?.optString("driver") ?: "Unknown",
                trip?.optString("route") ?: "Unknown",
                location.latitude,
                location.longitude
            )
            succeeded = true
            outcome = "SOS sent successfully with GPS location."
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (exception: Exception) {
            succeeded = false
            outcome = exception.message ?: "SOS failed."
        } finally {
            busy = false
        }
    }

    val sosPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val granted = permissions[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
                permissions[Manifest.permission.ACCESS_COARSE_LOCATION] == true
        if (granted) scope.launch { submitSos() }
        else {
            succeeded = false
            outcome = "Location permission is required. SOS was not sent."
        }
    }

    Column(Modifier.padding(16.dp)) {
        Text(
            "🚨 Emergency SOS",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.error
        )

        Spacer(Modifier.height(12.dp))

        Card(Modifier.fillMaxWidth()) {
            Text(
                "Pressing this button sends an immediate transport SOS emergency alert to the school admin dashboard.",
                color = Color.DarkGray
            )

            Spacer(Modifier.height(16.dp))

            Button(
                onClick = {
                    outcome = ""
                    showConfirmation = true
                },
                enabled = !busy,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.error
                ),
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Default.Warning, null)
                Spacer(Modifier.width(8.dp))
                Text(if (busy) "SENDING SOS…" else "SEND TRANSPORT SOS", fontWeight = FontWeight.Bold)
            }

            if (outcome.isNotBlank()) {
                Spacer(Modifier.height(10.dp))
                Text(
                    outcome,
                    fontWeight = FontWeight.Bold,
                    color = if (succeeded) CutePurplePrimary else MaterialTheme.colorScheme.error
                )
            }
        }
    }
    if (showConfirmation) {
        AlertDialog(
            onDismissRequest = { showConfirmation = false },
            title = { Text("Send emergency SOS?") },
            text = { Text("This will alert school admins and include the phone's current GPS location.") },
            confirmButton = {
                TextButton(onClick = {
                    showConfirmation = false
                    val hasLocationPermission =
                        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
                                PackageManager.PERMISSION_GRANTED ||
                                ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) ==
                                PackageManager.PERMISSION_GRANTED
                    if (hasLocationPermission) scope.launch { submitSos() }
                    else sosPermissionLauncher.launch(
                        arrayOf(
                            Manifest.permission.ACCESS_FINE_LOCATION,
                            Manifest.permission.ACCESS_COARSE_LOCATION
                        )
                    )
                }) { Text("Send SOS") }
            },
            dismissButton = {
                TextButton(onClick = { showConfirmation = false }) { Text("Cancel") }
            }
        )
    }
}

private suspend fun getCurrentLocation(
    client: com.google.android.gms.location.FusedLocationProviderClient
): Location? = suspendCancellableCoroutine { continuation ->
    val token = CancellationTokenSource()
    continuation.invokeOnCancellation { token.cancel() }
    client.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, token.token)
        .addOnSuccessListener { location ->
            if (continuation.isActive) continuation.resume(location)
        }
        .addOnFailureListener { exception ->
            if (continuation.isActive) continuation.resumeWithException(exception)
        }
}

private suspend fun com.google.android.gms.tasks.Task<Location>.awaitLocation(): Location? =
    suspendCancellableCoroutine { continuation ->
        addOnSuccessListener { location ->
            if (continuation.isActive) continuation.resume(location)
        }.addOnFailureListener { exception ->
            if (continuation.isActive) continuation.resumeWithException(exception)
        }
    }
