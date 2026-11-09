package com.example.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.data.network.PersonalControlApi
import com.example.ui.viewmodel.TaskitatorViewModel
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

@Composable
fun BreaksScreen(viewModel: TaskitatorViewModel) {
    val settings by viewModel.settings.collectAsState()
    val token = settings.authToken
    val scope = rememberCoroutineScope()
    var state by remember(token) { mutableStateOf<JSONObject?>(null) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    var minutesText by remember { mutableStateOf("30") }
    var scheduledStart by remember { mutableStateOf("13:00") }
    var scheduledEnd by remember { mutableStateOf("13:30") }
    var reservedMinutes by remember { mutableStateOf("15") }
    var reservedStart by remember { mutableStateOf("17:00") }
    var reservedEnd by remember { mutableStateOf("17:30") }
    var useReservation by remember { mutableStateOf(false) }
    var strictFlexible by remember { mutableStateOf(false) }
    var strictScheduled by remember { mutableStateOf(false) }
    var allowanceMinutes by remember { mutableStateOf("180") }

    fun refresh() {
        scope.launch {
            busy = true
            val result = PersonalControlApi.request("/breaks", token)
            result.onSuccess {
                state = it
                val cfg = it.optJSONObject("strict")?.optJSONObject("config")
                if (cfg != null) {
                    strictFlexible = cfg.optJSONObject("flexible")?.optBoolean("enabled") ?: false
                    strictScheduled = cfg.optJSONObject("scheduled")?.optBoolean("enabled") ?: false
                    allowanceMinutes = cfg.optInt("allowance", 180).toString()
                }
                error = ""
            }.onFailure { error = it.message ?: "Unable to load break state" }
            busy = false
        }
    }
    fun action(payload: JSONObject) {
        scope.launch {
            busy = true
            val result = PersonalControlApi.request("/breaks", token, payload)
            result.onSuccess { state = it; error = "" }
                .onFailure { error = it.message ?: "Break action rejected" }
            busy = false
        }
    }
    LaunchedEffect(token) { if (token.isNotBlank()) refresh() }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Breaks · Server controlled", style = MaterialTheme.typography.headlineMedium)
        Text("All allowances, restrictions and timers are checked by Cloudflare. Offline changes are disabled.", style = MaterialTheme.typography.bodySmall)
        if (token.isBlank()) Text("Sign in from Settings to manage breaks.")
        if (error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error)
        OutlinedButton(onClick = { refresh() }, enabled = !busy && token.isNotBlank()) { Text("Refresh from server") }
        val data = state
        if (data != null) {
            val allowance = data.optJSONObject("allowance") ?: JSONObject()
            Text("Day: ${data.optString("date")} · System: ${data.optString("system", "Not selected")}")
            Text("Allowance: ${allowance.optInt("remaining")} min remaining of ${allowance.optInt("total")} min")
            val schedule = data.optJSONObject("scheduled") ?: JSONObject()
            val planning = schedule.optJSONObject("planning")
            Text("Scheduled breaks", style = MaterialTheme.typography.titleLarge)
            if (schedule.optString("reason").isNotBlank()) Text(schedule.optString("reason"), style = MaterialTheme.typography.bodySmall)
            Button(onClick = { action(JSONObject().put("action", "plan_start")) },
                enabled = !busy && schedule.optBoolean("can_start_planning")) { Text("Start 10-minute planning") }
            if (planning != null && planning.optBoolean("open")) {
                Text("Planning window open. Deadline: ${java.text.DateFormat.getTimeInstance().format(java.util.Date(planning.optLong("deadline")))}")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(scheduledStart, { scheduledStart = it }, label = { Text("Start HH:mm") }, modifier = Modifier.weight(1f))
                    OutlinedTextField(scheduledEnd, { scheduledEnd = it }, label = { Text("End HH:mm") }, modifier = Modifier.weight(1f))
                }
                Row {
                    Checkbox(useReservation, { useReservation = it })
                    Text("Include a reserved break")
                }
                if (useReservation) {
                    OutlinedTextField(reservedMinutes, { reservedMinutes = it }, label = { Text("Reserved minutes") })
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(reservedStart, { reservedStart = it }, label = { Text("Window from") }, modifier = Modifier.weight(1f))
                        OutlinedTextField(reservedEnd, { reservedEnd = it }, label = { Text("Window to") }, modifier = Modifier.weight(1f))
                    }
                }
                Button(onClick = {
                    val timesOk = listOf(scheduledStart, scheduledEnd).all { Regex("[0-2][0-9]:[0-5][0-9]").matches(it) }
                    if (!timesOk) { error = "Use HH:mm clock times"; return@Button }
                    val payload = JSONObject().put("action", "schedule_save")
                        .put("breaks", JSONArray().put(JSONObject().put("start", scheduledStart).put("end", scheduledEnd)))
                    if (useReservation) payload.put("reserved", JSONArray().put(JSONObject()
                        .put("minutes", reservedMinutes.toIntOrNull() ?: 0)
                        .put("from", reservedStart).put("to", reservedEnd)))
                    action(payload)
                }, enabled = !busy) { Text("Save scheduled breaks") }
                TextButton(onClick = { action(JSONObject().put("action", "plan_cancel")) }, enabled = !busy) { Text("Cancel planning") }
            }
            val savedBreaks = schedule.optJSONArray("breaks") ?: JSONArray()
            for (i in 0 until savedBreaks.length()) {
                val item = savedBreaks.optJSONObject(i) ?: continue
                Text("Scheduled: ${item.optString("start")} – ${item.optString("end")}")
            }
            if (!schedule.optBoolean("locked")) {
                OutlinedButton(onClick = { action(JSONObject().put("action", "lock").put("system", "scheduled")) }, enabled = !busy) {
                    Text("Lock Scheduled for today")
                }
            }
            HorizontalDivider()
            Text("Flexible breaks", style = MaterialTheme.typography.titleLarge)
            val flex = data.optJSONObject("flexible") ?: JSONObject()
            val active = flex.optJSONObject("active")
            if (active == null) {
                if (flex.optString("reason").isNotBlank()) Text(flex.optString("reason"), style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(minutesText, { minutesText = it }, label = { Text("Duration (minutes)") })
                Button(onClick = {
                    val minutes = minutesText.toIntOrNull() ?: 0
                    action(JSONObject().put("action", "flex_start").put("mode", "custom")
                        .put("minutes", minutes).put("operation_id", UUID.randomUUID().toString()))
                }, enabled = !busy && flex.optBoolean("can_start") && (minutesText.toIntOrNull() ?: 0) in flex.optInt("minimum", 15)..flex.optInt("max_custom", 0)) {
                    Text("Start Flexible break")
                }
            } else {
                Text("Active: ${active.optInt("minutes")} min. Server end: ${java.text.DateFormat.getTimeInstance().format(java.util.Date(active.optLong("ends_at")))}")
                Button(onClick = { action(JSONObject().put("action", "flex_end").put("break_id", active.optString("id"))) },
                    enabled = !busy && System.currentTimeMillis() >= active.optLong("can_end_at")) { Text("End Flexible break") }
            }
            HorizontalDivider()
            Text("Reserved breaks", style = MaterialTheme.typography.titleLarge)
            val reserved = data.optJSONArray("reserved_breaks") ?: JSONArray()
            if (reserved.length() == 0) Text("No reserved breaks today.")
            for (i in 0 until reserved.length()) {
                val item = reserved.optJSONObject(i) ?: continue
                Text("${item.optInt("minutes")} min · ${item.optString("from")}–${item.optString("to")} · ${item.optString("state")}")
                if (item.optBoolean("can_start")) Button(onClick = { action(JSONObject().put("action", "reserved_start")
                    .put("id", item.optString("id")).put("operation_id", UUID.randomUUID().toString())) }, enabled = !busy) {
                    Text("Start reserved break")
                }
            }
            HorizontalDivider()
            Text("Strict mode settings", style = MaterialTheme.typography.titleLarge)
            val strict = data.optJSONObject("strict") ?: JSONObject()
            if (strict.optBoolean("locked")) Text("Settings locked until today's required tasks are completed or a server-approved bypass is active.")
            Row { Checkbox(strictFlexible, { strictFlexible = it }); Text("Flexible Strict Mode") }
            Row { Checkbox(strictScheduled, { strictScheduled = it }); Text("Scheduled Strict Mode") }
            OutlinedTextField(allowanceMinutes, { allowanceMinutes = it }, label = { Text("Daily maximum (60–180 min, steps of 15)") })
            Button(onClick = {
                val config = JSONObject(strict.optJSONObject("config")?.toString() ?: "{}")
                config.put("allowance", allowanceMinutes.toIntOrNull() ?: 180)
                val f = config.optJSONObject("flexible") ?: JSONObject().also { config.put("flexible", it) }
                val scheduled = config.optJSONObject("scheduled") ?: JSONObject().also { config.put("scheduled", it) }
                f.put("enabled", strictFlexible)
                scheduled.put("enabled", strictScheduled)
                action(JSONObject().put("action", "strict_save").put("revision", strict.optInt("revision"))
                    .put("config", config).put("timezone", data.optString("timezone")))
            }, enabled = !busy && !strict.optBoolean("locked")) { Text("Save Strict settings") }
            HorizontalDivider()
            var confirmBypass by remember { mutableStateOf(false) }
            OutlinedButton(onClick = { confirmBypass = true }, enabled = !busy) { Text("Emergency Bypass") }
            if (confirmBypass) AlertDialog(onDismissRequest = { confirmBypass = false },
                title = { Text("Use Emergency Bypass?") },
                text = { Text("This consumes one of your server-managed emergency uses. The temporary window is 15 minutes.") },
                confirmButton = { TextButton(onClick = {
                    confirmBypass = false
                    scope.launch {
                        busy = true
                        PersonalControlApi.request("/emergency", token, JSONObject()).onSuccess { result ->
                            error = "Bypass response: ${result.optInt("uses_left")} uses left."
                            refresh()
                        }.onFailure { error = it.message ?: "Bypass rejected" }
                        busy = false
                    }
                }) { Text("Activate") } },
                dismissButton = { TextButton(onClick = { confirmBypass = false }) { Text("Cancel") } })
        }
    }
}
