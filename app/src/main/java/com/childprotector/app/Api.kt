package com.childprotector.app

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

class SessionStore(ctx: Context) {
    private val p = ctx.getSharedPreferences("child_protector", Context.MODE_PRIVATE)
    var username: String? get() = p.getString("username", null); set(v) = p.edit().putString("username", v).apply()
    var role: String? get() = p.getString("role", null); set(v) = p.edit().putString("role", v).apply()
    var session: String? get() = p.getString("session", null); set(v) = p.edit().putString("session", v).apply()
    fun clear() = p.edit().clear().apply()
}

class Api(private val store: SessionStore) {
    private val client = OkHttpClient()
    private val jsonType = "application/json; charset=utf-8".toMediaType()
    private suspend fun request(method: String, path: String, body: JSONObject? = null): JSONObject = withContext(Dispatchers.IO) {
        val url = BuildConfig.API_BASE_URL + path
        val req = Request.Builder().url(url).apply {
            if (body != null) method(method, body.toString().toRequestBody(jsonType)) else method(method, null)
        }.build()
        client.newCall(req).execute().use { r ->
            val raw = r.body?.string().orEmpty()
            val j = try {
                if (raw.isBlank()) JSONObject() else JSONObject(raw)
            } catch (e: Exception) {
                if (!r.isSuccessful) {
                    throw IllegalStateException("Server error (${r.code}): ${r.message}")
                } else {
                    throw IllegalStateException("Invalid response format from server")
                }
            }
            if (!r.isSuccessful) throw IllegalStateException(j.optString("message", j.optString("error", "Request failed (${r.code})")))
            return@withContext j
        }
    }
    suspend fun login(username: String, password: String, role: String) {
        val d = request("POST", "/api/auth/login", JSONObject().put("username", username).put("password", password).put("role", role)).getJSONObject("data")
        store.username = d.getString("username"); store.role = d.getString("role"); store.session = d.getString("session")
    }
    suspend fun signup(username: String, password: String, role: String) = request("POST", "/api/auth/signup", JSONObject().put("username", username).put("password", password).put("role", role))
    private fun q(path: String): String = if (path.contains("?")) "$path&session=${store.session}" else "$path?session=${store.session}"
    suspend fun parentChildren() = request("GET", q("/api/parent/children")).arrayData()
    suspend fun parentTrips() = request("GET", q("/api/parent/trips")).arrayData()
    suspend fun parentScans(date: String) = request("GET", q("/api/parent/scans?date=$date")).arrayData()
    suspend fun parentEmergencies() = request("GET", q("/api/parent/emergencies")).arrayData()
    suspend fun children() = request("GET", q("/api/children")).arrayData()
    suspend fun trips() = request("GET", q("/api/trips")).arrayData()
    suspend fun scans(date: String) = request("GET", "/api/scans?date=$date").arrayData()
    suspend fun emergencies() = request("GET", "/api/emergencies").arrayData()
    suspend fun createChild(name: String, studentId: String, className: String, route: String, photoBase64: String?, parentUsername: String? = null) = request("POST", q("/api/children"), JSONObject().apply {
        put("name", name)
        put("studentId", studentId)
        put("className", className)
        put("route", route)
        if (!photoBase64.isNullOrBlank()) put("photo", photoBase64)
        if (!parentUsername.isNullOrBlank()) put("parentUsername", parentUsername)
    })
    suspend fun createTrip(route: String, vehicle: String, driver: String, tripType: String = "MORNING (Home -> School)") = request("POST", q("/api/trips"), JSONObject().put("route", route).put("vehicle", vehicle).put("driver", driver).put("tripType", tripType)).getJSONObject("data")
    suspend fun endTrip(id: String) = request("POST", "/api/trips/$id/end", JSONObject())
    suspend fun scan(studentId: String, tripId: String, phase: String, faceMatchScore: Double, photoBase64: String?, lat: Double?, lon: Double?, accuracy: Float?) = request("POST", q("/api/scan"), JSONObject().put("studentId",studentId).put("tripId",tripId).put("phase",phase).put("scanDate",today()).put("faceMatchPassed",true).put("faceMatchScore",faceMatchScore).apply { if(!photoBase64.isNullOrBlank()) put("scannedFacePhoto", photoBase64); if(lat!=null) put("latitude",lat); if(lon!=null) put("longitude",lon); if(accuracy!=null) put("accuracy",accuracy) })
    suspend fun location(tripId: String, lat: Double, lon: Double, accuracy: Float) = request("PUT", "/api/trips/$tripId/location", JSONObject().put("latitude",lat).put("longitude",lon).put("accuracy",accuracy).put("timestamp",System.currentTimeMillis()))
    suspend fun sos(vehicle: String, driver: String, route: String, lat: Double?, lon: Double?) = request("POST", "/api/emergencies", JSONObject().put("vehicle",vehicle).put("driver",driver).put("route",route).put("description","Emergency button pressed from Child Protector Android app.").apply { if(lat!=null) put("latitude",lat); if(lon!=null) put("longitude",lon) })
    private fun JSONObject.arrayData(): List<JSONObject> { val a = optJSONArray("data") ?: optJSONArray("items") ?: JSONArray(); return (0 until a.length()).map { a.getJSONObject(it) } }
    companion object { fun today() = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(java.util.Date()) }
}
