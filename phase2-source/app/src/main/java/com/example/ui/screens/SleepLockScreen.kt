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
import org.json.JSONObject

@Composable
fun SleepLockScreen(viewModel: TaskitatorViewModel) {
    val settings by viewModel.settings.collectAsState()
    val token = settings.authToken
    val scope = rememberCoroutineScope()
    var schedule by remember(token) { mutableStateOf<JSONObject?>(null) }
    var hasPhone by remember(token) { mutableStateOf(false) }
    var enabled by remember { mutableStateOf(false) }
    var bedtime by remember { mutableStateOf("23:00") }
    var wake by remember { mutableStateOf("07:00") }
    var revision by remember { mutableIntStateOf(0) }
    var delayed by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("") }
    var confirmation by remember { mutableIntStateOf(0) }
    val validTime = Regex("(?:[01][0-9]|2[0-3]):[0-5][0-9]")

    fun refresh() {
        scope.launch {
            busy = true
            val phoneResult = PersonalControlApi.request("/auth/phone", token)
            phoneResult.onSuccess { hasPhone = it.optBoolean("active", false) }
                .onFailure { message = it.message ?: "Unable to verify phone connection" }
            PersonalControlApi.request("/sleep-lock", token).onSuccess { response ->
                schedule = response
                val saved = response.optJSONObject("schedule") ?: JSONObject()
                enabled = saved.optBoolean("enabled")
                bedtime = saved.optString("bedtime", "23:00")
                wake = saved.optString("wake_time", "07:00")
                revision = saved.optInt("revision")
                delayed = response.optJSONObject("delay") != null
                message = "Loaded from server"
            }.onFailure { message = it.message ?: "Unable to load Sleep Lock" }
            busy = false
        }
    }
    fun post(body: JSONObject) {
        scope.launch {
            busy = true
            PersonalControlApi.request("/sleep-lock", token, body).onSuccess {
                message = "Saved by server"
                refresh()
            }.onFailure { message = it.message ?: "Server rejected request" }
            busy = false
        }
    }
    LaunchedEffect(token) { if (token.isNotBlank()) refresh() }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Sleep Lock", style = MaterialTheme.typography.headlineMedium)
        Text("Server-enforced sleep schedule · Asia/Riyadh", style = MaterialTheme.typography.bodySmall)
        if (token.isBlank()) Text("Sign in from Settings first.")
        if (message.isNotBlank()) Text(message)
        OutlinedButton(onClick = { refresh() }, enabled = !busy && token.isNotBlank()) { Text("Refresh") }
        if (schedule != null) {
            if (!hasPhone) Text("Connect a phone bridge in Settings before enabling Sleep Lock.", color = MaterialTheme.colorScheme.error)
            Row {
                Checkbox(enabled, { enabled = it }, enabled = !busy && hasPhone)
                Text("Enable Sleep Lock")
            }
            OutlinedTextField(bedtime, { bedtime = it }, label = { Text("Bedtime HH:mm") }, enabled = !busy && hasPhone)
            OutlinedTextField(wake, { wake = it }, label = { Text("Wake time HH:mm") }, enabled = !busy && hasPhone)
            Button(onClick = {
                post(JSONObject().put("enabled", enabled).put("bedtime", bedtime)
                    .put("wake_time", wake).put("revision", revision))
            }, enabled = !busy && hasPhone && validTime.matches(bedtime) && validTime.matches(wake) && bedtime != wake) {
                Text("Save sleep schedule")
            }
            HorizontalDivider()
            Text("One delay per night", style = MaterialTheme.typography.titleMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { confirmation = 30 }, enabled = !busy && !delayed && enabled) { Text("Delay 30 min") }
                OutlinedButton(onClick = { confirmation = 60 }, enabled = !busy && !delayed && enabled) { Text("Delay 60 min") }
            }
            if (delayed) Text("Tonight's delay has already been used.")
        }
    }
    if (confirmation > 0) AlertDialog(
        onDismissRequest = { confirmation = 0 },
        title = { Text("Delay tonight's sleep lock?") },
        text = { Text("You can use only one delay per night. The server will enforce the limit.") },
        confirmButton = { TextButton(onClick = {
            val duration = confirmation
            confirmation = 0
            post(JSONObject().put("action", "delay").put("minutes", duration).put("revision", revision))
        }) { Text("Confirm") } },
        dismissButton = { TextButton(onClick = { confirmation = 0 }) { Text("Cancel") } }
    )
}
