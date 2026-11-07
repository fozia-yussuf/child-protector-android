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
import androidx.compose.runtime.saveable.rememberSaveable
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import kotlinx.coroutines.CancellationException
import java.io.File
import java.util.UUID

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
    var editingId by rememberSaveable { mutableStateOf<String?>(null) }
    var creating by rememberSaveable { mutableStateOf(false) }
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
    val editing = editingId?.let { id -> vm.children.find { it.optString("id") == id } }
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
                        TextButton(onClick = { editingId = child.optString("id") }) { Text("Edit") }
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
            onDismiss = { creating = false; editingId = null },
            onSaved = { creating = false; editingId = null; reload() },
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
    val childId = child?.optString("id")
    var name by rememberSaveable(childId) { mutableStateOf(child?.optString("name").orEmpty()) }
    var studentId by rememberSaveable(childId) { mutableStateOf(child?.optString("studentId").orEmpty()) }
    var className by rememberSaveable(childId) { mutableStateOf(child?.optString("className").orEmpty()) }
    var route by rememberSaveable(childId) { mutableStateOf(child?.optString("route").orEmpty()) }
    var photoPath by rememberSaveable(childId) { mutableStateOf<String?>(null) }
    val photo = remember(photoPath) { photoPath?.let(BitmapFactory::decodeFile) }
    var parents by remember(childId) { mutableStateOf(emptyList<JSONObject>()) }
    var selectedParentId by rememberSaveable(childId) { mutableStateOf("") }
    var consent by rememberSaveable(childId) { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var capturingPhoto by remember { mutableStateOf(false) }
    var loadingExisting by remember(child) { mutableStateOf(child != null) }
    var error by rememberSaveable(childId) { mutableStateOf("") }
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
        } else {
            try {
                parents = vm.apiClient().adminParents()
            } catch (exception: Exception) {
                error = "Parent accounts could not be loaded. You can link one later in Parent Access. " +
                        (exception.message ?: "Please try again.")
            }
        }
    }
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.TakePicturePreview()
    ) { bitmap ->
        if (bitmap == null) {
            capturingPhoto = false
            error = "Photo capture failed or was cancelled."
        } else {
            scope.launch {
                try {
                    val capturedPath = withContext(Dispatchers.IO) {
                        persistStudentPhotoCapture(context, bitmap)
                    }
                    photoPath?.let { File(it).delete() }
                    photoPath = capturedPath
                    error = ""
                } catch (exception: Exception) {
                    error = exception.message ?: "Could not save captured photo."
                } finally {
                    capturingPhoto = false
                }
            }
        }
    }
    fun dismissEditor() {
        photoPath?.let { File(it).delete() }
        onDismiss()
    }
    AlertDialog(
        onDismissRequest = ::dismissEditor,
        title = { Text(if (child == null) "Register student" else "Edit student") },
        text = {
            Column {
                OutlinedTextField(name, { name = it }, label = { Text("Name") })
                OutlinedTextField(studentId, { studentId = it }, label = { Text("Student ID") }, enabled = child == null)
                OutlinedTextField(className, { className = it }, label = { Text("Class") })
                OutlinedTextField(route, { route = it }, label = { Text("Route") })
                if (child == null) {
                    if (parents.isNotEmpty()) {
                        Choice(
                            "Parent account (optional)",
                            parents.map { it.optString("id") to it.optString("username") },
                            selectedParentId
                        ) { selectedParentId = it }
                        Text(
                            "Linking a parent lets them see this child, attendance, route GPS, and arrival updates.",
                            style = MaterialTheme.typography.bodySmall,
                            color = Color.Gray
                        )
                    } else if (error.isBlank()) {
                        Text(
                            "No parent accounts found. Create the parent account first, then link it here or in Parent Access.",
                            style = MaterialTheme.typography.bodySmall,
                            color = Color.Gray
                        )
                    }
                }
                if (loadingExisting) CircularProgressIndicator()
                TextButton(
                    enabled = !busy && !capturingPhoto,
                    onClick = {
                        capturingPhoto = true
                        error = ""
                        try {
                            launcher.launch()
                        } catch (exception: Exception) {
                            capturingPhoto = false
                            error = exception.message ?: "No camera app is available."
                        }
                    }
                ) {
                    Text(if (photo == null) "Take or replace photo" else "Retake photo")
                }
                photo?.let { Image(it.asImageBitmap(), "Student photo", Modifier.size(72.dp)) }
                if (capturingPhoto) CircularProgressIndicator()
                if (photoPath != null && photo == null) {
                    Text("Captured photo could not be restored. Please retake it.", color = MaterialTheme.colorScheme.error)
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(consent, { consent = it })
                    Text("Parent consent obtained")
                }
                if (error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error)
            }
        },
        confirmButton = {
            TextButton(
                enabled = !busy && !capturingPhoto && !loadingExisting && name.isNotBlank() && studentId.isNotBlank() &&
                        (photoPath == null || photo != null) &&
                        (child != null || photo != null && consent) &&
                        (photo == null || consent),
                onClick = {
                    scope.launch {
                        busy = true
                        try {
                            val encoded = photo?.let { bitmap ->
                                withContext(Dispatchers.Default) {
                                    validateAndEncodeStudentPhoto(context, bitmap)
                                }
                            }
                            if (child == null) {
                                vm.apiClient().createChild(
                                    name, studentId, className, route,
                                    encoded ?: error("A validated student photo is required."),
                                    parents.find { it.optString("id") == selectedParentId }
                                        ?.optString("username"),
                                    consent
                                )
                            } else {
                                vm.apiClient().updateChild(
                                    child.optString("id"), name, className, route, encoded, consent
                                )
                            }
                            photoPath?.let { File(it).delete() }
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
        dismissButton = { TextButton(onClick = ::dismissEditor) { Text("Cancel") } }
    )
}

private fun persistStudentPhotoCapture(context: android.content.Context, bitmap: Bitmap): String {
    val directory = File(context.filesDir, "student-photo-captures")
    check(directory.isDirectory || directory.mkdirs()) { "Could not prepare photo storage." }
    val file = File(directory, "${UUID.randomUUID()}.jpg")
    try {
        file.outputStream().use { output ->
            check(bitmap.compress(Bitmap.CompressFormat.JPEG, 90, output)) {
                "Could not save captured photo."
            }
        }
        return file.absolutePath
    } catch (exception: Exception) {
        file.delete()
        throw exception
    }
}
