package com.childprotector.app

import android.Manifest
import android.content.pm.PackageManager
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
import org.json.JSONObject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withTimeoutOrNull
import androidx.lifecycle.viewModelScope
import okhttp3.WebSocket
import com.childprotector.app.realtime.RealtimeClient

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
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationResult

import com.childprotector.app.ui.auth.AuthScreen
import com.childprotector.app.ui.common.*
import com.childprotector.app.ui.driver.*
import com.childprotector.app.ui.parent.*
import com.childprotector.app.ui.admin.*

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            ChildProtectorTheme {
                AppRoot()
            }
        }
    }
}

class AppVM(private val api: Api) : androidx.lifecycle.ViewModel() {
    var role by mutableStateOf<String?>(null)
    var loading by mutableStateOf(false)
    var message by mutableStateOf("")

    var children by mutableStateOf(listOf<JSONObject>())
    var trips by mutableStateOf(listOf<JSONObject>())
    var scans by mutableStateOf(listOf<JSONObject>())
    var emergencies by mutableStateOf(listOf<JSONObject>())
    var selectedChild by mutableStateOf<JSONObject?>(null)
    var adminOverview by mutableStateOf<JSONObject?>(null)
    var realtimeConnected by mutableStateOf(false)

    private var realtimeJob: Job? = null
    private var realtimeSocket: WebSocket? = null
    private val realtimeClient = RealtimeClient(api)

