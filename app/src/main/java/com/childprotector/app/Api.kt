package com.childprotector.app

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject

class SessionStore internal constructor(
    context: Context,
    testPreferences: SharedPreferences? = null
) {
    private val preferences = testPreferences ?: EncryptedSharedPreferences.create(
        context,
        "child_protector_secure",
        MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )

    init {
        val legacy = context.getSharedPreferences("child_protector", Context.MODE_PRIVATE)
        if (preferences.getString("session", null) == null) {
            preferences.edit().apply {
                legacy.getString("username", null)?.let { putString("username", it) }
                legacy.getString("role", null)?.let { putString("role", it) }
                legacy.getString("session", null)?.let { putString("session", it) }
            }.apply()
        }
        legacy.edit().clear().apply()
    }

    var username: String?
        get() = preferences.getString("username", null)
        set(value) = preferences.edit().putString("username", value).apply()

    var role: String?
        get() = preferences.getString("role", null)
        set(value) = preferences.edit().putString("role", value).apply()

    var session: String?
        get() = preferences.getString("session", null)
        set(value) = preferences.edit().putString("session", value).apply()

    fun clear() = preferences.edit().clear().apply()
}

class Api internal constructor(
    private val store: SessionStore,
    apiBaseUrl: String = BuildConfig.API_BASE_URL,
    clientConfigurer: ((OkHttpClient.Builder) -> Unit)? = null
) {
    @Volatile
    private var unauthorizedHandler: (() -> Unit)? = null

    private val client = OkHttpClient.Builder().apply {
        clientConfigurer?.invoke(this)
    }.addInterceptor(authorizationInterceptor()).build()
    private val jsonType = "application/json; charset=utf-8".toMediaType()
    private val baseUrl = apiBaseUrl.trimEnd('/').also {
        require(it.startsWith("https://")) { "API_BASE_URL must use HTTPS" }
    }

    fun setUnauthorizedHandler(handler: () -> Unit) {
        unauthorizedHandler = handler
    }

    fun openWebSocket(listener: WebSocketListener): WebSocket {
        val token = requireNotNull(store.session) { "Session expired" }
        val socketUrl = "$baseUrl/ws".replaceFirst("https://", "wss://")
        val request = Request.Builder()
            .url(socketUrl)
            .header("Authorization", "Bearer $token")
            .build()
        return client.newWebSocket(request, listener)
    }

    private fun authorizationInterceptor() = Interceptor { chain ->
        val original = chain.request()
        val token = store.session
        val authenticatedRequest = original.newBuilder().apply {
            if (
                !token.isNullOrBlank() &&
                original.url.encodedPath !in AUTH_PATHS
            ) {
                header("Authorization", "Bearer $token")
            }
        }.build()
        val response = chain.proceed(authenticatedRequest)
        if (response.code == 401 && !token.isNullOrBlank() && authenticatedRequest.header("Authorization") != null) {
            store.clear()
            unauthorizedHandler?.invoke()
        }
        response
    }

    private suspend fun request(
        method: String,
        path: String,
        body: JSONObject? = null
    ): JSONObject = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url("$baseUrl$path")
            .apply {
                if (body != null) {
                    method(method, body.toString().toRequestBody(jsonType))
                } else {
                    method(method, null)
                }
            }
            .build()

        client.newCall(request).execute().use { response ->
            val raw = response.body?.string().orEmpty()
            val json = try {
                if (raw.isBlank()) JSONObject() else JSONObject(raw)
            } catch (exception: Exception) {
                if (!response.isSuccessful) {
                    if (
                        response.code == 401 &&
                        response.request.url.encodedPath !in AUTH_PATHS
                    ) {
                        throw IllegalStateException("Session expired")
                    }
                    throw IllegalStateException("Server error (${response.code}): ${response.message}")
                }
                throw IllegalStateException("Invalid response format from server")
            }
            if (!response.isSuccessful) {
                val message = if (
                    response.code == 401 &&
                    response.request.url.encodedPath !in AUTH_PATHS
                ) {
                    "Session expired"
                } else {
                    json.optString(
                        "message",
                        json.optString("error", "Request failed (${response.code})")
                    )
                }
                throw IllegalStateException(message)
            }
            return@withContext json
        }
    }

    suspend fun login(username: String, password: String, role: String) {
        val data = request(
            "POST",
            "/api/auth/login",
            JSONObject().put("username", username).put("password", password).put("role", role)
        ).getJSONObject("data")
        store.username = data.getString("username")
        store.role = data.getString("role")
        store.session = data.getString("session")
    }

    suspend fun signup(username: String, password: String, role: String): JSONObject {
        require(role == "PARENT" || role == "DRIVER") {
            "Admin accounts cannot be created through signup."
        }
        return request(
            "POST",
            "/api/auth/signup",
            JSONObject().put("username", username).put("password", password).put("role", role)
        )
    }

    suspend fun parentChildren() = request("GET", "/api/parent/children").arrayData()
    suspend fun parentTrips() = request("GET", "/api/parent/trips").arrayData()
    suspend fun parentScans(date: String) = request("GET", "/api/parent/scans?date=$date").arrayData()
    suspend fun parentEmergencies() = request("GET", "/api/parent/emergencies").arrayData()
    suspend fun children() = request("GET", "/api/children").arrayData()
    suspend fun trips() = request("GET", "/api/trips").arrayData()
    suspend fun scans(date: String) = request("GET", "/api/scans?date=$date").arrayData()
    suspend fun emergencies() = request("GET", "/api/emergencies").arrayData()
    suspend fun adminOverview() = request("GET", "/api/admin/overview").objectData()
    suspend fun adminParents() = request("GET", "/api/admin/parents").arrayData()
    suspend fun adminParentLinks() = request("GET", "/api/admin/parent-links").arrayData()

    suspend fun createParentLink(parentId: String, childId: String) = request(
        "POST",
        "/api/admin/parent-links",
        JSONObject().put("parentId", parentId).put("childId", childId)
    )

    suspend fun deleteParentLink(parentId: String, childId: String) = request(
        "DELETE",
        "/api/admin/parent-links",
        JSONObject().put("parentId", parentId).put("childId", childId)
    )

    suspend fun child(id: String) = request("GET", "/api/children/${id.pathSegment()}").objectData()

    suspend fun updateChild(
        id: String,
        name: String,
        className: String,
        route: String,
        photoBase64: String?,
        parentConsentObtained: Boolean
    ) = request(
        "PUT",
        "/api/children/${id.pathSegment()}",
        JSONObject().apply {
            put("name", name)
            put("className", className)
            put("route", route)
            if (!photoBase64.isNullOrBlank()) {
                require(parentConsentObtained) {
                    "Parent consent is required before updating a student photo."
                }
                put("parentConsentObtained", true)
            }
            if (!photoBase64.isNullOrBlank()) put("photo", photoBase64)
        }
    )

    suspend fun deleteChild(id: String) =
        request("DELETE", "/api/children/${id.pathSegment()}")

    suspend fun scan(id: String) = request("GET", "/api/scans/${id.pathSegment()}").objectData()
    suspend fun deleteScan(id: String) = request("DELETE", "/api/scans/${id.pathSegment()}")

    suspend fun updateTrip(
        id: String,
        route: String,
        vehicle: String,
        driver: String,
        tripType: String
    ) = request(
        "PUT",
        "/api/trips/${id.pathSegment()}",
        JSONObject().put("route", route).put("vehicle", vehicle)
            .put("driver", driver).put("tripType", tripType)
    )

    suspend fun deleteTrip(id: String) = request("DELETE", "/api/trips/${id.pathSegment()}")

    suspend fun updateEmergency(id: String, fields: JSONObject) =
        request("PUT", "/api/emergencies/${id.pathSegment()}", fields)

    suspend fun deleteEmergency(id: String) =
        request("DELETE", "/api/emergencies/${id.pathSegment()}")

    suspend fun createChild(
        name: String,
        studentId: String,
        className: String,
        route: String,
        photoBase64: String,
        parentUsername: String?,
        parentConsentObtained: Boolean
    ): JSONObject {
        require(photoBase64.isNotBlank()) { "A validated student photo is required." }
        require(parentConsentObtained) { "Parent consent is required before registering a student." }
        return request(
            "POST",
            "/api/children",
            JSONObject().apply {
                put("name", name)
                put("studentId", studentId)
                put("className", className)
                put("route", route)
                put("photo", photoBase64)
                put("parentConsentObtained", parentConsentObtained)
                if (!parentUsername.isNullOrBlank()) put("parentUsername", parentUsername)
            }
        )
    }

    suspend fun createTrip(
        route: String,
        vehicle: String,
        driver: String,
        tripType: String = "MORNING (Home -> School)"
    ) = request(
        "POST",
        "/api/trips",
        JSONObject().put("route", route).put("vehicle", vehicle)
            .put("driver", driver).put("tripType", tripType)
    ).getJSONObject("data")

    suspend fun endTrip(id: String) = request("POST", "/api/trips/$id/end", JSONObject())

    suspend fun scan(
        studentId: String,
        tripId: String,
        phase: String,
        faceMatchScore: Double?,
        photoBase64: String?,
        lat: Double?,
        lon: Double?,
        accuracy: Float?,
        faceMatchPassed: Boolean,
        scanMethod: String
    ) = request(
        "POST",
        "/api/scan",
        JSONObject()
            .put("studentId", studentId)
            .put("tripId", tripId)
            .put("phase", phase)
            .put("scanDate", today())
            .put("faceMatchPassed", faceMatchPassed)
            .put("scanMethod", scanMethod)
            .apply {
                if (faceMatchScore != null) put("faceMatchScore", faceMatchScore)
                if (!photoBase64.isNullOrBlank()) put("scannedFacePhoto", photoBase64)
                if (lat != null) put("latitude", lat)
                if (lon != null) put("longitude", lon)
                if (accuracy != null) put("accuracy", accuracy)
            }
    )

    suspend fun location(tripId: String, lat: Double, lon: Double, accuracy: Float) = request(
        "PUT",
        "/api/trips/$tripId/location",
        JSONObject().put("latitude", lat).put("longitude", lon)
            .put("accuracy", accuracy).put("timestamp", System.currentTimeMillis())
    )

    suspend fun sos(vehicle: String, driver: String, route: String, lat: Double?, lon: Double?) =
        request(
            "POST",
            "/api/emergencies",
            JSONObject().put("vehicle", vehicle).put("driver", driver).put("route", route)
                .put("description", "Emergency button pressed from Child Protector Android app.")
                .apply {
                    if (lat != null) put("latitude", lat)
                    if (lon != null) put("longitude", lon)
                }
        )

    private fun JSONObject.arrayData(): List<JSONObject> {
        val array = optJSONArray("data") ?: optJSONArray("items") ?: JSONArray()
        return (0 until array.length()).map { array.getJSONObject(it) }
    }

    private fun JSONObject.objectData(): JSONObject =
        optJSONObject("data") ?: optJSONObject("item") ?: this

    private fun String.pathSegment(): String =
        java.net.URLEncoder.encode(this, Charsets.UTF_8.name()).replace("+", "%20")

    companion object {
        private val AUTH_PATHS = setOf("/api/auth/login", "/api/auth/signup")

        fun today() = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US)
            .format(java.util.Date())
    }
}
