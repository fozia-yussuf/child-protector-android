package com.childprotector.app.ui.admin

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.dp
import com.childprotector.app.*
import com.childprotector.app.ui.common.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import org.json.JSONObject

@Composable
fun AdminTripsScreen(vm: AppVM) {
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf("") }
    var reloadKey by remember { mutableIntStateOf(0) }
    var create by remember { mutableStateOf(false) }
    var edit by remember { mutableStateOf<JSONObject?>(null) }
    var ending by remember { mutableStateOf<JSONObject?>(null) }
    var deleting by remember { mutableStateOf<JSONObject?>(null) }
    val scope = rememberCoroutineScope()

    suspend fun reload() {
        loading = true
        error = ""
        try {
            vm.trips = vm.apiClient().trips()
        } catch (exception: Exception) {
            error = exception.message ?: "Could not load trips."
        } finally {
            loading = false
        }
    }

    LaunchedEffect(reloadKey) { reload() }
    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Trips", style = MaterialTheme.typography.headlineSmall, color = CutePurplePrimary)
            Button(onClick = { create = true }) { Text("Create trip") }
        }
        if (loading || error.isNotBlank() || vm.trips.isEmpty()) {
            AdminListState(loading, error, vm.trips.isEmpty(), "No trips found.") { reloadKey++ }
        }
        LazyColumn(Modifier.weight(1f).padding(12.dp)) {
            items(vm.trips) { trip ->
                Card(Modifier.fillMaxWidth()) {
                    Text("${trip.optString("route")} · ${trip.optString("vehicle")}", fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
                    Text("${trip.optString("driver")} · ${trip.optString("tripType")}")
                    Text("Status: ${trip.optString("status")}")
                    Row {
                        TextButton(onClick = { edit = trip }) { Text("Edit") }
                        if (trip.optString("status").equals("ACTIVE", true)) {
                            TextButton(onClick = { ending = trip }) { Text("End") }
                        }
                        TextButton(onClick = { deleting = trip }) { Text("Delete") }
                    }
                }
            }
        }
    }
    if (create || edit != null) {
        TripEditor(vm, edit, { create = false; edit = null }, { create = false; edit = null; reloadKey++ }) {
            error = it
        }
    }
    ending?.let { trip ->
        ConfirmActionDialog("End trip?", "End the active trip on ${trip.optString("route")}?", "End",
            { ending = null }) {
            scope.launch {
                try {
                    vm.apiClient().endTrip(trip.optString("id"))
                    ending = null
                    reloadKey++
                } catch (exception: Exception) {
                    error = exception.message ?: "Could not end trip."
                    ending = null
                }
            }
        }
    }
    deleting?.let { trip ->
        ConfirmActionDialog("Delete trip?", "Delete trip ${trip.optString("route")}? This cannot be undone.", "Delete",
            { deleting = null }) {
            scope.launch {
                try {
                    vm.apiClient().deleteTrip(trip.optString("id"))
                    deleting = null
                    reloadKey++
                } catch (exception: Exception) {
                    error = exception.message ?: "Could not delete trip."
                    deleting = null
                }
            }
        }
    }
}

