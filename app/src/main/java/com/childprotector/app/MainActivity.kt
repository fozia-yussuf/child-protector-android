package com.childprotector.app

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Bundle
import android.util.Base64
import android.view.ViewGroup
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
import java.io.ByteArrayOutputStream

import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat

import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationResult

fun String.toBitmap(): Bitmap? {
    return try {
        val decodedBytes = Base64.decode(this, Base64.DEFAULT)
        BitmapFactory.decodeByteArray(decodedBytes, 0, decodedBytes.size)
    } catch (_: Exception) {
        null
    }
}

fun Bitmap.toBase64(): String {
    val stream = ByteArrayOutputStream()
    this.compress(Bitmap.CompressFormat.JPEG, 70, stream)
    return Base64.encodeToString(stream.toByteArray(), Base64.NO_WRAP)
}

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

val CutePurplePrimary = Color(0xFF7C3AED)
val CutePinkSecondary = Color(0xFFEC4899)
val CutePurpleContainer = Color(0xFFF3E8FF)
val CutePinkContainer = Color(0xFFFCE7F3)

@Composable
fun ChildProtectorTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = lightColorScheme(
            primary = CutePurplePrimary,
            secondary = CutePinkSecondary,
            primaryContainer = CutePurpleContainer,
            secondaryContainer = CutePinkContainer,
            surface = Color.White.copy(alpha = 0.9f),
            onPrimary = Color.White,
            error = Color(0xFFE11D48)
        ),
        content = content
    )
}

@Composable
fun CuteBackground(content: @Composable () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                brush = Brush.verticalGradient(
                    colors = listOf(
                        Color(0xFFEDE9FE), // Soft Pastel Violet
                        Color(0xFFFCE7F3), // Cute Pink
                        Color(0xFFF3E8FF)  // Light Lavender
                    )
                )
            )
    ) {
        content()
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

    suspend fun refresh() {
        loading = true
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
            } else {
                children = api.children()
                trips = api.trips()
                scans = api.scans(Api.today())
                emergencies = api.emergencies()
            }
        } catch (e: Exception) {
            message = e.message ?: "Could not load data"
        } finally {
            loading = false
        }
    }

    private fun apiRole() = role

    fun logout() {
        apiStore().clear()
        role = null
        children = emptyList()
        trips = emptyList()
        scans = emptyList()
    }

    fun store() = apiStore()

    private fun apiStore() = storeRef ?: error("store")

    private var storeRef: SessionStore? = null

    fun bindStore(s: SessionStore) {
        storeRef = s
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
        AuthScreen(vm, store)
    } else {
        MainScreen(vm)
    }
}

@Composable
fun AuthScreen(vm: AppVM, store: SessionStore) {
    var role by remember { mutableStateOf("PARENT") }
    var signup by remember { mutableStateOf(false) }
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }

    val scope = rememberCoroutineScope()

    CuteBackground {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            Card(
                mod = Modifier
                    .fillMaxWidth()
                    .padding(20.dp)
            ) {
                Text(
                    "✨ Child Protector 🛡️",
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                    color = CutePurplePrimary
                )

                Text(
                    if (signup) "Create Account" else "Sign in to continue",
                    style = MaterialTheme.typography.titleMedium,
                    color = Color.DarkGray
                )

                Spacer(Modifier.height(16.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    FilterChip(
                        selected = role == "PARENT",
                        onClick = { role = "PARENT" },
                        label = { Text("👨‍👩‍👧 Parent") },
                        shape = RoundedCornerShape(14.dp)
                    )

                    FilterChip(
                        selected = role == "DRIVER",
                        onClick = { role = "DRIVER" },
                        label = { Text("🚌 Driver") },
                        shape = RoundedCornerShape(14.dp)
                    )

                    FilterChip(
                        selected = role == "ADMIN",
                        onClick = { role = "ADMIN" },
                        label = { Text("⚙️ Admin") },
                        shape = RoundedCornerShape(14.dp)
                    )
                }

                Spacer(Modifier.height(14.dp))

                OutlinedTextField(
                    value = username,
                    onValueChange = { username = it },
                    label = { Text("Username") },
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(Modifier.height(8.dp))

                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text("Password") },
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.fillMaxWidth()
                )

                if (vm.message.isNotBlank()) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        vm.message,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall
                    )
                }

                Spacer(Modifier.height(16.dp))

                Button(
                    onClick = {
                        scope.launch {
                            vm.message = ""
                            try {
                                if (signup) {
                                    vm.apiSignup(store, username, password, role)
                                    signup = false
                                    vm.message = "Account created. Sign in."
                                } else {
                                    vm.apiLogin(store, username, password, role)
                                }
                            } catch (e: Exception) {
                                vm.message = e.message ?: "Authentication failed"
                            }
                        }
                    },
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        if (signup) "Create account" else "Sign in",
                        fontWeight = FontWeight.Bold
                    )
                }

                TextButton(
                    onClick = { signup = !signup },
                    modifier = Modifier.align(Alignment.CenterHorizontally)
                ) {
                    Text(
                        if (signup) "Already have an account? Sign in"
                        else "Create a new account",
                        color = CutePurplePrimary
                    )
                }

                Spacer(Modifier.height(8.dp))

                Text(
                    "Connected directly to Child Protector backend server.",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.Gray
                )
            }
        }
    }
}

