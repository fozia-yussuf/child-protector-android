package com.childprotector.app.ui.admin

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

import com.childprotector.app.*
import com.childprotector.app.ui.common.*

@Composable
fun AdminScreen(vm: AppVM, page: String) {
    when (page) {
        "Overview" -> AdminOverviewScreen(vm)
        "Students" -> AdminStudentsScreen(vm)
        "Trips" -> AdminTripsScreen(vm)
        "Live GPS" -> AdminGpsScreen(vm)
        "Attendance" -> AdminAttendanceScreen(vm)
        "Emergencies" -> AdminEmergenciesScreen(vm)
        "Parent Access" -> AdminParentAccessScreen(vm)
    }
}

@Composable
private fun AdminOverviewScreen(vm: AppVM) {
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()

    fun reload() {
        scope.launch {
            loading = true
            error = ""
            try {
                vm.refresh()
                if (vm.message.isNotBlank()) error = vm.message
            } catch (exception: Exception) {
                error = exception.message ?: "Could not load overview."
            } finally {
                loading = false
            }
        }
    }

    LaunchedEffect(Unit) { reload() }
    val counts = vm.adminOverview
    val countData = counts?.optJSONObject("counts") ?: counts
    val openEmergencies = vm.emergencies.count {
        it.optString("status").uppercase() !in setOf("RESOLVED", "CLOSED")
    }
    val countValues = listOf(
        ("Students" to listOf("students", "studentCount", "children", "childrenCount")),
        ("Active trips" to listOf("activeTrips", "activeTripCount")),
        ("Today's scans" to listOf("todayScans", "todaysScans", "scansToday", "scanCount")),
        ("Open emergencies" to listOf("openEmergencies", "openEmergencyCount"))
    )
    val fallbackValues = listOf(
        vm.children.size,
        vm.trips.count { it.optString("status") == "ACTIVE" },
        vm.scans.size,
        openEmergencies
    )
    LazyColumn(Modifier.fillMaxSize().padding(16.dp)) {
        item {
            Text(
                "⚙️ School Admin Overview",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                color = CutePurplePrimary
            )
            AdminListState(loading, error, false, "", ::reload)
            countValues.forEachIndexed { index, (label, keys) ->
                val count = keys.firstNotNullOfOrNull { key ->
                    countData?.takeIf { it.has(key) }?.optInt(key)
                } ?: fallbackValues[index]
                Stat(label, count)
            }
        }
    }
}

@Composable
private fun AdminStudentsScreen(vm: AppVM) {
    var query by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    var editing by remember { mutableStateOf<JSONObject?>(null) }
    var creating by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<JSONObject?>(null) }
    val scope = rememberCoroutineScope()

    fun reload() {
        scope.launch {
            loading = true
            error = ""
            try {
                vm.refresh()
                if (vm.message.isNotBlank()) error = vm.message
            } catch (exception: Exception) {
                error = exception.message ?: "Could not load students."
            } finally {
                loading = false
            }
        }
    }

    LaunchedEffect(Unit) { reload() }
    val filtered = vm.children.filter { child ->
        listOf("name", "studentId", "className", "route").any { key ->
            child.optString(key).contains(query, ignoreCase = true)
        }
    }
    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Students", style = MaterialTheme.typography.headlineSmall, color = CutePurplePrimary)
            Button(onClick = { creating = true }) { Text("Register") }
        }
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            label = { Text("Search name, ID, class, route") },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)
        )
        if (loading || error.isNotBlank() || filtered.isEmpty()) {
            AdminListState(loading, error, filtered.isEmpty(), "No students found.", ::reload)
        }
        LazyColumn(Modifier.weight(1f).padding(12.dp)) {
            items(filtered) { child ->
                Card(Modifier.fillMaxWidth()) {
                    Text(child.optString("name"), fontWeight = FontWeight.Bold)
                    Text("${child.optString("studentId")} · ${child.optString("className")} · ${child.optString("route")}")
                    Row {
                        TextButton(onClick = { editing = child }) { Text("Edit") }
                        TextButton(onClick = { deleting = child }) { Text("Delete") }
                    }
                }
            }
        }
    }
    if (creating || editing != null) {
        StudentEditorDialog(
            vm,
            editing,
            onDismiss = { creating = false; editing = null },
            onSaved = { creating = false; editing = null; reload() },
            onError = { error = it }
        )
    }
    deleting?.let { child ->
        ConfirmActionDialog(
            "Delete student?",
            "Delete ${child.optString("name")} and its student record? This cannot be undone.",
            "Delete",
            onDismiss = { deleting = null }
        ) {
            scope.launch {
                try {
                    vm.apiClient().deleteChild(child.optString("id"))
                    deleting = null
                    reload()
                } catch (exception: Exception) {
                    deleting = null
                    error = exception.message ?: "Could not delete student."
                }
            }
        }
    }
}

