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
import com.childprotector.app.ui.driver.SosTab

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
                Text(
                    "Location: " +
                            t.optString("latitude") + ", " +
                            t.optString("longitude"),
                    color = CutePurplePrimary,
                    fontWeight = FontWeight.Bold
                )
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