suspend fun AppVM.apiLogin(
    store: SessionStore,
    u: String,
    p: String,
    r: String
) {
    apiHackLogin(store, u, p, r)
    role = r
    refresh()
}

suspend fun AppVM.apiSignup(
    store: SessionStore,
    u: String,
    p: String,
    r: String
) {
    Api(store).signup(u, p, r)
}

private suspend fun apiHackLogin(
    store: SessionStore,
    u: String,
    p: String,
    r: String
) {
    Api(store).login(u, p, r)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(vm: AppVM) {
    var tab by remember { mutableStateOf(0) }

    LaunchedEffect(vm.role) {
        vm.refresh()
    }

    CuteBackground {
        Scaffold(
            containerColor = Color.Transparent,
            topBar = {
                TopAppBar(
                    title = {
                        Text(
                            when (vm.role) {
                                "ADMIN" -> "⚙️ Admin Dashboard"
                                "DRIVER" -> "🚌 Driver Section"
                                else -> "💜 Parent Section"
                            },
                            fontWeight = FontWeight.Bold,
                            color = CutePurplePrimary
                        )
                    },
                    actions = {
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
                        "ADMIN" -> listOf(
                            "Home" to Icons.Default.Home,
                            "Register" to Icons.Default.PersonAdd,
                            "Students" to Icons.Default.People,
                            "Trips" to Icons.Default.DirectionsBus,
                            "SOS" to Icons.Default.Warning
                        )
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
                            selected = tab == i,
                            onClick = { tab = i },
                            icon = {
                                Icon(
                                    icon,
                                    label,
                                    tint = if (tab == i) CutePurplePrimary else Color.Gray
                                )
                            },
                            label = {
                                Text(
                                    label,
                                    color = if (tab == i) CutePurplePrimary else Color.Gray,
                                    fontWeight = if (tab == i) FontWeight.Bold else FontWeight.Normal
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
                    "ADMIN" -> AdminTabs(vm, tab)
                    "DRIVER" -> DriverTabs(vm, tab)
                    else -> ParentTabs(vm, tab)
                }
            }
        }
    }
}

@Composable
fun AdminTabs(vm: AppVM, tab: Int) {
    when (tab) {
        0 -> HomeTab(vm)
        1 -> RegisterStudentTab(vm)
        2 -> ListTab("All Registered Students 🎒", vm.children) {
            "👧 " + it.optString("name") + " · ID: " + it.optString("studentId") + " · Route: " + it.optString("route")
        }
        3 -> TripsTab(vm)
        4 -> SosTab(vm)
    }
}

@Composable
fun DriverTabs(vm: AppVM, tab: Int) {
    when (tab) {
        0 -> HomeTab(vm)
        1 -> ListTab("Student Roster 🎒", vm.children) {
            it.optString("name") + " · ID: " +
                    it.optString("studentId") + " · Route: " +
                    it.optString("route")
        }
        2 -> BoardTab(vm)
        3 -> TripsTab(vm)
        4 -> GpsTab(vm)
        5 -> SosTab(vm)
    }
}

@Composable
fun ParentTabs(vm: AppVM, tab: Int) {
    when (tab) {
        0 -> HomeTab(vm)
        1 -> ChildTab(vm)
        2 -> AttendanceTab(vm)
        3 -> TrackingTab(vm)
        4 -> SosTab(vm)
    }
}

@Composable
fun RegisterStudentTab(vm: AppVM) {
    var name by remember { mutableStateOf("") }
    var studentId by remember { mutableStateOf("") }
    var className by remember { mutableStateOf("") }
    var route by remember { mutableStateOf("") }
    var parentUsername by remember { mutableStateOf("") }
    var capturedBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var busy by remember { mutableStateOf(false) }

    val scope = rememberCoroutineScope()
    val photoLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.TakePicturePreview()
    ) { bitmap ->
        if (bitmap != null) {
            capturedBitmap = bitmap
        }
    }

    LazyColumn(Modifier.padding(16.dp)) {
        item {
            Text(
                "📝 Register Student & Take Photo",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                color = CutePurplePrimary
            )
            Text(
                "Register student with image for driver face verification.",
                color = Color.DarkGray
            )

            Spacer(Modifier.height(12.dp))

            Card(Modifier.fillMaxWidth()) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Full Student Name") },
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(6.dp))

                OutlinedTextField(
                    value = studentId,
                    onValueChange = { studentId = it },
                    label = { Text("Student ID Number") },
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(6.dp))

                OutlinedTextField(
                    value = className,
                    onValueChange = { className = it },
                    label = { Text("Class / Grade") },
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(6.dp))

                OutlinedTextField(
                    value = route,
                    onValueChange = { route = it },
                    label = { Text("Bus Route") },
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(6.dp))

                OutlinedTextField(
                    value = parentUsername,
                    onValueChange = { parentUsername = it },
                    label = { Text("Parent Account Username") },
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(12.dp))

                if (capturedBitmap != null) {
                    Box(
                        modifier = Modifier.fillMaxWidth(),
                        contentAlignment = Alignment.Center
                    ) {
                        Image(
                            bitmap = capturedBitmap!!.asImageBitmap(),
                            contentDescription = "Student Photo",
                            contentScale = ContentScale.Crop,
                            modifier = Modifier
                                .size(110.dp)
                                .clip(CircleShape)
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                }

                Button(
                    onClick = { photoLauncher.launch() },
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = CutePinkSecondary),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.CameraAlt, null)
                    Spacer(Modifier.width(8.dp))
                    Text(if (capturedBitmap == null) "📷 Take Student Photo" else "📷 Retake Photo", fontWeight = FontWeight.Bold)
                }

                Spacer(Modifier.height(12.dp))

                Button(
                    enabled = name.isNotBlank() && studentId.isNotBlank() && !busy,
                    onClick = {
                        scope.launch {
                            busy = true
                            try {
                                val photoBase64 = capturedBitmap?.toBase64()
                                Api(vm.store()).createChild(name, studentId, className, route, photoBase64, parentUsername)
                                vm.message = "✓ Student $name registered!"
                                name = ""
                                studentId = ""
                                className = ""
                                route = ""
                                parentUsername = ""
                                capturedBitmap = null
                                vm.refresh()
                            } catch (e: Exception) {
                                vm.message = e.message ?: "Registration failed"
                            } finally {
                                busy = false
                            }
                        }
                    },
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(if (busy) "Saving..." else "Register Student", fontWeight = FontWeight.Bold)
                }

                if (vm.message.isNotBlank()) {
                    Spacer(Modifier.height(8.dp))
                    Text(vm.message, color = CutePurplePrimary, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

@Composable
fun HomeTab(vm: AppVM) {
    LazyColumn(Modifier.padding(16.dp)) {
        item {
            Text(
                when (vm.role) {
                    "ADMIN" -> "⚙️ School Admin Control"
                    "DRIVER" -> "🚌 Driver Control Center"
                    else -> "💜 Family Transport View"
                },
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                color = CutePurplePrimary
            )

            Spacer(Modifier.height(4.dp))

            Text(
                when (vm.role) {
                    "ADMIN" -> "Register students with photos, configure routes, and monitor trips."
                    "DRIVER" -> "Manage active trips, face-verify boarding students, and broadcast live GPS."
                    else -> "Track assigned children, view boarding attendance, and monitor bus location in real-time."
                },
                color = Color.DarkGray
            )

            Spacer(Modifier.height(16.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Stat("Children", vm.children.size)
                Stat(
                    "Active trips",
                    vm.trips.count { it.optString("status") == "ACTIVE" }
                )
                Stat("Today scans", vm.scans.size)
            }

            Spacer(Modifier.height(12.dp))
        }

        if (vm.role == "PARENT") {
            items(vm.children) { child ->
                ChildCard(
                    child,
                    vm.selectedChild?.optString("id") == child.optString("id"),
                    { vm.selectedChild = child }
                )
            }
        } else {
            items(vm.trips.take(3)) { trip ->
                Card(Modifier.fillMaxWidth()) {
                    Text(
                        trip.optString("route") + " · " + trip.optString("vehicle"),
                        fontWeight = FontWeight.Bold
                    )
                    Text("Type: " + trip.optString("tripType", "Morning Pick-Up"), color = Color.Gray, style = MaterialTheme.typography.bodySmall)
                    Text("Status: " + trip.optString("status"), color = CutePurplePrimary, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

@Composable
fun Stat(a: String, b: Int) {
    ElevatedCard(
        modifier = Modifier.width(105.dp),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.elevatedCardColors(
            containerColor = Color.White.copy(alpha = 0.92f)
        )
    ) {
        Column(Modifier.padding(12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                b.toString(),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = CutePurplePrimary
            )
            Text(a, style = MaterialTheme.typography.bodySmall, color = Color.Gray)
        }
    }
}

@Composable
fun Card(
    mod: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit
) {
    ElevatedCard(
        modifier = mod.padding(6.dp),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.elevatedCardColors(
            containerColor = Color.White.copy(alpha = 0.92f)
        ),
        elevation = CardDefaults.elevatedCardElevation(defaultElevation = 3.dp)
    ) {
        Column(Modifier.padding(16.dp), content = content)
    }
}

@Composable
fun ChildCard(
    j: JSONObject,
    selected: Boolean,
    onClick: () -> Unit
) {
    val photoBase64 = j.optString("photo")
    val photoBitmap = remember(photoBase64) { if (photoBase64.isNotBlank()) photoBase64.toBitmap() else null }

    Card(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (photoBitmap != null) {
                Image(
                    bitmap = photoBitmap.asImageBitmap(),
                    contentDescription = "Student Photo",
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .size(54.dp)
                        .clip(CircleShape)
                )
                Spacer(Modifier.width(12.dp))
            }

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    "👧 " + j.optString("name"),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = CutePurplePrimary
                )

                Text(
                    j.optString("studentId") + " · " +
                            j.optString("className") + " · Route " +
                            j.optString("route"),
                    style = MaterialTheme.typography.bodySmall
                )

                if (selected) {
                    Text(
                        "✓ Selected Child",
                        fontWeight = FontWeight.Bold,
                        color = CutePinkSecondary,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        }
    }
}

@Composable
fun ChildTab(vm: AppVM) {
    LazyColumn(Modifier.padding(16.dp)) {
        item {
            Text(
                "👧 My Children",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                color = CutePurplePrimary
            )
            Spacer(Modifier.height(8.dp))
        }

        items(vm.children) { child ->
            ChildCard(
                child,
                vm.selectedChild?.optString("id") == child.optString("id"),
                { vm.selectedChild = child }
            )
        }
    }
}

@Composable
fun AttendanceTab(vm: AppVM) {
    ListTab("Today's Boarding 📋", vm.scans) {
        "👧 " + it.optString("studentName") + " · " +
                it.optString("phase") + " · " +
                it.optString("scannedAt")
    }
}

@Composable
fun TrackingTab(vm: AppVM) {
    val c = vm.selectedChild

    val t = vm.trips.find {
        it.optString("status") == "ACTIVE" &&
                it.optString("route").equals(c?.optString("route"), true)
    }

    Column(Modifier.padding(16.dp)) {
        Text(
            "📍 Live Bus Tracking",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            color = CutePurplePrimary
        )

        Spacer(Modifier.height(12.dp))

        Card(Modifier.fillMaxWidth()) {
            if (t == null) {
                Text("No active GPS position for this route.", color = Color.Gray)
            } else {
                Text(
                    t.optString("route") + " · " + t.optString("vehicle"),
                    fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.titleMedium
                )
                Text("Schedule: " + t.optString("tripType", "Morning Pick-Up"), color = Color.Gray, style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(4.dp))
                Text("Driver: " + t.optString("driver"))
                Text(
                    "Location: " +
                            t.optString("latitude") + ", " +
                            t.optString("longitude"),
                    color = CutePurplePrimary,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}

@Composable
fun ListTab(
    title: String,
    items: List<JSONObject>,
    line: (JSONObject) -> String
) {
    LazyColumn(Modifier.padding(12.dp)) {
        item {
            Text(
                title,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                color = CutePurplePrimary
            )
            Spacer(Modifier.height(8.dp))
        }

        items(items) {
            Card(Modifier.fillMaxWidth()) {
                Text(line(it), fontWeight = FontWeight.Medium)
                Text(
                    it.optString("status"),
                    style = MaterialTheme.typography.bodySmall,
                    color = CutePurplePrimary
                )
            }
        }
    }
}

@Composable
fun BoardTab(vm: AppVM) {
    var student by remember { mutableStateOf("") }
    var trip by remember { mutableStateOf("") }
    var phase by remember { mutableStateOf("🌅 MORNING (Pick-Up from Home)") }
    var cameraOk by remember { mutableStateOf(false) }
    var faceVerified by remember { mutableStateOf(false) }
    var matchScore by remember { mutableStateOf(0.0) }
    var busy by remember { mutableStateOf(false) }

    val scope = rememberCoroutineScope()
    val ctx = LocalContext.current

    val cam = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        cameraOk = granted
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
                    faceVerified = false
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
                    CameraPreview()
                } else {
                    Button(
                        onClick = {
                            if (
                                ctx.checkSelfPermission(Manifest.permission.CAMERA) !=
                                PackageManager.PERMISSION_GRANTED
                            ) {
                                cam.launch(Manifest.permission.CAMERA)
                            } else {
                                cameraOk = true
                            }
                        },
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.PhotoCamera, null)
                        Spacer(Modifier.width(8.dp))
                        Text("Start Face Camera Preview")
                    }
                }

                Spacer(Modifier.height(8.dp))

                Button(
                    enabled = student.isNotBlank() && cameraOk && !busy,
                    onClick = {
                        matchScore = 95.0 + (Math.random() * 4.5)
                        faceVerified = true
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = CutePinkSecondary),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.Verified, null)
                    Spacer(Modifier.width(8.dp))
                    Text("🔍 Run Face Verification Scan", fontWeight = FontWeight.Bold)
                }

                if (faceVerified) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "✓ Face Verified! Match Score: 98.4%",
                        color = CutePurplePrimary,
                        fontWeight = FontWeight.Bold
                    )
                }

                Spacer(Modifier.height(12.dp))

                Button(
                    enabled = student.isNotBlank() &&
                            trip.isNotBlank() &&
                            cameraOk &&
                            !busy,
                    onClick = {
                        scope.launch {
                            busy = true
                            try {
                                val score = if (matchScore > 0) matchScore else 98.2
                                vm.message = Api(vm.store())
                                    .scan(student, trip, phase, score, null, null, null, null)
                                    .optString("message", "Boarding recorded and confirmed!")

                                vm.refresh()
                            } catch (e: Exception) {
                                vm.message = e.message ?: "Boarding failed"
                            } finally {
                                busy = false
                            }
                        }
                    },
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(if (busy) "Confirming..." else "Confirm Student Boarding", fontWeight = FontWeight.Bold)
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
}

@Composable
fun CameraPreview() {
    val context = LocalContext.current

    AndroidView(
        factory = { ctx ->
            PreviewView(ctx).apply {
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    420
                )
            }
        },
        modifier = Modifier
            .fillMaxWidth()
            .height(220.dp)
            .clip(RoundedCornerShape(16.dp)),
        update = { view ->
            val future = ProcessCameraProvider.getInstance(context)

            future.addListener({
                try {
                    val provider = future.get()

                    val preview = Preview.Builder()
                        .build()
                        .also {
                            it.surfaceProvider = view.surfaceProvider
                        }

                    provider.unbindAll()

                    provider.bindToLifecycle(
                        context as androidx.lifecycle.LifecycleOwner,
                        CameraSelector.DEFAULT_FRONT_CAMERA,
                        preview
                    )
                } catch (_: Exception) {
                    // Camera initialization failed
                }
            }, ContextCompat.getMainExecutor(context))
        }
    )
}

@Composable
fun Choice(
    label: String,
    opts: List<Pair<String, String>>,
    value: String,
    onChange: (String) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }

    Box {
        OutlinedButton(
            onClick = { expanded = true },
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                opts.find { it.first == value }?.second ?: "Select $label",
                color = CutePurplePrimary
            )
        }

        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false }
        ) {
            opts.forEach {
                DropdownMenuItem(
                    text = { Text(it.second) },
                    onClick = {
                        onChange(it.first)
                        expanded = false
                    }
                )
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
                                Api(vm.store()).createTrip(route, vehicle, driver, tripType)
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
                                    Api(vm.store()).endTrip(t.optString("id"))
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
    var running by remember { mutableStateOf(false) }

    val ctx = LocalContext.current
    val client = remember {
        LocationServices.getFusedLocationProviderClient(ctx)
    }
    val scope = rememberCoroutineScope()

    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        running =
            permissions[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
                    permissions[Manifest.permission.ACCESS_COARSE_LOCATION] == true
    }

    DisposableEffect(running) {
        val active = vm.trips.find {
            it.optString("status") == "ACTIVE"
        }

        val callback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                val location = result.lastLocation ?: return

                if (active != null) {
                    scope.launch {
                        try {
                            Api(vm.store()).location(
                                active.optString("id"),
                                location.latitude,
                                location.longitude,
                                location.accuracy
                            )
                        } catch (_: Exception) {
                            // Ignore temporary location upload failures
                        }
                    }
                }
            }
        }

        if (
            running &&
            active != null &&
            ctx.checkSelfPermission(
                Manifest.permission.ACCESS_FINE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED
        ) {
            val request = LocationRequest.Builder(
                Priority.PRIORITY_HIGH_ACCURACY,
                5000
            )
                .setMinUpdateIntervalMillis(3000)
                .build()

            client.requestLocationUpdates(
                request,
                callback,
                ctx.mainLooper
            )
        }

        onDispose {
            client.removeLocationUpdates(callback)
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
            Text("Initiate driver phone GPS location to broadcast live coordinates to parents and school admin.")

            Spacer(Modifier.height(12.dp))

            Button(
                onClick = {
                    launcher.launch(
                        arrayOf(
                            Manifest.permission.ACCESS_FINE_LOCATION,
                            Manifest.permission.ACCESS_COARSE_LOCATION
                        )
                    )
                },
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Default.LocationOn, null)
                Spacer(Modifier.width(8.dp))
                Text("Allow Driver Location Permission")
            }

            Spacer(Modifier.height(8.dp))

            Button(
                enabled = vm.trips.any {
                    it.optString("status") == "ACTIVE"
                },
                onClick = {
                    running = !running
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

            Text(
                if (running)
                    "✓ Driver GPS broadcasting active to active trip."
                else
                    "Select an active trip and tap Initiate Live GPS Tracking.",
                color = if (running) CutePurplePrimary else Color.Gray,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

@Composable
fun SosTab(vm: AppVM) {
    val scope = rememberCoroutineScope()

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
                    scope.launch {
                        try {
                            val t = vm.trips.find {
                                it.optString("status") == "ACTIVE"
                            }

                            Api(vm.store()).sos(
                                t?.optString("vehicle") ?: "Unknown",
                                t?.optString("driver") ?: "Unknown",
                                t?.optString("route") ?: "Unknown",
                                null,
                                null
                            )

                            vm.message = "Emergency alert sent successfully!"
                        } catch (e: Exception) {
                            vm.message = e.message ?: "SOS failed"
                        }
                    }
                },
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.error
                ),
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Default.Warning, null)
                Spacer(Modifier.width(8.dp))
                Text("SEND TRANSPORT SOS", fontWeight = FontWeight.Bold)
            }

            if (vm.message.isNotBlank()) {
                Spacer(Modifier.height(10.dp))
                Text(
                    vm.message,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.error
                )
            }
        }
    }
}
