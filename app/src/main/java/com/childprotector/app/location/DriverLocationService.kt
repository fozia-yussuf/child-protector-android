package com.childprotector.app.location

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.location.Location
import android.os.Build
import android.os.IBinder
import androidx.core.content.ContextCompat
import com.childprotector.app.Api
import com.childprotector.app.SessionStore
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class DriverLocationService : Service() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val latestLocations = Channel<Location>(Channel.CONFLATED)
    private var locationJob: Job? = null
    private var tripId: String? = null
    private val locationClient by lazy { LocationServices.getFusedLocationProviderClient(this) }
    private val callback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            result.lastLocation?.let(latestLocations::trySend)
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        tripId = intent?.getStringExtra(EXTRA_TRIP_ID)
        if (tripId.isNullOrBlank()) {
            stopSelf()
            return START_NOT_STICKY
        }

        getSharedPreferences(STATUS_PREFS, MODE_PRIVATE).edit()
            .putBoolean(KEY_RUNNING, true)
            .putString(KEY_ERROR, "")
            .apply()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                buildNotification("Waiting for GPS location…"),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
            )
        } else {
            startForeground(NOTIFICATION_ID, buildNotification("Waiting for GPS location…"))
        }

        if (
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) !=
            PackageManager.PERMISSION_GRANTED &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            updateStatus(error = "Location permission is required.")
            stopSelf()
            return START_NOT_STICKY
        }

        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 5_000)
            .setMinUpdateIntervalMillis(3_000)
            .build()
        try {
            locationClient.removeLocationUpdates(callback)
            locationClient.requestLocationUpdates(request, callback, mainLooper)
                .addOnFailureListener { exception ->
                    updateStatus(error = "Could not start GPS: ${exception.message ?: "unknown error"}")
                }
        } catch (exception: SecurityException) {
            updateStatus(error = "Location permission is required.")
            stopSelf()
            return START_NOT_STICKY
        }
        startUploadWorker()
        return START_STICKY
    }

    private fun startUploadWorker() {
        if (locationJob?.isActive == true) return
        locationJob = serviceScope.launch {
            val api = Api(SessionStore(this@DriverLocationService))
            for (initial in latestLocations) {
                var location = initial
                var retryDelay = 2_000L
                while (isActive) {
                    try {
                        api.location(
                            requireNotNull(tripId),
                            location.latitude,
                            location.longitude,
                            location.accuracy
                        )
                        val uploadedAt = System.currentTimeMillis()
                        updateStatus(uploadedAt = uploadedAt, error = "")
                        updateNotification("Last GPS upload ${formatTime(uploadedAt)}")
                        break
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (exception: Exception) {
                        if (exception.message == "Session expired") {
                            updateStatus(error = "Session expired. Sign in again to resume GPS.")
                            stopSelf()
                            return@launch
                        }
                        val error = "GPS upload failed; retrying: ${exception.message ?: "network error"}"
                        updateStatus(error = error)
                        updateNotification("GPS upload failed; retrying")
                        delay(retryDelay)
                        retryDelay = (retryDelay * 2).coerceAtMost(30_000L)
                        location = latestLocations.tryReceive().getOrNull() ?: location
                    }
                }
            }
        }
    }

    private fun updateStatus(uploadedAt: Long? = null, error: String? = null) {
        getSharedPreferences(STATUS_PREFS, MODE_PRIVATE).edit().apply {
            uploadedAt?.let { putLong(KEY_LAST_UPLOAD, it) }
            error?.let { putString(KEY_ERROR, it) }
        }.apply()
    }

    private fun buildNotification(message: String): Notification =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
                .setContentTitle("Child Protector · Live GPS")
                .setContentText(message)
                .setSmallIcon(android.R.drawable.ic_menu_mylocation)
                .setOngoing(true)
                .build()
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
                .setContentTitle("Child Protector · Live GPS")
                .setContentText(message)
                .setSmallIcon(android.R.drawable.ic_menu_mylocation)
                .setOngoing(true)
                .build()
        }

    private fun updateNotification(message: String) {
        getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID, buildNotification(message))
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Driver GPS", NotificationManager.IMPORTANCE_LOW)
            )
        }
    }

    override fun onDestroy() {
        locationClient.removeLocationUpdates(callback)
        locationJob?.cancel()
        latestLocations.close()
        serviceScope.coroutineContext[Job]?.cancel()
        getSharedPreferences(STATUS_PREFS, MODE_PRIVATE).edit()
            .putBoolean(KEY_RUNNING, false)
            .apply()
        getSystemService(NotificationManager::class.java).cancel(NOTIFICATION_ID)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun formatTime(time: Long) =
        SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(time))

    companion object {
        const val ACTION_START = "com.childprotector.app.location.START"
        const val ACTION_STOP = "com.childprotector.app.location.STOP"
        const val EXTRA_TRIP_ID = "trip_id"
        const val STATUS_PREFS = "driver_gps_status"
        const val KEY_RUNNING = "running"
        const val KEY_LAST_UPLOAD = "last_upload"
        const val KEY_ERROR = "error"
        private const val CHANNEL_ID = "driver_gps"
        private const val NOTIFICATION_ID = 2401
    }
}