    suspend fun refresh() {
        loading = true
        message = ""
        try {
            if (apiRole() == "PARENT") {
                children = api.parentChildren()
                trips = api.parentTrips()
                scans = api.parentScans(Api.today())
                emergencies = api.parentEmergencies()

                selectedChild = selectedChild?.let { x ->
                    children.find {
                        it.optString("id") == x.optString("id")
                    }
                } ?: children.firstOrNull()
            } else if (apiRole() == "ADMIN") {
                children = api.children()
                trips = api.trips()
                scans = api.scans(Api.today())
                emergencies = api.emergencies()
                adminOverview = api.adminOverview()
            } else {
                children = api.children()
                trips = api.trips()
                scans = api.scans(Api.today())
                emergencies = api.emergencies()
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (e: Exception) {
            message = e.message ?: "Could not load data"
        } finally {
            loading = false
        }
    }

    fun startRealtime() {
        if (realtimeJob?.isActive == true || role == null || storeRef?.session.isNullOrBlank()) return
        realtimeJob = viewModelScope.launch {
            var reconnectDelay = 1_000L
            launch {
                while (isActive) {
                    delay(10_000)
                    if (!realtimeConnected && role != null) refresh()
                }
            }
            while (isActive && role != null) {
                val opened = CompletableDeferred<Boolean>()
                val disconnected = CompletableDeferred<Unit>()
                try {
                    realtimeSocket = realtimeClient.connect(
                        onOpen = {
                            viewModelScope.launch {
                                realtimeConnected = true
                                opened.complete(true)
                            }
                        },
                        onMessage = { text ->
                            viewModelScope.launch { applyRealtimeMessage(text) }
                        },
                        onDisconnect = {
                            viewModelScope.launch { realtimeConnected = false }
                            disconnected.complete(Unit)
                            opened.complete(false)
                        }
                    )
                    val connected = withTimeoutOrNull(15_000) { opened.await() } == true
                    if (connected) {
                        reconnectDelay = 1_000L
                        disconnected.await()
                    } else {
                        realtimeSocket?.cancel()
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    realtimeConnected = false
                }
                realtimeSocket = null
                realtimeConnected = false
                delay(reconnectDelay)
                reconnectDelay = (reconnectDelay * 2).coerceAtMost(30_000L)
            }
        }
    }

    fun stopRealtime() {
        realtimeJob?.cancel()
        realtimeJob = null
        realtimeSocket?.close(1000, "Session closed")
        realtimeSocket = null
        realtimeConnected = false
    }

    private fun applyRealtimeMessage(raw: String) {
        val event = try {
            JSONObject(raw)
        } catch (_: Exception) {
            return
        }
        val type = event.optString("type", event.optString("event"))
            .lowercase().replace('_', '-').replace('.', '-')
        val data = event.optJSONObject("data")
            ?: event.optJSONObject("payload")
            ?: event
        when {
            type.contains("location") -> {
                val tripId = data.optString("tripId", data.optString("trip_id"))
                if (tripId.isBlank()) return
                val location = JSONObject(data.toString()).put("id", tripId)
                if (!location.has("latitude")) {
                    val latitude = data.optDouble("lat", Double.NaN)
                    if (latitude.isFinite()) location.put("latitude", latitude)
                }
                if (!location.has("longitude")) {
                    val longitude = data.optDouble("lon", data.optDouble("lng", Double.NaN))
                    if (longitude.isFinite()) location.put("longitude", longitude)
                }
                if (!location.has("locationUpdatedAt") && !location.has("updatedAt")) {
                    location.put(
                        "locationUpdatedAt",
                        data.opt("timestamp") ?: System.currentTimeMillis()
                    )
                }
                trips = trips.map { trip ->
                    if (trip.optString("id") != tripId) trip else JSONObject(trip.toString()).apply {
                        listOf("latitude", "longitude", "accuracy", "timestamp", "locationUpdatedAt", "updatedAt")
                            .forEach { key -> if (location.has(key)) put(key, location.opt(key)) }
                        if (!has("locationUpdatedAt")) put("locationUpdatedAt", System.currentTimeMillis())
                    }
                }
            }
            type.contains("scan") -> scans = upsert(scans, data.optJSONObject("scan") ?: data)
            type.contains("emergency") -> emergencies =
                upsert(emergencies, data.optJSONObject("emergency") ?: data)
            type.contains("trip") -> {
                val trip = JSONObject((data.optJSONObject("trip") ?: data).toString())
                if (!trip.has("id")) {
                    val tripId = data.optString("tripId", data.optString("trip_id"))
                    if (tripId.isNotBlank()) trip.put("id", tripId)
                }
                val ended = type.contains("end") || type.contains("stop")
                if (ended) trip.put("status", "COMPLETED")
                else if (type.contains("start")) trip.put("status", "ACTIVE")
                trips = upsert(trips, trip)
            }
        }
    }

    private fun upsert(items: List<JSONObject>, item: JSONObject): List<JSONObject> {
        val normalized = JSONObject(item.toString())
        val id = normalized.optString(
            "id",
            normalized.optString(
                "scanId",
                normalized.optString("emergencyId", normalized.optString("tripId", normalized.optString("trip_id")))
            )
        )
        if (id.isBlank()) return listOf(item) + items
        normalized.put("id", id)
        val existingIndex = items.indexOfFirst { it.optString("id") == id }
        if (existingIndex < 0) return listOf(normalized) + items
        val merged = JSONObject(items[existingIndex].toString())
        normalized.keys().forEach { key -> merged.put(key, normalized.opt(key)) }
        return items.toMutableList().also { it[existingIndex] = merged }
    }

    private fun apiRole() = role

    fun logout() {
        stopRealtime()
        apiStore().clear()
        role = null
        children = emptyList()
        trips = emptyList()
        scans = emptyList()
        emergencies = emptyList()
        selectedChild = null
        adminOverview = null
        message = ""
    }

    fun store() = apiStore()
    fun apiClient() = api

    private fun apiStore() = storeRef ?: error("store")

    private var storeRef: SessionStore? = null

    fun bindStore(s: SessionStore) {
        storeRef = s
        api.setUnauthorizedHandler {
            Handler(Looper.getMainLooper()).post {
                role = null
                children = emptyList()
                trips = emptyList()
                scans = emptyList()
                emergencies = emptyList()
                selectedChild = null
                message = "Session expired"
            }
        }
        role = s.role
    }
}

@Composable
fun AppRoot() {
    val ctx = LocalContext.current
    val store = remember { SessionStore(ctx) }
    val api = remember { Api(store) }

    val vm: AppVM = viewModel(
        factory = object : androidx.lifecycle.ViewModelProvider.Factory {
            override fun <T : androidx.lifecycle.ViewModel> create(
                c: Class<T>
            ): T = AppVM(api).also {
                it.bindStore(store)
            } as T
        }
    )

    if (vm.role == null) {
        AuthScreen(vm)
    } else {
        MainScreen(vm)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(vm: AppVM) {
    var tab by remember { mutableIntStateOf(0) }
    var adminPage by remember { mutableStateOf("Overview") }
    var showAdminMore by remember { mutableStateOf(false) }

    LaunchedEffect(vm.role) {
        tab = 0
        adminPage = "Overview"
        vm.refresh()
    }
    DisposableEffect(vm.role) {
        vm.startRealtime()
        onDispose { vm.stopRealtime() }
    }

    CuteBackground {
        Scaffold(
            containerColor = Color.Transparent,
            topBar = {
                TopAppBar(
                    title = {
                        Column {
                            Text(
                                when (vm.role) {
                                    "ADMIN" -> "⚙️ Admin Dashboard"
                                    "DRIVER" -> "🚌 Driver Section"
                                    else -> "💜 Parent Section"
                                },
                                fontWeight = FontWeight.Bold,
                                color = CutePurplePrimary
                            )
                            Text(
                                if (vm.realtimeConnected) "● Live updates connected" else "● Reconnecting · polling",
                                style = MaterialTheme.typography.labelSmall,
                                color = if (vm.realtimeConnected) CutePurplePrimary else Color.Gray
                            )
                        }
                    },
                    actions = {
                        if (vm.role == "ADMIN") {
                            Box {
                                TextButton(onClick = { showAdminMore = true }) {
                                    Text("More", color = CutePurplePrimary)
                                }
                                DropdownMenu(
                                    expanded = showAdminMore,
                                    onDismissRequest = { showAdminMore = false }
                                ) {
                                    listOf("Live GPS", "Attendance", "Emergencies", "Parent Access")
                                        .forEach { page ->
                                            DropdownMenuItem(
                                                text = { Text(page) },
                                                onClick = {
                                                    adminPage = page
                                                    showAdminMore = false
                                                }
                                            )
                                        }
                                }
                            }
                        }
                        IconButton(onClick = { vm.logout() }) {
                            Icon(Icons.Default.Logout, "Sign out", tint = CutePurplePrimary)
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = Color.White.copy(alpha = 0.8f)
                    )
                )
            },
            bottomBar = {
                NavigationBar(
                    containerColor = Color.White.copy(alpha = 0.9f)
                ) {
                    val items = when (vm.role) {
                        "ADMIN" -> listOf("Overview" to Icons.Default.Home, "Students" to Icons.Default.People, "Trips" to Icons.Default.DirectionsBus)
                        "DRIVER" -> listOf(
                            "Home" to Icons.Default.Home,
                            "Students" to Icons.Default.People,
                            "Board" to Icons.Default.QrCodeScanner,
                            "Trips" to Icons.Default.DirectionsBus,
                            "GPS" to Icons.Default.LocationOn,
                            "SOS" to Icons.Default.Warning
                        )
                        else -> listOf(
                            "Home" to Icons.Default.Home,
                            "My Child" to Icons.Default.Face,
                            "Attendance" to Icons.Default.CheckCircle,
                            "Tracking" to Icons.Default.LocationOn,
                            "SOS" to Icons.Default.Warning
                        )
                    }

                    items.forEachIndexed { i, (label, icon) ->
                        NavigationBarItem(
                            selected = if (vm.role == "ADMIN") adminPage == label else tab == i,
                            onClick = {
                                if (vm.role == "ADMIN") adminPage = label else tab = i
                            },
                            icon = {
                                Icon(
                                    icon,
                                    label,
                                    tint = if (
                                        if (vm.role == "ADMIN") adminPage == label else tab == i
                                    ) CutePurplePrimary else Color.Gray
                                )
                            },
                            label = {
                                Text(
                                    label,
                                    color = if (
                                        if (vm.role == "ADMIN") adminPage == label else tab == i
                                    ) CutePurplePrimary else Color.Gray,
                                    fontWeight = if (
                                        if (vm.role == "ADMIN") adminPage == label else tab == i
                                    ) FontWeight.Bold else FontWeight.Normal
                                )
                            }
                        )
                    }
                }
            }
        ) { pad ->
            Box(
                Modifier
                    .padding(pad)
                    .fillMaxSize()
            ) {
                when (vm.role) {
                    "ADMIN" -> AdminScreen(vm, adminPage)
                    "DRIVER" -> DriverTabs(vm, tab)
                    else -> ParentTabs(vm, tab)
                }
            }
        }
    }
}