@Composable
private fun StudentEditorDialog(
    vm: AppVM,
    child: JSONObject?,
    onDismiss: () -> Unit,
    onSaved: () -> Unit,
    onError: (String) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var name by remember(child) { mutableStateOf(child?.optString("name").orEmpty()) }
    var studentId by remember(child) { mutableStateOf(child?.optString("studentId").orEmpty()) }
    var className by remember(child) { mutableStateOf(child?.optString("className").orEmpty()) }
    var route by remember(child) { mutableStateOf(child?.optString("route").orEmpty()) }
    var photo by remember(child) { mutableStateOf<Bitmap?>(null) }
    var consent by remember(child) { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var loadingExisting by remember(child) { mutableStateOf(child != null) }
    var error by remember { mutableStateOf("") }
    LaunchedEffect(child?.optString("id")) {
        if (child != null) {
            loadingExisting = true
            try {
                val latest = vm.apiClient().child(child.optString("id"))
                name = latest.optString("name", name)
                studentId = latest.optString("studentId", studentId)
                className = latest.optString("className", className)
                route = latest.optString("route", route)
            } catch (exception: Exception) {
                error = exception.message ?: "Could not load student details."
            } finally {
                loadingExisting = false
            }
        }
    }
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.TakePicturePreview()
    ) { bitmap ->
        photo = bitmap
        if (bitmap == null) error = "Photo capture failed or was cancelled."
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (child == null) "Register student" else "Edit student") },
        text = {
            Column {
                OutlinedTextField(name, { name = it }, label = { Text("Name") })
                OutlinedTextField(studentId, { studentId = it }, label = { Text("Student ID") }, enabled = child == null)
                OutlinedTextField(className, { className = it }, label = { Text("Class") })
                OutlinedTextField(route, { route = it }, label = { Text("Route") })
                if (loadingExisting) CircularProgressIndicator()
                TextButton(onClick = { launcher.launch() }) {
                    Text(if (photo == null) "Take or replace photo" else "Retake photo")
                }
                photo?.let { Image(it.asImageBitmap(), "Student photo", Modifier.size(72.dp)) }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(consent, { consent = it })
                    Text("Parent consent obtained")
                }
                if (error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error)
            }
        },
        confirmButton = {
            TextButton(
                enabled = !busy && !loadingExisting && name.isNotBlank() && studentId.isNotBlank() &&
                        (child != null || photo != null && consent) &&
                        (photo == null || consent),
                onClick = {
                    scope.launch {
                        busy = true
                        try {
                            val encoded = photo?.let { validateAndEncodeStudentPhoto(context, it) }
                            if (child == null) {
                                vm.apiClient().createChild(
                                    name, studentId, className, route,
                                    encoded ?: error("A validated student photo is required."),
                                    null,
                                    consent
                                )
                            } else {
                                vm.apiClient().updateChild(
                                    child.optString("id"), name, className, route, encoded, consent
                                )
                            }
                            onSaved()
                        } catch (exception: Exception) {
                            error = exception.message ?: "Could not save student."
                            onError(error)
                        } finally {
                            busy = false
                        }
                    }
                }
            ) { Text(if (busy) "Saving..." else "Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}
