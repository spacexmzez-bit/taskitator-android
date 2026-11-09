package com.example.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.model.CustomPriority
import com.example.data.network.TaskitatorWorkerApi
import com.example.data.network.PhoneDeviceInfo
import org.json.JSONObject
import com.example.ui.theme.*
import com.example.ui.viewmodel.TaskitatorViewModel
import kotlinx.coroutines.launch

@Composable
fun SettingsScreen(
    viewModel: TaskitatorViewModel,
    modifier: Modifier = Modifier
) {
    val settings by viewModel.settings.collectAsState()
    val authState by viewModel.authState.collectAsState()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var selectedTab by remember { mutableIntStateOf(0) }
    val tabTitles = listOf("General", "Tasks", "Day & Week", "AI & Pro", "Phone Lock", "Account")

    // Phase 1 native language and appearance controls.
    var chosenLanguage by remember(settings.language) { mutableStateOf(settings.language) }
    var chosenTheme by remember(settings.appearanceTheme) { mutableStateOf(settings.appearanceTheme) }

    // General state
    var copilotNameInput by remember(settings.copilotName) { mutableStateOf(settings.copilotName) }
    var soundEffects by remember(settings.soundEffectsEnabled) { mutableStateOf(settings.soundEffectsEnabled) }
    var haptics by remember(settings.hapticsEnabled) { mutableStateOf(settings.hapticsEnabled) }
    var dumpDuration by remember(settings.dumpDurationSeconds) { mutableFloatStateOf(settings.dumpDurationSeconds.toFloat()) }
    var syncStatusMessage by remember { mutableStateOf<String?>(null) }
    var isSyncing by remember { mutableStateOf(false) }

    // Tasks state
    var strictValidation by remember(settings.strictCriteriaValidation) { mutableStateOf(settings.strictCriteriaValidation) }
    var newTagInput by remember { mutableStateOf("") }

    // Day & week state
    var dayStartHour by remember(settings.dayStartHour) { mutableFloatStateOf(settings.dayStartHour.toFloat()) }
    var weekendStart by remember(settings.weekendStart) { mutableIntStateOf(settings.weekendStart) }
    var weekendEnd by remember(settings.weekendEnd) { mutableIntStateOf(settings.weekendEnd) }

    // AI & Pro state
    var keyoActivationKey by remember { mutableStateOf("") }
    var isActivatingKeyo by remember { mutableStateOf(false) }
    var keyoResultMsg by remember { mutableStateOf<String?>(null) }
    var isTestingAi by remember { mutableStateOf(false) }
    var testResultMsg by remember { mutableStateOf<String?>(null) }
    var useByokState by remember(settings.useByok) { mutableStateOf(settings.useByok) }
    var activeModel by remember(settings.activeAiModel) { mutableStateOf(settings.activeAiModel) }
    var proStatus by remember { mutableStateOf<JSONObject?>(null) }
    var aiKeyMetadata by remember { mutableStateOf<JSONObject?>(null) }
    var isRefreshingAi by remember { mutableStateOf(false) }
    var aiStatusMessage by remember { mutableStateOf<String?>(null) }

    // Phone lock state is server-authoritative. Raw tokens are shown only once after creation.
    var phoneDevices by remember { mutableStateOf<List<PhoneDeviceInfo>>(emptyList()) }
    var newlyCreatedToken by remember { mutableStateOf<String?>(null) }
    var newlyCreatedDeviceId by remember { mutableStateOf<String?>(null) }
    var isGeneratingToken by remember { mutableStateOf(false) }
    var revokingDeviceId by remember { mutableStateOf<String?>(null) }
    var phoneErrorMessage by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(settings.authToken) {
        if (settings.authToken.isBlank()) {
            proStatus = null
            aiKeyMetadata = null
            phoneDevices = emptyList()
        } else {
            isRefreshingAi = true
            val statusResult = TaskitatorWorkerApi.getProStatusDetails(settings.authToken)
            statusResult.onSuccess { status ->
                proStatus = status
                val serverIsPro = status.optBoolean("isPro", false)
                val serverByok = status.optBoolean("use_byok", false)
                useByokState = serverByok
                if (settings.isPro != serverIsPro || settings.useByok != serverByok) {
                    viewModel.repository.updateSettings(settings.copy(isPro = serverIsPro, useByok = serverByok))
                }
            }.onFailure { aiStatusMessage = it.message ?: "Unable to refresh Pro status." }
            val keyResult = TaskitatorWorkerApi.getAiKeysMetadataDetails(settings.authToken)
            keyResult.onSuccess { aiKeyMetadata = it }
                .onFailure { aiStatusMessage = it.message ?: "Unable to read AI provider status." }
            val devicesResult = TaskitatorWorkerApi.getPhoneDevices(settings.authToken)
            devicesResult.onSuccess { phoneDevices = it }
                .onFailure { phoneErrorMessage = it.message ?: "Unable to read registered phones." }
            isRefreshingAi = false
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp)
    ) {
        Spacer(modifier = Modifier.height(12.dp))

        Text(
            text = "Settings",
            style = MaterialTheme.typography.headlineMedium,
            color = TextPrimary,
            fontWeight = FontWeight.ExtraBold
        )

        Spacer(modifier = Modifier.height(10.dp))

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = {
                chosenLanguage = if (chosenLanguage == "en") "ar" else "en"
                viewModel.repository.updateSettings(settings.copy(language = chosenLanguage))
            }) { Text(if (chosenLanguage == "en") "العربية / RTL" else "English / LTR") }
            OutlinedButton(onClick = {
                chosenTheme = if (chosenTheme == "midnight") "nature" else "midnight"
                viewModel.repository.updateSettings(settings.copy(appearanceTheme = chosenTheme))
            }) { Text(if (chosenTheme == "nature") "Nature (preview)" else "Midnight") }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Task sorting:", modifier = Modifier.padding(top = 10.dp))
            listOf("priority", "alphabetical", "required_first").forEach { mode ->
                TextButton(onClick = { viewModel.repository.updateSettings(settings.copy(taskSortMode = mode)) }) {
                    Text(if (mode == settings.taskSortMode) "✓ ${mode.replace('_', ' ')}" else mode.replace('_', ' '))
                }
            }
        }

        // 6 Tabs per Plan v5 Section 9.4
        ScrollableTabRow(
            selectedTabIndex = selectedTab,
            containerColor = DarkSurfaceVariant,
            contentColor = ElectricBlue,
            edgePadding = 0.dp
        ) {
            tabTitles.forEachIndexed { index, title ->
                Tab(
                    selected = selectedTab == index,
                    onClick = { selectedTab = index },
                    text = { Text(title, fontSize = 11.sp, fontWeight = FontWeight.Bold) }
                )
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
        ) {
            when (selectedTab) {
                0 -> {
                    // TAB 0: General
                    Card(
                        shape = RoundedCornerShape(12.dp),
                        colors = CardDefaults.cardColors(containerColor = DarkSurface),
                        border = androidx.compose.foundation.BorderStroke(1.dp, DarkBorder),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text("Focus & Experience", style = MaterialTheme.typography.titleMedium, color = TextPrimary, fontWeight = FontWeight.Bold)
                            Spacer(modifier = Modifier.height(12.dp))

                            OutlinedTextField(
                                value = copilotNameInput,
                                onValueChange = {
                                    if (it.length <= 20) {
                                        copilotNameInput = it
                                        viewModel.repository.updateSettings(settings.copy(copilotName = it))
                                    }
                                },
                                label = { Text("Assistant Name (max 20 chars)") },
                                singleLine = true,
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedBorderColor = ElectricBlue,
                                    unfocusedBorderColor = DarkBorder,
                                    focusedTextColor = TextPrimary,
                                    unfocusedTextColor = TextPrimary
                                ),
                                modifier = Modifier.fillMaxWidth()
                            )

                            Spacer(modifier = Modifier.height(14.dp))

                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        soundEffects = !soundEffects
                                        viewModel.repository.updateSettings(settings.copy(soundEffectsEnabled = soundEffects))
                                    },
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text("Sound Effects", color = TextPrimary, style = MaterialTheme.typography.bodyMedium)
                                Switch(
                                    checked = soundEffects,
                                    onCheckedChange = {
                                        soundEffects = it
                                        viewModel.repository.updateSettings(settings.copy(soundEffectsEnabled = it))
                                    }
                                )
                            }

                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        haptics = !haptics
                                        viewModel.repository.updateSettings(settings.copy(hapticsEnabled = haptics))
                                    },
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text("Haptic Feedback", color = TextPrimary, style = MaterialTheme.typography.bodyMedium)
                                Switch(
                                    checked = haptics,
                                    onCheckedChange = {
                                        haptics = it
                                        viewModel.repository.updateSettings(settings.copy(hapticsEnabled = it))
                                    }
                                )
                            }

                            Spacer(modifier = Modifier.height(14.dp))

                            Text(
                                text = "Distraction Dump Timer: ${dumpDuration.toInt()} seconds",
                                style = MaterialTheme.typography.labelMedium,
                                color = TextPrimary,
                                fontWeight = FontWeight.SemiBold
                            )
                            Slider(
                                value = dumpDuration,
                                onValueChange = {
                                    val stepped = (Math.round(it / 15f) * 15).coerceIn(30, 300)
                                    dumpDuration = stepped.toFloat()
                                    viewModel.repository.updateSettings(settings.copy(dumpDurationSeconds = stepped))
                                },
                                valueRange = 30f..300f,
                                steps = 17,
                                colors = SliderDefaults.colors(thumbColor = CyanAccent, activeTrackColor = CyanAccent)
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(14.dp))

                    // Cloudflare Worker Sync Card
                    Card(
                        shape = RoundedCornerShape(12.dp),
                        colors = CardDefaults.cardColors(containerColor = DarkSurface),
                        border = androidx.compose.foundation.BorderStroke(1.dp, DarkBorder),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text("Cloudflare Worker Synchronization", style = MaterialTheme.typography.titleMedium, color = TextPrimary, fontWeight = FontWeight.Bold)
                            Spacer(modifier = Modifier.height(4.dp))
                            Text("Worker Gateway: ${TaskitatorWorkerApi.WORKER_BASE_URL}", fontSize = 11.sp, color = TextMuted)
                            Spacer(modifier = Modifier.height(10.dp))

                            Button(
                                onClick = {
                                    isSyncing = true
                                    syncStatusMessage = null
                                    scope.launch {
                                        val (ok, msg) = viewModel.repository.performSync()
                                        isSyncing = false
                                        syncStatusMessage = msg
                                    }
                                },
                                enabled = !isSyncing,
                                colors = ButtonDefaults.buttonColors(containerColor = ElectricBlue),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(if (isSyncing) "Syncing with Worker..." else "Sync Now", fontWeight = FontWeight.Bold)
                            }

                            if (syncStatusMessage != null) {
                                Spacer(modifier = Modifier.height(8.dp))
                                Text(syncStatusMessage!!, fontSize = 11.sp, color = CyanAccent)
                            }
                        }
                    }
                }

                1 -> {
                    // TAB 1: Tasks
                    Card(
                        shape = RoundedCornerShape(12.dp),
                        colors = CardDefaults.cardColors(containerColor = DarkSurface),
                        border = androidx.compose.foundation.BorderStroke(1.dp, DarkBorder),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text("Criteria & Protection Rules", style = MaterialTheme.typography.titleMedium, color = TextPrimary, fontWeight = FontWeight.Bold)
                            Spacer(modifier = Modifier.height(8.dp))

                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        strictValidation = !strictValidation
                                        viewModel.repository.updateSettings(settings.copy(strictCriteriaValidation = strictValidation))
                                    },
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text("Strict Criteria Validation", color = TextPrimary, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                                    Text("Ordinary AI-locked tasks must achieve score >= 7/10 before saving.", color = TextMuted, fontSize = 11.sp)
                                }
                                Switch(
                                    checked = strictValidation,
                                    onCheckedChange = {
                                        strictValidation = it
                                        viewModel.repository.updateSettings(settings.copy(strictCriteriaValidation = it))
                                    }
                                )
                            }

                            Spacer(modifier = Modifier.height(16.dp))

                            Text("Custom Tags", style = MaterialTheme.typography.titleSmall, color = TextPrimary, fontWeight = FontWeight.Bold)
                            Spacer(modifier = Modifier.height(6.dp))

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                OutlinedTextField(
                                    value = newTagInput,
                                    onValueChange = { newTagInput = it },
                                    placeholder = { Text("Add tag...") },
                                    singleLine = true,
                                    colors = OutlinedTextFieldDefaults.colors(
                                        focusedBorderColor = CyanAccent,
                                        unfocusedBorderColor = DarkBorder,
                                        focusedTextColor = TextPrimary,
                                        unfocusedTextColor = TextPrimary
                                    ),
                                    modifier = Modifier.weight(1f)
                                )
                                Button(
                                    onClick = {
                                        if (newTagInput.isNotBlank()) {
                                            val current = settings.customTags.toMutableList()
                                            val t = newTagInput.trim().lowercase()
                                            if (t !in current) current.add(t)
                                            viewModel.repository.updateSettings(settings.copy(customTags = current))
                                            newTagInput = ""
                                        }
                                    },
                                    colors = ButtonDefaults.buttonColors(containerColor = CyanAccent)
                                ) {
                                    Text("Add", color = DarkBackground, fontWeight = FontWeight.Bold)
                                }
                            }

                            Spacer(modifier = Modifier.height(8.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                settings.customTags.forEach { tag ->
                                    Surface(
                                        shape = RoundedCornerShape(12.dp),
                                        color = DarkSurfaceVariant,
                                        border = androidx.compose.foundation.BorderStroke(0.5.dp, DarkBorder)
                                    ) {
                                        Row(
                                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                                        ) {
                                            Text("#$tag", color = TextPrimary, fontSize = 11.sp)
                                            Text(
                                                "✕",
                                                color = TextMuted,
                                                fontSize = 10.sp,
                                                modifier = Modifier.clickable {
                                                    val current = settings.customTags.toMutableList()
                                                    current.remove(tag)
                                                    viewModel.repository.updateSettings(settings.copy(customTags = current))
                                                }
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                2 -> {
                    // TAB 2: Day & week
                    Card(
                        shape = RoundedCornerShape(12.dp),
                        colors = CardDefaults.cardColors(containerColor = DarkSurface),
                        border = androidx.compose.foundation.BorderStroke(1.dp, DarkBorder),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text("Schedule & Calendar Cycle", style = MaterialTheme.typography.titleMedium, color = TextPrimary, fontWeight = FontWeight.Bold)
                            Spacer(modifier = Modifier.height(12.dp))

                            Text(
                                text = "Logical Day Start: ${dayStartHour.toInt()}:00 AM",
                                style = MaterialTheme.typography.labelMedium,
                                color = TextPrimary,
                                fontWeight = FontWeight.SemiBold
                            )
                            Text("Tasks due 'today' remain active until this hour tomorrow morning (0..4 AM).", color = TextMuted, fontSize = 11.sp)
                            Slider(
                                value = dayStartHour,
                                onValueChange = {
                                    val h = it.toInt().coerceIn(0, 4)
                                    dayStartHour = h.toFloat()
                                    viewModel.repository.updateSettings(settings.copy(dayStartHour = h))
                                },
                                valueRange = 0f..4f,
                                steps = 3,
                                colors = SliderDefaults.colors(thumbColor = ElectricBlue, activeTrackColor = ElectricBlue)
                            )

                            Spacer(modifier = Modifier.height(14.dp))

                            Text("Break & Schedule Timezone", style = MaterialTheme.typography.labelMedium, color = TextPrimary, fontWeight = FontWeight.SemiBold)
                            Text("App Timezone: ${settings.breakTimezone} (Server sync uses Riyadh UTC+3)", color = TextMuted, fontSize = 11.sp)
                        }
                    }
                }

                3 -> {
                    val serverIsPro = proStatus?.optBoolean("isPro", settings.isPro) ?: settings.isPro
                    val serverByok = proStatus?.optBoolean("use_byok", useByokState) ?: useByokState
                    val hostedMode = serverIsPro && !serverByok
                    val providers = aiKeyMetadata?.optJSONObject("providers")
                    val selectedProvider = aiKeyMetadata?.optString("selected_provider")
                        ?.takeIf { it.isNotBlank() } ?: settings.activeProvider
                    val selectedMeta = providers?.optJSONObject(selectedProvider)
                    val modelArray = selectedMeta?.optJSONArray("models")
                    val providerModels = (0 until (modelArray?.length() ?: 0)).mapNotNull { modelArray?.optString(it) }
                    val credits = proStatus?.optJSONObject("credits")

                    Card(
                        shape = RoundedCornerShape(12.dp),
                        colors = CardDefaults.cardColors(containerColor = DarkSurface),
                        border = androidx.compose.foundation.BorderStroke(1.dp, DarkBorder),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text("Pro Entitlement & AI", style = MaterialTheme.typography.titleMedium, color = TextPrimary, fontWeight = FontWeight.Bold)
                                Surface(
                                    shape = RoundedCornerShape(8.dp),
                                    color = if (serverIsPro) SuccessGreen.copy(alpha = 0.2f) else DarkSurfaceVariant,
                                    border = androidx.compose.foundation.BorderStroke(1.dp, if (serverIsPro) SuccessGreen else DarkBorder)
                                ) {
                                    Text(
                                        text = if (serverIsPro) (proStatus?.optString("tier", "PRO")?.uppercase() ?: "PRO") + " ACTIVE" else "FREE ACCOUNT",
                                        color = if (serverIsPro) SuccessGreen else TextSecondary,
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold,
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                    )
                                }
                            }

                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    "Server entitlement: " + (proStatus?.optString("status", "not refreshed") ?: "not refreshed"),
                                    color = TextSecondary,
                                    fontSize = 11.sp,
                                    modifier = Modifier.weight(1f)
                                )
                                TextButton(
                                    onClick = {
                                        scope.launch {
                                            isRefreshingAi = true
                                            TaskitatorWorkerApi.getProStatusDetails(settings.authToken).onSuccess {
                                                proStatus = it
                                                val p = it.optBoolean("isPro", false)
                                                val b = it.optBoolean("use_byok", false)
                                                useByokState = b
                                                viewModel.repository.updateSettings(settings.copy(isPro = p, useByok = b))
                                            }.onFailure { aiStatusMessage = it.message }
                                            TaskitatorWorkerApi.getAiKeysMetadataDetails(settings.authToken).onSuccess { aiKeyMetadata = it }
                                            isRefreshingAi = false
                                        }
                                    },
                                    enabled = !isRefreshingAi && settings.authToken.isNotBlank()
                                ) { Text(if (isRefreshingAi) "Refreshing…" else "Refresh") }
                            }

                            if (credits != null) {
                                Spacer(modifier = Modifier.height(8.dp))
                                Surface(
                                    shape = RoundedCornerShape(10.dp),
                                    color = DarkSurfaceVariant,
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Column(Modifier.padding(12.dp)) {
                                        Text("Hosted AI credits", color = CyanAccent, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                                        Text(
                                            "Daily: " + credits.optString("daily_remaining", "—") + " / " + credits.optString("daily_limit", "—") + " remaining",
                                            color = TextPrimary,
                                            fontSize = 12.sp
                                        )
                                        Text(
                                            "Monthly: " + credits.optString("monthly_remaining", "—") + " / " + credits.optString("monthly_limit", "—") + " remaining",
                                            color = TextPrimary,
                                            fontSize = 12.sp
                                        )
                                        Text("Monthly cycle: 30 days · reset timezone: Asia/Riyadh", color = TextMuted, fontSize = 10.sp)
                                    }
                                }
                            } else if (!serverIsPro) {
                                Text(
                                    "Free-plan allowances are enforced by the Worker. Exact numeric limits are not exposed by the status endpoint.",
                                    color = TextMuted,
                                    fontSize = 11.sp
                                )
                            }

                            Spacer(modifier = Modifier.height(14.dp))
                            if (!serverIsPro) {
                                Text("Activate Keyo license", style = MaterialTheme.typography.labelMedium, color = TextPrimary, fontWeight = FontWeight.SemiBold)
                                Spacer(modifier = Modifier.height(4.dp))
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    OutlinedTextField(
                                        value = keyoActivationKey,
                                        onValueChange = { keyoActivationKey = it },
                                        placeholder = { Text("16-character license key") },
                                        singleLine = true,
                                        colors = OutlinedTextFieldDefaults.colors(
                                            focusedBorderColor = CyanAccent,
                                            unfocusedBorderColor = DarkBorder,
                                            focusedTextColor = TextPrimary,
                                            unfocusedTextColor = TextPrimary
                                        ),
                                        modifier = Modifier.weight(1f)
                                    )
                                    Button(
                                        onClick = {
                                            isActivatingKeyo = true
                                            keyoResultMsg = null
                                            scope.launch {
                                                val activated = TaskitatorWorkerApi.activateProKey(keyoActivationKey, settings.authToken)
                                                if (activated.isFailure) {
                                                    keyoResultMsg = activated.exceptionOrNull()?.message ?: "Activation failed."
                                                } else {
                                                    val refreshed = TaskitatorWorkerApi.getProStatusDetails(settings.authToken)
                                                    refreshed.fold(
                                                        onSuccess = { status ->
                                                            proStatus = status
                                                            val active = status.optBoolean("isPro", false)
                                                            val byok = status.optBoolean("use_byok", false)
                                                            if (active) {
                                                                viewModel.repository.updateSettings(settings.copy(isPro = true, useByok = byok))
                                                                useByokState = byok
                                                                keyoActivationKey = ""
                                                                keyoResultMsg = "Keyo entitlement verified by the Worker."
                                                            } else {
                                                                keyoResultMsg = "Activation was received, but the Worker does not report an active entitlement."
                                                            }
                                                        },
                                                        onFailure = { keyoResultMsg = it.message ?: "Could not verify the activated entitlement." }
                                                    )
                                                }
                                                isActivatingKeyo = false
                                            }
                                        },
                                        enabled = keyoActivationKey.isNotBlank() && !isActivatingKeyo && settings.authToken.isNotBlank(),
                                        colors = ButtonDefaults.buttonColors(containerColor = CyanAccent)
                                    ) {
                                        Text(if (isActivatingKeyo) "Checking…" else "Activate", color = DarkBackground, fontWeight = FontWeight.Bold)
                                    }
                                }
                                if (keyoResultMsg != null) Text(keyoResultMsg!!, fontSize = 11.sp, color = CyanAccent)
                            }

                            Spacer(modifier = Modifier.height(16.dp))
                            Text("AI provider vault", style = MaterialTheme.typography.labelMedium, color = TextPrimary, fontWeight = FontWeight.SemiBold)
                            listOf("gemini" to "Google Gemini", "claude" to "Anthropic Claude").forEach { (provider, label) ->
                                val meta = providers?.optJSONObject(provider)
                                val saved = meta?.optBoolean("saved", false) == true
                                val suffix = meta?.optString("suffix").orEmpty()
                                Row(
                                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(Modifier.weight(1f)) {
                                        Text(label, color = TextPrimary, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                                        Text(
                                            if (saved) "Saved in Worker vault" + if (suffix.isNotBlank()) " · ending " + suffix else "" else "No saved key",
                                            color = if (saved) SuccessGreen else TextMuted,
                                            fontSize = 10.sp
                                        )
                                    }
                                    if (saved && !hostedMode) {
                                        TextButton(
                                            onClick = {
                                                scope.launch {
                                                    isTestingAi = true
                                                    testResultMsg = null
                                                    TaskitatorWorkerApi.testStoredKey(provider, settings.authToken).fold(
                                                        onSuccess = { pair -> testResultMsg = pair.second },
                                                        onFailure = { testResultMsg = it.message ?: "Key test failed." }
                                                    )
                                                    isTestingAi = false
                                                }
                                            },
                                            enabled = !isTestingAi
                                        ) { Text(if (isTestingAi) "Testing…" else "Test") }
                                    }
                                }
                            }

                            if (testResultMsg != null) {
                                Text(testResultMsg!!, color = CyanAccent, fontSize = 11.sp)
                            }

                            Spacer(modifier = Modifier.height(14.dp))
                            Text("Selected provider", style = MaterialTheme.typography.labelMedium, color = TextPrimary, fontWeight = FontWeight.SemiBold)
                            if (hostedMode) {
                                Text("Hosted Pro AI is active. Provider and model routing are controlled by Taskitator.", color = TextSecondary, fontSize = 11.sp)
                            } else {
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                                    listOf("gemini" to "Gemini", "claude" to "Claude").forEach { (provider, label) ->
                                        val saved = providers?.optJSONObject(provider)?.optBoolean("saved", false) == true
                                        val selected = selectedProvider == provider
                                        OutlinedButton(
                                            onClick = {
                                                scope.launch {
                                                    val result = TaskitatorWorkerApi.setAiProvider(provider, settings.authToken)
                                                    result.fold(
                                                        onSuccess = { ok ->
                                                            if (ok) {
                                                                val firstModel = providers?.optJSONObject(provider)?.optJSONArray("models")?.optString(0).orEmpty()
                                                                viewModel.repository.updateSettings(settings.copy(activeProvider = provider, activeAiModel = firstModel))
                                                                activeModel = firstModel
                                                                TaskitatorWorkerApi.getAiKeysMetadataDetails(settings.authToken).onSuccess { aiKeyMetadata = it }
                                                                aiStatusMessage = null
                                                            } else aiStatusMessage = "The Worker did not select this provider."
                                                        },
                                                        onFailure = { aiStatusMessage = it.message ?: "Provider selection failed." }
                                                    )
                                                }
                                            },
                                            enabled = saved && !isRefreshingAi,
                                            colors = ButtonDefaults.outlinedButtonColors(contentColor = if (selected) ElectricBlue else TextSecondary),
                                            modifier = Modifier.weight(1f)
                                        ) {
                                            Text(if (selected) "✓ " + label else label)
                                        }
                                    }
                                }

                                if (providerModels.isNotEmpty()) {
                                    Spacer(modifier = Modifier.height(8.dp))
                                    Text("Model", color = TextSecondary, fontSize = 11.sp)
                                    providerModels.forEach { model ->
                                        Row(
                                            modifier = Modifier.fillMaxWidth().clickable {
                                                activeModel = model
                                                viewModel.repository.updateSettings(settings.copy(activeAiModel = model))
                                            }.padding(vertical = 5.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            RadioButton(
                                                selected = activeModel == model,
                                                onClick = {
                                                    activeModel = model
                                                    viewModel.repository.updateSettings(settings.copy(activeAiModel = model))
                                                }
                                            )
                                            Text(model, color = TextPrimary, fontSize = 12.sp)
                                        }
                                    }
                                }
                            }

                            Spacer(modifier = Modifier.height(14.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text("Use Personal BYOK Key", color = TextPrimary, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                                    Text(
                                        if (serverIsPro) "Switch between hosted Pro credits and your own saved provider key." else "Free accounts use a saved personal provider key.",
                                        color = TextMuted,
                                        fontSize = 11.sp
                                    )
                                }
                                Switch(
                                    checked = serverByok,
                                    enabled = serverIsPro && !isRefreshingAi,
                                    onCheckedChange = { newValue ->
                                        scope.launch {
                                            val result = TaskitatorWorkerApi.toggleUseByok(newValue, settings.authToken)
                                            result.fold(
                                                onSuccess = { ok ->
                                                    if (ok) {
                                                        useByokState = newValue
                                                        viewModel.repository.updateSettings(settings.copy(useByok = newValue))
                                                        TaskitatorWorkerApi.getProStatusDetails(settings.authToken).onSuccess { proStatus = it }
                                                    } else aiStatusMessage = "The Worker rejected the BYOK preference change."
                                                },
                                                onFailure = { aiStatusMessage = it.message ?: "Could not change BYOK mode." }
                                            )
                                        }
                                    }
                                )
                            }

                            if (aiStatusMessage != null) {
                                Spacer(modifier = Modifier.height(6.dp))
                                Text(aiStatusMessage!!, color = PriorityP1, fontSize = 11.sp)
                            }

                            Spacer(modifier = Modifier.height(12.dp))
                            Surface(
                                shape = RoundedCornerShape(10.dp),
                                color = DarkSurfaceVariant,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(modifier = Modifier.padding(12.dp)) {
                                    Text("Vault security", style = MaterialTheme.typography.labelMedium, color = CyanAccent, fontWeight = FontWeight.Bold)
                                    Text(
                                        "API keys remain in the encrypted Worker KeyVault and are never stored in this app. Saving or replacing a key requires the website's verified account flow.",
                                        fontSize = 11.sp,
                                        color = TextSecondary
                                    )
                                    Spacer(modifier = Modifier.height(8.dp))
                                    Button(
                                        onClick = {
                                            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://taskitator.mazendev.com")))
                                        },
                                        colors = ButtonDefaults.buttonColors(containerColor = CyanAccent),
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Text("Manage AI keys securely on website", color = DarkBackground, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                                    }
                                }
                            }
                        }
                    }
                }

                4 -> {
                    Card(
                        shape = RoundedCornerShape(12.dp),
                        colors = CardDefaults.cardColors(containerColor = DarkSurface),
                        border = androidx.compose.foundation.BorderStroke(1.dp, DarkBorder),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text("MacroDroid Phone Lock Integration", style = MaterialTheme.typography.titleMedium, color = TextPrimary, fontWeight = FontWeight.Bold)
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(
                                "MacroDroid should request the Worker status endpoint using the phone token in an Authorization header. Never put the token in the URL.",
                                fontSize = 11.sp,
                                color = TextSecondary
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Surface(
                                color = DarkSurfaceVariant,
                                shape = RoundedCornerShape(8.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(Modifier.padding(10.dp)) {
                                    Text("GET https://taskitator-sync.spacexmzez.workers.dev/status?format=text", color = TextPrimary, fontSize = 11.sp)
                                    Text("Header: Authorization: Bearer <PHONE_TOKEN>", color = CyanAccent, fontSize = 11.sp)
                                    Text("Response: device_unlocked = true|false", color = TextSecondary, fontSize = 11.sp)
                                }
                            }

                            Spacer(modifier = Modifier.height(14.dp))
                            Button(
                                onClick = {
                                    isGeneratingToken = true
                                    phoneErrorMessage = null
                                    scope.launch {
                                        TaskitatorWorkerApi.createPhoneDevice(settings.authToken, "Android " + Build.MODEL).fold(
                                            onSuccess = { created ->
                                                newlyCreatedToken = created.token
                                                newlyCreatedDeviceId = created.device.id
                                                TaskitatorWorkerApi.getPhoneDevices(settings.authToken).onSuccess { phoneDevices = it }
                                            },
                                            onFailure = { phoneErrorMessage = it.message ?: "Failed to generate a phone token." }
                                        )
                                        isGeneratingToken = false
                                    }
                                },
                                enabled = !isGeneratingToken && settings.authToken.isNotBlank(),
                                colors = ButtonDefaults.buttonColors(containerColor = ElectricBlue),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(if (isGeneratingToken) "Generating…" else "Register this phone", fontWeight = FontWeight.Bold)
                            }

                            if (newlyCreatedToken != null) {
                                Spacer(modifier = Modifier.height(10.dp))
                                Surface(
                                    shape = RoundedCornerShape(8.dp),
                                    color = WarningAmber.copy(alpha = 0.15f),
                                    border = androidx.compose.foundation.BorderStroke(1.dp, WarningAmber),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Column(modifier = Modifier.padding(10.dp)) {
                                        Text("Copy this token now. The Worker will not reveal it again.", color = WarningAmber, fontWeight = FontWeight.Bold, fontSize = 11.sp)
                                        Spacer(modifier = Modifier.height(6.dp))
                                        Text(newlyCreatedToken!!, color = TextPrimary, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                                        Spacer(modifier = Modifier.height(6.dp))
                                        Button(
                                            onClick = {
                                                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                                clipboard.setPrimaryClip(ClipData.newPlainText("phone_token", newlyCreatedToken))
                                                Toast.makeText(context, "Copied token", Toast.LENGTH_SHORT).show()
                                            },
                                            colors = ButtonDefaults.buttonColors(containerColor = WarningAmber),
                                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                                        ) {
                                            Text("Copy token", color = DarkBackground, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                        }
                                        TextButton(onClick = { newlyCreatedToken = null; newlyCreatedDeviceId = null }) {
                                            Text("Hide token", color = TextSecondary)
                                        }
                                    }
                                }
                            }

                            Spacer(modifier = Modifier.height(14.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text("Registered phones", color = TextPrimary, fontWeight = FontWeight.SemiBold)
                                TextButton(
                                    onClick = {
                                        scope.launch {
                                            TaskitatorWorkerApi.getPhoneDevices(settings.authToken).onSuccess { phoneDevices = it }
                                                .onFailure { phoneErrorMessage = it.message }
                                        }
                                    },
                                    enabled = settings.authToken.isNotBlank()
                                ) { Text("Refresh") }
                            }
                            if (phoneDevices.isEmpty()) {
                                Text("No active phone tokens are registered.", color = TextMuted, fontSize = 11.sp)
                            } else {
                                phoneDevices.forEach { device ->
                                    Row(
                                        modifier = Modifier.fillMaxWidth().padding(vertical = 5.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Column(Modifier.weight(1f)) {
                                            Text(device.label, color = TextPrimary, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                                            Text(device.status + if (device.createdAt.isNotBlank()) " · " + device.createdAt else "", color = TextMuted, fontSize = 10.sp)
                                        }
                                        TextButton(
                                            onClick = {
                                                revokingDeviceId = device.id
                                                scope.launch {
                                                    TaskitatorWorkerApi.revokePhoneDevice(device.id, settings.authToken).fold(
                                                        onSuccess = { revoked ->
                                                            if (revoked) {
                                                                phoneDevices = phoneDevices.filterNot { it.id == device.id }
                                                                if (newlyCreatedDeviceId == device.id) {
                                                                    newlyCreatedToken = null
                                                                    newlyCreatedDeviceId = null
                                                                }
                                                            } else phoneErrorMessage = "The Worker did not revoke this phone token."
                                                        },
                                                        onFailure = { phoneErrorMessage = it.message ?: "Could not revoke phone token." }
                                                    )
                                                    revokingDeviceId = null
                                                }
                                            },
                                            enabled = revokingDeviceId == null && device.status == "active"
                                        ) {
                                            Text(if (revokingDeviceId == device.id) "Revoking…" else "Revoke", color = PriorityP1)
                                        }
                                    }
                                }
                            }

                            if (phoneErrorMessage != null) {
                                Spacer(modifier = Modifier.height(8.dp))
                                Text(phoneErrorMessage!!, color = PriorityP1, fontSize = 11.sp)
                            }
                        }
                    }
                }

                5 -> {
                    // TAB 5: Account & Data
                    Card(
                        shape = RoundedCornerShape(12.dp),
                        colors = CardDefaults.cardColors(containerColor = DarkSurface),
                        border = androidx.compose.foundation.BorderStroke(1.dp, DarkBorder),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text("Clerk Account & Cloud Data", style = MaterialTheme.typography.titleMedium, color = TextPrimary, fontWeight = FontWeight.Bold)
                            Spacer(modifier = Modifier.height(10.dp))

                            when (authState) {
                                is com.example.data.model.AuthState.Authenticated -> {
                                    Surface(
                                        shape = RoundedCornerShape(8.dp),
                                        color = SuccessGreen.copy(alpha = 0.12f),
                                        border = androidx.compose.foundation.BorderStroke(1.dp, SuccessGreen.copy(alpha = 0.4f)),
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Column(modifier = Modifier.padding(12.dp)) {
                                            Text("✓ Connected Cloud Account", color = SuccessGreen, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                                            Spacer(modifier = Modifier.height(4.dp))
                                            Text("User: ${settings.username}", color = TextPrimary, fontWeight = FontWeight.SemiBold)
                                            Text("Cloud User ID: ${settings.workerUser}", color = TextSecondary, fontSize = 11.sp)
                                            Text("Remote Snapshot: v${settings.remoteVersion}", color = TextMuted, fontSize = 11.sp)
                                        }
                                    }

                                    Spacer(modifier = Modifier.height(12.dp))

                                    Button(
                                        onClick = { viewModel.setAuthDialogVisible(true) },
                                        colors = ButtonDefaults.buttonColors(containerColor = ElectricBlue),
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Text("Manage Session / Switch Account", fontWeight = FontWeight.Bold)
                                    }

                                    Spacer(modifier = Modifier.height(8.dp))

                                    OutlinedButton(
                                        onClick = { viewModel.signOut() },
                                        colors = ButtonDefaults.outlinedButtonColors(contentColor = PriorityP1),
                                        border = androidx.compose.foundation.BorderStroke(1.dp, PriorityP1.copy(alpha = 0.5f)),
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Text("Sign Out", fontWeight = FontWeight.Bold)
                                    }
                                }
                                else -> {
                                    Surface(
                                        shape = RoundedCornerShape(8.dp),
                                        color = DarkSurfaceVariant,
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Column(modifier = Modifier.padding(12.dp)) {
                                            Text("☁️ Cloud Sync Disabled", color = TextSecondary, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                                            Spacer(modifier = Modifier.height(4.dp))
                                            Text("Sign in with your Clerk account to link with the website.", color = TextMuted, fontSize = 11.sp)
                                        }
                                    }

                                    Spacer(modifier = Modifier.height(12.dp))

                                    Button(
                                        onClick = { viewModel.setAuthDialogVisible(true) },
                                        colors = ButtonDefaults.buttonColors(containerColor = CyanAccent),
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Text("Sign In with Clerk", color = DarkBackground, fontWeight = FontWeight.Bold)
                                    }
                                }
                            }

                            Spacer(modifier = Modifier.height(16.dp))
                            HorizontalDivider(color = DarkBorder, thickness = 0.5.dp)
                            Spacer(modifier = Modifier.height(16.dp))

                            Text("Local Safety Backups", style = MaterialTheme.typography.titleSmall, color = TextPrimary, fontWeight = FontWeight.Bold)
                            Spacer(modifier = Modifier.height(8.dp))

                            OutlinedButton(
                                onClick = {
                                    val backupJson = viewModel.repository.exportConflictBackup()
                                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                    clipboard.setPrimaryClip(ClipData.newPlainText("conflict_backup", backupJson))
                                    Toast.makeText(context, "Conflict backup copied to clipboard", Toast.LENGTH_SHORT).show()
                                },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text("Export Conflict Backup JSON", fontSize = 12.sp)
                            }

                            Spacer(modifier = Modifier.height(16.dp))
                            HorizontalDivider(color = DarkBorder, thickness = 0.5.dp)
                            Spacer(modifier = Modifier.height(16.dp))

                            // Account Deletion per Google Play requirement
                            OutlinedButton(
                                onClick = {
                                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://taskitator.mazendev.com"))
                                    context.startActivity(intent)
                                },
                                colors = ButtonDefaults.outlinedButtonColors(contentColor = PriorityP1),
                                border = androidx.compose.foundation.BorderStroke(1.dp, PriorityP1),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text("Delete Account on Website (Custom Tab)", fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            }
        }
    }
}
