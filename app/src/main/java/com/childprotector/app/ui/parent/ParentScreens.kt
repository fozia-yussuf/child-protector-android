package com.childprotector.app.ui.parent

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
fun ParentTabs(vm: AppVM, tab: Int) {
    val scope = rememberCoroutineScope()
    Column(Modifier.fillMaxSize()) {
        if (vm.message.isNotBlank()) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(vm.message, Modifier.weight(1f), color = MaterialTheme.colorScheme.error)
                TextButton(onClick = { scope.launch { vm.refresh() } }) { Text("Retry") }
            }
        }
        Box(Modifier.weight(1f)) {
            when (tab) {
                0 -> HomeTab(vm)
                1 -> ChildTab(vm)
                2 -> AttendanceTab(vm)
                3 -> TrackingTab(vm)
                4 -> ParentAlertsTab(vm)
            }
        }
    }
}

@Composable
fun ChildTab(vm: AppVM) {
    var query by remember { mutableStateOf("") }
    var studentId by remember { mutableStateOf("") }
    var requestMessage by remember { mutableStateOf("") }
    var submittingRequest by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val filteredChildren = vm.children.filter { child ->
        listOf("name", "studentId", "className", "route").any { field ->
            child.optString(field).contains(query, ignoreCase = true)
        }
    }
    LazyColumn(Modifier.fillMaxSize().padding(16.dp)) {
        item {
            Text(
                "👧 My Children",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                color = CutePurplePrimary
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                label = { Text("Search child name, ID, class, or route") },
                modifier = Modifier.fillMaxWidth()
            )
            if (vm.children.isEmpty()) {
                Text(
                    "No children are linked yet. Enter the school student ID to request access. A school administrator must approve it before child information is shown.",
                    Modifier.padding(vertical = 12.dp),
                    color = Color.Gray
                )
            } else {
                Text(
                    "To add another child, request access with the school student ID. A school administrator must approve it.",
                    Modifier.padding(vertical = 8.dp),
                    color = Color.Gray
                )
                if (filteredChildren.isEmpty()) {
                    Text("No linked children match your search.", Modifier.padding(vertical = 12.dp))
                }
            }
            OutlinedTextField(
                value = studentId,
                onValueChange = { studentId = it },
                label = { Text("School student ID") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Button(
                enabled = !submittingRequest && studentId.isNotBlank(),
                onClick = {
                    scope.launch {
                        submittingRequest = true
                        requestMessage = ""
                        try {
                            vm.apiClient().createParentLinkRequest(studentId.trim())
                            studentId = ""
                            requestMessage = "Request sent. The school administrator must approve it."
                            vm.refresh()
                        } catch (exception: Exception) {
                            requestMessage = exception.message ?: "Could not submit link request."
                        } finally {
                            submittingRequest = false
                        }
                    }
                }
            ) {
                Text(if (submittingRequest) "Sending request..." else "Request child access")
            }
            if (requestMessage.isNotBlank()) {
                Text(
                    requestMessage,
                    color = if (requestMessage.startsWith("Request sent")) CutePurplePrimary
                    else MaterialTheme.colorScheme.error
                )
            }
            if (vm.parentLinkRequests.isNotEmpty()) {
                Text("Access requests", style = MaterialTheme.typography.titleMedium)
            }
        }

        items(vm.parentLinkRequests) { linkRequest ->
            Card(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                Text("Student ID: ${linkRequest.optString("studentId")}")
                Text("Status: ${linkRequest.optString("status")}")
            }
        }

        items(filteredChildren) { child ->
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
    val activities = (vm.scans.map { scan ->
        val morning = scan.optString("phase").contains("MORNING", ignoreCase = true)
        Triple(
            scan.optString("scannedAt"),
            scan.optString("studentName"),
            if (morning) "Left home · boarded for school" else "Left school · boarded for home"
        )
    } + vm.arrivals.map { arrival ->
        Triple(
            arrival.optString("arrivedAt"),
            arrival.optString("studentName"),
            "Arrived at ${arrival.optString("destination").lowercase().replaceFirstChar(Char::uppercase)}"
        )
    }).sortedByDescending { it.first }

    LazyColumn(Modifier.fillMaxSize().padding(16.dp)) {
        item {
            Text(
                "Child Journey Updates",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                color = CutePurplePrimary
            )
            Text(
                "Live in-app updates appear when a driver records boarding or confirms arrival.",
                color = Color.Gray,
                style = MaterialTheme.typography.bodySmall
            )
            if (activities.isEmpty()) {
                Text("No journey updates yet today.", Modifier.padding(vertical = 12.dp))
            }
        }
        items(activities) { (time, studentName, description) ->
            Card(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                Text(studentName, fontWeight = FontWeight.Bold)
                Text(description)
                Text(time, style = MaterialTheme.typography.bodySmall, color = Color.Gray)
            }
        }
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
                Text(
                    if (c == null) "Select a linked child to see their route."
                    else "No active trip for this child's route. Live GPS appears when the driver starts the trip and GPS sharing.",
                    color = Color.Gray
                )
            } else {
                val latitude = t.optDouble("latitude", Double.NaN)
                val longitude = t.optDouble("longitude", Double.NaN)
                Text(
                    t.optString("route") + " · " + t.optString("vehicle"),
                    fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.titleMedium
                )
                Text("Schedule: " + t.optString("tripType", "Morning Pick-Up"), color = Color.Gray, style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(4.dp))
                Text("Driver: " + t.optString("driver"))
                if (latitude.isFinite() && longitude.isFinite()) {
                    Text(
                        "Location: $latitude, $longitude",
                        color = CutePurplePrimary,
                        fontWeight = FontWeight.Bold
                    )
                } else {
                    Text("Waiting for the driver’s first GPS fix.", color = Color.Gray)
                }
                LastUpdated(
                    t.optString(
                        "locationUpdatedAt",
                        t.optString("gpsUpdatedAt", t.optString("lastUpdated", t.optString("timestamp")))
                    ).takeIf { it.isNotBlank() && it != "null" }
                )
                if (latitude.isFinite() && longitude.isFinite()) {
                    Spacer(Modifier.height(8.dp))
                    EmbeddedMap(latitude, longitude, Modifier.fillMaxWidth().height(220.dp))
                }
            }
        }
    }
}

@Composable
private fun ParentAlertsTab(vm: AppVM) {
    LazyColumn(Modifier.fillMaxSize().padding(16.dp)) {
        item {
            Text(
                "Route Alerts",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                color = CutePurplePrimary
            )
            if (vm.emergencies.isEmpty()) {
                Text("No open route alerts.", Modifier.padding(vertical = 12.dp))
            }
        }
        items(vm.emergencies) { emergency ->
            Card(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                Text(
                    "${emergency.optString("route")} · ${emergency.optString("vehicle")}",
                    fontWeight = FontWeight.Bold
                )
                Text(emergency.optString("description"))
                Text(
                    "${emergency.optString("status")} · ${emergency.optString("createdAt")}",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.Gray
                )
            }
        }
    }
}
