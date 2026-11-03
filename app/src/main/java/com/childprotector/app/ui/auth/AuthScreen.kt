package com.childprotector.app.ui.auth

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
fun AuthScreen(vm: AppVM) {
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

                    if (!signup) {
                        FilterChip(
                            selected = role == "ADMIN",
                            onClick = { role = "ADMIN" },
                            label = { Text("⚙️ Admin") },
                            shape = RoundedCornerShape(14.dp)
                        )
                    }
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
                                    vm.apiSignup(username, password, role)
                                    signup = false
                                    vm.message = "Account created. Sign in."
                                } else {
                                    vm.apiLogin(username, password, role)
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
                    onClick = {
                        signup = !signup
                        if (signup && role == "ADMIN") role = "PARENT"
                    },
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
    u: String,
    p: String,
    r: String
) {
    apiClient().login(u, p, r)
    role = store().role
    refresh()
}

suspend fun AppVM.apiSignup(
    u: String,
    p: String,
    r: String
) {
    apiClient().signup(u, p, r)
}