@Composable
private fun TripEditor(
    vm: AppVM,
    trip: JSONObject?,
    onDismiss: () -> Unit,
    onSaved: () -> Unit,
    onError: (String) -> Unit
) {
    val scope = rememberCoroutineScope()
    var route by remember(trip) { mutableStateOf(trip?.optString("route").orEmpty()) }
    var vehicle by remember(trip) { mutableStateOf(trip?.optString("vehicle").orEmpty()) }
    var driver by remember(trip) { mutableStateOf(trip?.optString("driver").orEmpty()) }
    var type by remember(trip) { mutableStateOf(trip?.optString("tripType").orEmpty()) }
    var error by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (trip == null) "Create trip" else "Edit trip") },
        text = {
            Column {
                OutlinedTextField(route, { route = it }, label = { Text("Route") })
                OutlinedTextField(vehicle, { vehicle = it }, label = { Text("Vehicle") })
                OutlinedTextField(driver, { driver = it }, label = { Text("Driver") })
                OutlinedTextField(type, { type = it }, label = { Text("Trip type") })
                if (error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error)
            }
        },
        confirmButton = {
            TextButton(enabled = !busy && route.isNotBlank(), onClick = {
                scope.launch {
                    busy = true
                    try {
                        if (trip == null) vm.apiClient().createTrip(route, vehicle, driver, type)
                        else vm.apiClient().updateTrip(trip.optString("id"), route, vehicle, driver, type)
                        onSaved()
                    } catch (exception: Exception) {
                        error = exception.message ?: "Could not save trip."
                        onError(error)
                    } finally {
                        busy = false
                    }
                }
            }) { Text(if (busy) "Saving..." else "Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

@Composable
fun AdminGpsScreen(vm: AppVM) {
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf("") }
    var reloadKey by remember { mutableIntStateOf(0) }
    LaunchedEffect(reloadKey) {
        try {
            vm.trips = vm.apiClient().trips()
            error = ""
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (exception: Exception) {
            error = exception.message ?: "Could not load live GPS."
        } finally {
            loading = false
        }
    }
    val active = vm.trips.filter { it.optString("status").equals("ACTIVE", true) }
    LazyColumn(Modifier.fillMaxSize().padding(16.dp)) {
        item {
            Text("📍 Live GPS", style = MaterialTheme.typography.headlineSmall, color = CutePurplePrimary)
            if (loading || error.isNotBlank() || active.isEmpty()) {
                AdminListState(loading, error, active.isEmpty(), "No active trips.") { reloadKey++ }
            }
            val mappedTrip = active
                .filter { it.doubleValue("latitude") != null && it.doubleValue("longitude") != null }
                .maxByOrNull {
                    it.stringValue("locationUpdatedAt", "gpsUpdatedAt", "lastUpdated", "updatedAt", "timestamp")
                        ?.toEpochMillis() ?: 0L
                }
            if (mappedTrip != null) {
                EmbeddedMap(
                    mappedTrip.doubleValue("latitude")!!,
                    mappedTrip.doubleValue("longitude")!!,
                    Modifier.fillMaxWidth().height(260.dp)
                )
            }
        }
        items(active) { trip ->
            val lat = trip.doubleValue("latitude")
            val lon = trip.doubleValue("longitude")
            val updated = trip.stringValue(
                "locationUpdatedAt", "gpsUpdatedAt", "lastUpdated", "updatedAt", "timestamp"
            )
            Card(Modifier.fillMaxWidth()) {
                Text("${trip.optString("route")} · ${trip.optString("vehicle")}", fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
                Text("${lat ?: "—"}, ${lon ?: "—"}")
                LastUpdated(updated)
                OpenMapButton(lat, lon, "${trip.optString("route")} ${trip.optString("vehicle")}")
            }
        }
    }
}

@Composable
fun AdminAttendanceScreen(vm: AppVM) {
    var date by remember { mutableStateOf(Api.today()) }
    var route by remember { mutableStateOf("") }
    var scans by remember { mutableStateOf(emptyList<JSONObject>()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf("") }
    var reloadKey by remember { mutableIntStateOf(0) }
    var selected by remember { mutableStateOf<JSONObject?>(null) }
    var deleting by remember { mutableStateOf<JSONObject?>(null) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(date, reloadKey) {
        loading = true
        error = ""
        try {
            scans = vm.apiClient().scans(date)
        } catch (exception: Exception) {
            error = exception.message ?: "Could not load attendance."
        } finally {
            loading = false
        }
    }
    val filtered = scans.filter { route.isBlank() || it.optString("route").contains(route, true) }
    Column(Modifier.fillMaxSize()) {
        Text("📋 Attendance", Modifier.padding(16.dp), style = MaterialTheme.typography.headlineSmall, color = CutePurplePrimary)
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
            OutlinedTextField(date, { date = it }, label = { Text("Date (yyyy-MM-dd)") }, modifier = Modifier.weight(1f))
            OutlinedTextField(route, { route = it }, label = { Text("Route") }, modifier = Modifier.weight(1f))
        }
        if (loading || error.isNotBlank() || filtered.isEmpty()) {
            AdminListState(loading, error, filtered.isEmpty(), "No attendance scans for these filters.") { reloadKey++ }
        }
        LazyColumn(Modifier.weight(1f).padding(12.dp)) {
            items(filtered) { scan ->
                Card(Modifier.fillMaxWidth().clickable { selected = scan }) {
                    Text(scan.optString("studentName"), fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
                    Text("${scan.optString("route")} · ${scan.optString("scannedAt")}")
                    TextButton(onClick = { deleting = scan }) { Text("Delete") }
                }
            }
        }
    }
    selected?.let { scan ->
        ScanDetails(vm, scan, { selected = null }) { error = it }
    }
    deleting?.let { scan ->
        ConfirmActionDialog("Delete attendance?", "Delete this attendance record? This cannot be undone.", "Delete",
            { deleting = null }) {
            scope.launch {
                try {
                    vm.apiClient().deleteScan(scan.optString("id"))
                    deleting = null
                    reloadKey++
                } catch (exception: Exception) {
                    error = exception.message ?: "Could not delete attendance."
                    deleting = null
                }
            }
        }
    }
}

@Composable
private fun ScanDetails(vm: AppVM, scan: JSONObject, onDismiss: () -> Unit, onError: (String) -> Unit) {
    var detail by remember(scan) { mutableStateOf<JSONObject?>(null) }
    var error by remember { mutableStateOf("") }
    LaunchedEffect(scan) {
        try {
            detail = vm.apiClient().scan(scan.optString("id"))
        } catch (exception: Exception) {
            error = exception.message ?: "Could not load scan details."
            onError(error)
        }
    }
    val row = detail ?: scan
    val photo = row.stringValue("scannedFacePhoto", "photo")
    val bitmap = remember(photo) { photo?.toBitmap() }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Attendance details") },
        text = {
            Column {
                if (error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error)
                Text("Student: ${row.optString("studentName")}")
                Text("Route: ${row.optString("route")}")
                Text("Time: ${row.optString("scannedAt", row.optString("createdAt"))}")
                Text("Phase: ${row.optString("phase")}")
                Text("Score: ${row.optString("faceMatchScore", "—")}")
                Text("Location: ${row.optString("latitude", "—")}, ${row.optString("longitude", "—")}")
                bitmap?.let { Image(it.asImageBitmap(), "Scanned face", Modifier.size(120.dp)) }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } }
    )
}

@Composable
fun AdminEmergenciesScreen(vm: AppVM) {
    var rows by remember { mutableStateOf(emptyList<JSONObject>()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf("") }
    var reloadKey by remember { mutableIntStateOf(0) }
    var editing by remember { mutableStateOf<JSONObject?>(null) }
    var deleting by remember { mutableStateOf<JSONObject?>(null) }
    var statusUpdate by remember { mutableStateOf<Pair<JSONObject, String>?>(null) }
    var selectedMap by remember { mutableStateOf<JSONObject?>(null) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(reloadKey) {
        loading = true
        error = ""
        try {
            rows = vm.apiClient().emergencies()
        } catch (exception: Exception) {
            error = exception.message ?: "Could not load emergencies."
        } finally {
            loading = false
        }
    }
    Column(Modifier.fillMaxSize()) {
        Text("🚨 Emergencies", Modifier.padding(16.dp), style = MaterialTheme.typography.headlineSmall, color = CutePurplePrimary)
        if (loading || error.isNotBlank() || rows.isEmpty()) {
            AdminListState(loading, error, rows.isEmpty(), "No emergencies.") { reloadKey++ }
        }
        LazyColumn(Modifier.weight(1f).padding(12.dp)) {
            items(rows) { emergency ->
                val resolved = emergency.optString("status").uppercase() in setOf("CLOSED", "RESOLVED")
                Card(Modifier.fillMaxWidth()) {
                    Text("${emergency.optString("vehicle")} · ${emergency.optString("route")}", fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
                    Text(emergency.optString("description"))
                    Text("Status: ${emergency.optString("status")}")
                    LastUpdated(
                        emergency.stringValue("updatedAt", "timestamp", "createdAt", "lastUpdated")
                    )
                    TextButton(
                        enabled = emergency.doubleValue("latitude") != null &&
                                emergency.doubleValue("longitude") != null,
                        onClick = { selectedMap = emergency }
                    ) { Text("View on map") }
                    Row {
                        TextButton(onClick = { editing = emergency }) { Text("Edit") }
                        if (!resolved) {
                            TextButton(onClick = { statusUpdate = emergency to "RESOLVED" }) { Text("Resolve") }
                            TextButton(onClick = { statusUpdate = emergency to "CLOSED" }) { Text("Close") }
                        }
                        TextButton(onClick = { deleting = emergency }) { Text("Delete") }
                    }
                }
            }
        }
    }
    editing?.let { row ->
        EmergencyEditor(vm, row, { editing = null }, { editing = null; reloadKey++ }) { error = it }
    }
    statusUpdate?.let { (row, status) ->
        ConfirmActionDialog("Update emergency?", "Set status to $status?", status, { statusUpdate = null }) {
            scope.launch {
                try {
                    vm.apiClient().updateEmergency(row.optString("id"), JSONObject().put("status", status))
                    statusUpdate = null
                    reloadKey++
                } catch (exception: Exception) {
                    error = exception.message ?: "Could not update emergency."
                    statusUpdate = null
                }
            }
        }
    }
    deleting?.let { row ->
        ConfirmActionDialog("Delete emergency?", "Delete this emergency record? This cannot be undone.", "Delete",
            { deleting = null }) {
            scope.launch {
                try {
                    vm.apiClient().deleteEmergency(row.optString("id"))
                    deleting = null
                    reloadKey++
                } catch (exception: Exception) {
                    error = exception.message ?: "Could not delete emergency."
                    deleting = null
                }
            }
        }
    }
    selectedMap?.let { emergency ->
        AlertDialog(
            onDismissRequest = { selectedMap = null },
            title = { Text("Emergency location") },
            text = {
                Column {
                    Text("${emergency.optString("latitude")}, ${emergency.optString("longitude")}")
                    LastUpdated(
                        emergency.stringValue("updatedAt", "timestamp", "createdAt", "lastUpdated")
                    )
                    val latitude = emergency.doubleValue("latitude")
                    val longitude = emergency.doubleValue("longitude")
                    if (latitude != null && longitude != null) {
                        EmbeddedMap(latitude, longitude, Modifier.fillMaxWidth().height(300.dp))
                    }
                }
            },
            confirmButton = { TextButton(onClick = { selectedMap = null }) { Text("Close") } }
        )
    }
}

@Composable
private fun EmergencyEditor(
    vm: AppVM,
    emergency: JSONObject,
    onDismiss: () -> Unit,
    onSaved: () -> Unit,
    onError: (String) -> Unit
) {
    val scope = rememberCoroutineScope()
    var description by remember(emergency) { mutableStateOf(emergency.optString("description")) }
    var status by remember(emergency) { mutableStateOf(emergency.optString("status")) }
    var error by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Edit emergency") },
        text = {
            Column {
                OutlinedTextField(description, { description = it }, label = { Text("Description") })
                OutlinedTextField(status, { status = it }, label = { Text("Status") })
                if (error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error)
            }
        },
        confirmButton = {
            TextButton(enabled = !busy && description.isNotBlank() && status.isNotBlank(), onClick = {
                scope.launch {
                    busy = true
                    try {
                        vm.apiClient().updateEmergency(
                            emergency.optString("id"),
                            JSONObject().put("description", description).put("status", status)
                        )
                        onSaved()
                    } catch (exception: Exception) {
                        error = exception.message ?: "Could not update emergency."
                        onError(error)
                    } finally {
                        busy = false
                    }
                }
            }) { Text(if (busy) "Saving..." else "Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

@Composable
fun AdminParentAccessScreen(vm: AppVM) {
    var parents by remember { mutableStateOf(emptyList<JSONObject>()) }
    var links by remember { mutableStateOf(emptyList<JSONObject>()) }
    var selectedParent by remember { mutableStateOf("") }
    var selectedChild by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf("") }
    var reloadKey by remember { mutableIntStateOf(0) }
    var deleting by remember { mutableStateOf<JSONObject?>(null) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(reloadKey) {
        loading = true
        error = ""
        try {
            parents = vm.apiClient().adminParents()
            links = vm.apiClient().adminParentLinks()
            if (selectedParent.isBlank()) selectedParent = parents.firstOrNull()?.optString("id").orEmpty()
        } catch (exception: Exception) {
            error = exception.message ?: "Could not load parent access."
        } finally {
            loading = false
        }
    }
    val linked = links.filter {
        it.optString("parentId") == selectedParent ||
                it.optJSONObject("parent")?.optString("id") == selectedParent
    }
    val linkedChildIds = linked.map {
        it.stringValue("childId") ?: it.optJSONObject("child")?.optString("id").orEmpty()
    }.toSet()
    val children = vm.children.filter { it.optString("id") !in linkedChildIds }
    Column(Modifier.fillMaxSize().padding(12.dp)) {
        Text("Parent Access", style = MaterialTheme.typography.headlineSmall, color = CutePurplePrimary)
        if (loading || error.isNotBlank() || parents.isEmpty()) {
            AdminListState(loading, error, parents.isEmpty(), "No parent accounts found.") { reloadKey++ }
        }
        if (parents.isNotEmpty()) {
            Choice(
                "Parent",
                parents.map { it.optString("id") to it.optString("username", it.optString("name")) },
                selectedParent
            ) { selectedParent = it; selectedChild = "" }
            Text("Linked children")
            if (linked.isEmpty()) Text("No linked children.", color = androidx.compose.ui.graphics.Color.Gray)
            LazyColumn(Modifier.weight(1f)) {
                items(linked) { link ->
                    val childId = link.stringValue("childId")
                        ?: link.optJSONObject("child")?.optString("id").orEmpty()
                    val child = vm.children.find { it.optString("id") == childId }
                        ?: link.optJSONObject("child")
                    Card(Modifier.fillMaxWidth()) {
                        Text(child?.optString("name").orEmpty().ifBlank { childId })
                        TextButton(onClick = { deleting = link }) { Text("Remove link") }
                    }
                }
            }
            if (children.isNotEmpty()) {
                Choice("Child", children.map { it.optString("id") to it.optString("name") }, selectedChild) {
                    selectedChild = it
                }
                Button(enabled = selectedParent.isNotBlank() && selectedChild.isNotBlank(), onClick = {
                    scope.launch {
                        try {
                            vm.apiClient().createParentLink(selectedParent, selectedChild)
                            selectedChild = ""
                            reloadKey++
                        } catch (exception: Exception) {
                            error = exception.message ?: "Could not link parent and child."
                        }
                    }
                }) { Text("Link child") }
            } else {
                Text("All students are already linked to this parent.")
            }
        }
    }
    deleting?.let { link ->
        ConfirmActionDialog("Remove parent link?", "Remove this parent-to-child access?", "Remove",
            { deleting = null }) {
            scope.launch {
                try {
                    val childId = link.stringValue("childId")
                        ?: link.optJSONObject("child")?.optString("id").orEmpty()
                    vm.apiClient().deleteParentLink(selectedParent, childId)
                    deleting = null
                    reloadKey++
                } catch (exception: Exception) {
                    error = exception.message ?: "Could not remove parent link."
                    deleting = null
                }
            }
        }
    }
}

private fun JSONObject.stringValue(vararg keys: String): String? =
    keys.firstNotNullOfOrNull { key -> optString(key).takeIf { it.isNotBlank() && it != "null" } }

private fun JSONObject.doubleValue(key: String): Double? =
    if (has(key) && !isNull(key)) optDouble(key).takeIf { it.isFinite() } else null
