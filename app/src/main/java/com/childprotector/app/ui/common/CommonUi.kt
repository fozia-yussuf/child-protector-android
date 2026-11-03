package com.childprotector.app.ui.common

import android.content.Intent
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
import androidx.compose.ui.platform.LocalContext
import android.net.Uri
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

val CutePurplePrimary = Color(0xFF7C3AED)
val CutePinkSecondary = Color(0xFFEC4899)
val CutePurpleContainer = Color(0xFFF3E8FF)
val CutePinkContainer = Color(0xFFFCE7F3)

fun String.toBitmap(): Bitmap? {
    return try {
        val encoded = substringAfter("base64,", this)
        val decodedBytes = Base64.decode(encoded, Base64.DEFAULT)
        BitmapFactory.decodeByteArray(decodedBytes, 0, decodedBytes.size)
    } catch (_: Exception) {
        null
    }
}

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
fun HomeTab(vm: AppVM) {
    LazyColumn(Modifier.padding(16.dp)) {
        item {
            Text(
                when (vm.role) {
                    "ADMIN" -> "School Admin Control"
                    "DRIVER" -> "Driver Control Center"
                    else -> "Family Transport View"
                },
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                color = CutePurplePrimary
            )
            Spacer(Modifier.height(8.dp))
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Stat("Children", vm.children.size)
                Stat("Active trips", vm.trips.count { it.optString("status") == "ACTIVE" })
                Stat("Today scans", vm.scans.size)
            }
        }
        if (vm.role == "PARENT") {
            items(vm.children) { child ->
                ChildCard(
                    child,
                    vm.selectedChild?.optString("id") == child.optString("id")
                ) { vm.selectedChild = child }
            }
        } else {
            items(vm.trips.take(3)) { trip ->
                Card(Modifier.fillMaxWidth()) {
                    Text(
                        "${trip.optString("route")} · ${trip.optString("vehicle")}",
                        fontWeight = FontWeight.Bold
                    )
                    Text("Status: ${trip.optString("status")}", color = CutePurplePrimary)
                }
            }
        }
    }
}

@Composable
fun AdminListState(
    loading: Boolean,
    error: String,
    isEmpty: Boolean,
    emptyMessage: String,
    onRetry: () -> Unit
) {
    when {
        loading -> CircularProgressIndicator(Modifier.padding(16.dp))
        error.isNotBlank() -> Column(Modifier.padding(16.dp)) {
            Text(error, color = MaterialTheme.colorScheme.error)
            TextButton(onClick = onRetry) { Text("Retry") }
        }
        isEmpty -> Text(emptyMessage, Modifier.padding(16.dp), color = Color.Gray)
    }
}

@Composable
fun OpenMapButton(latitude: Double?, longitude: Double?, label: String) {
    val context = LocalContext.current
    if (latitude != null && longitude != null) {
        TextButton(
            onClick = {
                val uri = Uri.parse("geo:$latitude,$longitude?q=$latitude,$longitude(${Uri.encode(label)})")
                context.startActivity(Intent(Intent.ACTION_VIEW, uri))
            }
        ) {
            Icon(Icons.Default.Map, contentDescription = null)
            Text("View on map")
        }
    } else {
        Text("No location available", color = Color.Gray)
    }
}

@Composable
fun ConfirmActionDialog(
    title: String,
    message: String,
    confirmLabel: String,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(message) },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text(confirmLabel) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}
