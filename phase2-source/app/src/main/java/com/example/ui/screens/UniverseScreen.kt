package com.example.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.app.DatePickerDialog
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Base64
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.network.TaskitatorWorkerApi
import com.example.ui.theme.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Calendar
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private data class UniverseUploadFile(val name: String, val mime: String, val bytes: ByteArray, val hash: String)
private data class UniversePendingDownload(val name: String, val mime: String, val bytes: ByteArray)

private fun JSONArray.toObjectList(): List<JSONObject> =
    (0 until length()).mapNotNull { optJSONObject(it) }

private fun JSONArray.containsString(value: String): Boolean =
    (0 until length()).any { optString(it) == value }

private fun JSONObject.optFlag(name: String, default: Boolean = false): Boolean =
    when (val value = opt(name)) {
        is Boolean -> value
        is Number -> value.toInt() != 0
        is String -> value == "true" || value == "1"
        else -> default
    }

private fun operationBody(): JSONObject =
    JSONObject().put("operation_id", TaskitatorWorkerApi.newOperationId())

@Composable
fun UniverseScreen(
    authToken: String,
    isPro: Boolean,
    onStatus: (String) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var capabilities by remember { mutableStateOf<JSONObject?>(null) }
    var groups by remember { mutableStateOf<List<JSONObject>>(emptyList()) }
    var selectedGroupId by remember { mutableStateOf<String?>(null) }
    var group by remember { mutableStateOf<JSONObject?>(null) }
    var members by remember { mutableStateOf<List<JSONObject>>(emptyList()) }
    var projects by remember { mutableStateOf<List<JSONObject>>(emptyList()) }
    var tasks by remember { mutableStateOf<List<JSONObject>>(emptyList()) }
    var assignments by remember { mutableStateOf<List<JSONObject>>(emptyList()) }
    var comments by remember { mutableStateOf<List<JSONObject>>(emptyList()) }
    var activity by remember { mutableStateOf<List<JSONObject>>(emptyList()) }
    var locks by remember { mutableStateOf<List<JSONObject>>(emptyList()) }
    var groupSettings by remember { mutableStateOf<JSONObject?>(null) }
    var lockConsent by remember { mutableStateOf<JSONObject?>(null) }
    var currentUserId by remember { mutableStateOf("") }
    var currentTab by remember { mutableStateOf("Work") }
    var joinToken by remember { mutableStateOf("") }
    var createTitle by remember { mutableStateOf("") }
    var createPurpose by remember { mutableStateOf("") }
    var showCreateGroup by remember { mutableStateOf(false) }
    var showCreateProject by remember { mutableStateOf(false) }
    var newProjectTitle by remember { mutableStateOf("") }
    var showCreateTask by remember { mutableStateOf(false) }
    var newTaskTitle by remember { mutableStateOf("") }
    var newTaskCriteria by remember { mutableStateOf("") }
    var newTaskMode by remember { mutableStateOf("everyone") }
    var newTaskAiRequired by remember { mutableStateOf(false) }
    var criteriaValidation by remember { mutableStateOf<JSONObject?>(null) }
    var dueAtMillis by remember { mutableStateOf<Long?>(null) }
    var selectedProjectId by remember { mutableStateOf("") }
    var showInvite by remember { mutableStateOf(false) }
    var inviteRole by remember { mutableStateOf("member") }
    var inviteToken by remember { mutableStateOf<String?>(null) }
    var showSubmit by remember { mutableStateOf(false) }
    var submissionText by remember { mutableStateOf("") }
    var selectedAssignment by remember { mutableStateOf<JSONObject?>(null) }
    var selectedTask by remember { mutableStateOf<JSONObject?>(null) }
    var selectedAssignmentDetail by remember { mutableStateOf<JSONObject?>(null) }
    var submissionFiles by remember { mutableStateOf<List<UniverseUploadFile>>(emptyList()) }
    var showAssignmentDetail by remember { mutableStateOf(false) }
    var showReview by remember { mutableStateOf(false) }
    var reviewDecision by remember { mutableStateOf("approved") }
    var reviewReason by remember { mutableStateOf("") }
    var discussionDraft by remember { mutableStateOf("") }
    var aiQuote by remember { mutableStateOf<JSONObject?>(null) }
    var aiQuoteMode by remember { mutableStateOf("personal") }
    var aiQuoteAssignmentId by remember { mutableStateOf("") }
    var aiQuoteTaskId by remember { mutableStateOf("") }
    var aiResultMessage by remember { mutableStateOf<String?>(null) }
    var proUseByok by remember { mutableStateOf(false) }
    var serverIsPro by remember { mutableStateOf(isPro) }
    var pendingDownload by remember { mutableStateOf<UniversePendingDownload?>(null) }
    var downloadBusy by remember { mutableStateOf(false) }

    suspend fun call(path: String, method: String = "GET", body: JSONObject? = null): JSONObject? {
        val result = TaskitatorWorkerApi.requestJson(path, method, authToken, body, 90000)
        return result.fold(
            onSuccess = { it },
            onFailure = {
                error = it.message ?: "Universe request failed."
                null
            }
        )
    }

    suspend fun refreshGroups() {
        if (authToken.isBlank()) return
        loading = true
        error = null
        val caps = call("/universe/capabilities")
        if (caps != null) capabilities = caps
        if (caps?.optBoolean("ui", false) != true) {
            groups = emptyList()
            loading = false
            return
        }
        val result = call("/universe/groups")
        groups = result?.optJSONArray("groups")?.toObjectList() ?: emptyList()
        loading = false
    }

    suspend fun refreshGroup(id: String) {
        if (authToken.isBlank()) return
        loading = true
        error = null
        val base = "/universe/groups/" + id
        val groupResult = call(base)
        group = groupResult?.optJSONObject("group")
        val memberResult = call(base + "/members")
        members = memberResult?.optJSONArray("members")?.toObjectList() ?: emptyList()
        val workResult = call(base + "/work")
        projects = workResult?.optJSONArray("projects")?.toObjectList() ?: emptyList()
        tasks = workResult?.optJSONArray("tasks")?.toObjectList() ?: emptyList()
        assignments = workResult?.optJSONArray("assignments")?.toObjectList() ?: emptyList()
        val discussionResult = call(base + "/discussion")
        comments = discussionResult?.optJSONArray("comments")?.toObjectList() ?: emptyList()
        val activityResult = call(base + "/activity")
        activity = activityResult?.optJSONArray("events")?.toObjectList() ?: emptyList()
        groupSettings = call(base + "/settings")
        lockConsent = call(base + "/consent")?.optJSONObject("consent")
        locks = call(base + "/locks")?.optJSONArray("assignments")?.toObjectList() ?: emptyList()
        selectedProjectId = projects.firstOrNull()?.optString("id").orEmpty()
        loading = false
    }

    suspend fun currentUser() {
        val me = TaskitatorWorkerApi.getAuthMe(authToken).getOrNull()
        val account = me?.optJSONObject("account")
        currentUserId = account?.optString("user_id").orEmpty()
        val pro = TaskitatorWorkerApi.getProStatusDetails(authToken).getOrNull()
        serverIsPro = pro?.optBoolean("isPro", isPro) ?: isPro
        proUseByok = pro?.optBoolean("use_byok", false) ?: false
    }

    LaunchedEffect(authToken) {
        if (authToken.isNotBlank()) {
            currentUser()
            refreshGroups()
        }
    }
    LaunchedEffect(selectedGroupId) {
        val id = selectedGroupId
        if (!id.isNullOrBlank()) refreshGroup(id)
    }

    val groupId = selectedGroupId
    val groupRole = group?.optString("role").orEmpty()
    val groupRevision = group?.optInt("revision", 0) ?: 0
    val isManager = groupRole == "owner" || groupRole == "admin"
    val serverValidationRequired = groupSettings?.optFlag("validate_criteria", true) ?: true
    val base = if (groupId.isNullOrBlank()) "" else "/universe/groups/" + groupId

    val saveFileLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream")
    ) { uri ->
        val file = pendingDownload
        if (uri != null && file != null) {
            scope.launch {
                try {
                    withContext(Dispatchers.IO) {
                        val output = context.contentResolver.openOutputStream(uri)
                            ?: throw IllegalStateException("Unable to open the selected destination.")
                        output.use { it.write(file.bytes) }
                    }
                    onStatus("Saved " + file.name)
                } catch (e: Exception) {
                    error = e.message ?: "Unable to save the downloaded attachment."
                } finally {
                    pendingDownload = null
                    downloadBusy = false
                }
            }
        } else {
            pendingDownload = null
            downloadBusy = false
        }
    }

    val proofPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) {
            scope.launch {
                loading = true
                error = null
                try {
                    if (uris.size > 3) throw IllegalArgumentException("Universe submissions support up to three files.")
                    val loaded = mutableListOf<UniverseUploadFile>()
                    for (uri in uris) loaded += readUniverseFile(context, uri)
                    submissionFiles = loaded
                } catch (e: Exception) {
                    error = e.message ?: "Unable to read submission files."
                } finally {
                    loading = false
                }
            }
        }
    }

    fun downloadAttachment(fileId: String) {
        if (fileId.isBlank() || downloadBusy) return
        scope.launch {
            downloadBusy = true
            error = null
            val result = call(base + "/work/files/" + fileId)
            if (result != null) {
                try {
                    val encoded = result.optString("data")
                    if (encoded.isBlank()) throw IllegalStateException("The Worker returned no attachment bytes.")
                    val bytes = Base64.decode(encoded, Base64.DEFAULT)
                    if (bytes.isEmpty() || bytes.size > 5 * 1024 * 1024) {
                        throw IllegalStateException("The downloaded attachment is empty or exceeds the 5 MiB limit.")
                    }
                    val rawName = result.optString("name", "universe-attachment")
                    val safeName = rawName.substringAfterLast('/').substringAfterLast('\\')
                        .replace(Regex("[\\u0000-\\u001F]"), "_").take(120)
                        .ifBlank { "universe-attachment" }
                    pendingDownload = UniversePendingDownload(
                        safeName,
                        result.optString("mime", "application/octet-stream"),
                        bytes
                    )
                    saveFileLauncher.launch(safeName)
                } catch (e: Exception) {
                    error = e.message ?: "Unable to prepare the attachment."
                    downloadBusy = false
                    pendingDownload = null
                }
            } else {
                downloadBusy = false
            }
        }
    }

    fun postGroupAction(path: String, body: JSONObject, success: String) {
        scope.launch {
            loading = true
            call(path, "POST", body)?.let {
                onStatus(success)
                if (selectedGroupId != null) refreshGroup(selectedGroupId!!)
                else refreshGroups()
            }
            loading = false
        }
    }

    fun createUniverse() {
        val title = createTitle.trim()
        if (title.isBlank()) { error = "Enter a Universe name."; return }
        scope.launch {
            loading = true
            val body = operationBody().put("title", title).put("purpose", createPurpose.trim())
            val result = call("/universe/groups", "POST", body)
            val id = result?.optString("group_id").orEmpty()
            if (id.isNotBlank()) {
                showCreateGroup = false
                createTitle = ""
                createPurpose = ""
                refreshGroups()
                selectedGroupId = id
                onStatus("Universe created.")
            }
            loading = false
        }
    }

    fun acceptInvitation() {
        val token = joinToken.trim().lowercase()
        if (!token.matches(Regex("^[a-f0-9]{64}$"))) { error = "Enter the 64-character invitation token."; return }
        scope.launch {
            loading = true
            val result = call("/universe/invitations/accept", "POST", operationBody().put("token", token))
            val id = result?.optString("group_id").orEmpty()
            if (id.isNotBlank()) {
                joinToken = ""
                refreshGroups()
                selectedGroupId = id
                onStatus("Joined the Universe.")
            }
            loading = false
        }
    }

    fun createProject() {
        val title = newProjectTitle.trim()
        if (title.isBlank() || groupId == null) { error = "Enter a project name."; return }
        postGroupAction(base + "/work/projects", operationBody().put("title", title), "Project created.")
        showCreateProject = false
        newProjectTitle = ""
    }

    fun validateUniverseCriteria() {
        val title = newTaskTitle.trim()
        val criteria = newTaskCriteria.trim()
        if (groupId == null || title.isBlank() || criteria.isBlank()) {
            error = "Enter the task title and proof criteria before validation."
            return
        }
        scope.launch {
            loading = true
            error = null
            criteriaValidation = null
            try {
                val metadataResult = TaskitatorWorkerApi.getAiKeysMetadataDetails(authToken)
                if (metadataResult.isFailure) {
                    throw metadataResult.exceptionOrNull() ?: IllegalStateException("Unable to read AI provider settings.")
                }
                val metadata = metadataResult.getOrThrow()
                val body = operationBody()
                    .put("title", title)
                    .put("description", "")
                    .put("criteria", criteria)
                if (!metadata.optBoolean("hosted", false)) {
                    val provider = metadata.optString("selected_provider", "gemini")
                    val providerMeta = metadata.optJSONObject("providers")?.optJSONObject(provider)
                    if (providerMeta == null || !providerMeta.optBoolean("saved", false)) {
                        throw IllegalStateException("No saved key is available for the selected AI provider.")
                    }
                    val models = providerMeta.optJSONArray("models")
                    val model = models?.optString(0).orEmpty()
                    val version = providerMeta.optString("version")
                    if (model.isBlank() || version.isBlank()) {
                        throw IllegalStateException("The selected AI provider configuration is incomplete. Refresh AI settings.")
                    }
                    body.put("provider", provider)
                    body.put("model", model)
                    body.put("key_version", version)
                }
                val result = call(base + "/validate", "POST", body)
                if (result != null) {
                    criteriaValidation = result
                    if (result.optBoolean("passed", result.optInt("score", 0) >= 7)) {
                        onStatus("Universe criteria passed validation at " + result.optInt("score") + "/10.")
                    } else {
                        error = result.optString("critique", "Criteria did not reach the required 7/10 score.")
                    }
                }
            } catch (e: Exception) {
                error = e.message ?: "Criteria validation failed."
            } finally {
                loading = false
            }
        }
    }

    fun createTask() {
        val title = newTaskTitle.trim()
        val criteria = newTaskCriteria.trim()
        val projectId = selectedProjectId.ifBlank { projects.firstOrNull()?.optString("id").orEmpty() }
        if (title.isBlank() || criteria.isBlank() || projectId.isBlank() || groupId == null) {
            error = "Choose a project and provide a title and verifiable criteria."
            return
        }
        if (newTaskAiRequired && serverValidationRequired && criteriaValidation?.optBoolean("passed", false) != true) {
            error = "Validate the current title and criteria at 7/10 or higher before creating this AI-reviewed task."
            return
        }
        val memberIds = members.map { it.optString("account_id") }.filter { it.isNotBlank() }
        if (memberIds.isEmpty()) { error = "The Universe has no active members."; return }
        val body = operationBody().apply {
            put("revision", groupRevision)
            put("project_id", projectId)
            put("title", title)
            put("criteria", criteria)
            put("mode", newTaskMode)
            put("members", JSONArray(memberIds))
            put("reviewers", JSONArray(members.filter { it.optString("role") == "reviewer" }.map { it.optString("account_id") }))
            put("audit_mode", if (newTaskAiRequired) "both" else "human")
            put("allow_late", false)
            if (newTaskAiRequired && serverValidationRequired) {
                put("validation_id", criteriaValidation?.optString("id").orEmpty())
            }
            dueAtMillis?.let { put("due_at", it) }
        }
        postGroupAction(base + "/work/tasks", body, "Universe task created.")
        showCreateTask = false
        newTaskTitle = ""
        newTaskCriteria = ""
        newTaskMode = "everyone"
        newTaskAiRequired = false
        criteriaValidation = null
        dueAtMillis = null
    }

    fun createInvitation() {
        if (groupId == null) return
        val bytes = ByteArray(32)
        SecureRandom().nextBytes(bytes)
        val rawToken = bytes.joinToString("") { "%02x".format(it) }
        val body = operationBody().put("token", rawToken).put("role", inviteRole).put("expires_in_seconds", 86400)
        scope.launch {
            loading = true
            val result = call(base + "/invitations", "POST", body)
            if (result != null) {
                inviteToken = rawToken
                showInvite = false
                onStatus("Invitation created. Copy its token; it is not stored in the Worker in readable form.")
            }
            loading = false
        }
    }

    fun openAssignment(assignment: JSONObject, task: JSONObject) {
        selectedAssignment = assignment
        selectedTask = task
        scope.launch {
            val detail = call(base + "/work/assignments/" + assignment.optString("id"))
            selectedAssignmentDetail = detail
            showAssignmentDetail = true
        }
    }

    fun submitAssignment() {
        val assignment = selectedAssignment ?: return
        val task = selectedTask ?: return
        val aid = assignment.optString("id")
        val taskId = task.optString("id")
        val taskRevision = task.optInt("revision", 1)
        scope.launch {
            loading = true
            error = null
            val fileIds = mutableListOf<String>()
            for (file in submissionFiles) {
                val meta = JSONObject().apply {
                    put("operation_id", TaskitatorWorkerApi.newOperationId())
                    put("task_id", taskId)
                    put("assignment_id", aid)
                    put("kind", "proof")
                    put("name", file.name)
                    put("mime", file.mime)
                    put("size", file.bytes.size)
                    put("hash", file.hash)
                    put("task_revision", taskRevision)
                }
                val staged = call(base + "/work/files", "POST", meta) ?: break
                val fileId = staged.optString("id")
                if (fileId.isBlank()) { error = "The Worker did not return an upload ID."; break }
                val upload = JSONObject()
                    .put("operation_id", TaskitatorWorkerApi.newOperationId())
                    .put("data", Base64.encodeToString(file.bytes, Base64.NO_WRAP))
                if (call(base + "/work/files/" + fileId + "/upload", "POST", upload) == null) break
                fileIds += fileId
            }
            if (error == null && fileIds.size == submissionFiles.size) {
                val body = operationBody().apply {
                    put("revision", assignment.optInt("revision", 1))
                    put("task_revision", taskRevision)
                    put("text", submissionText.trim())
                    put("files", JSONArray(fileIds))
                }
                if (call(base + "/work/assignments/" + aid + "/submit", "POST", body) != null) {
                    showSubmit = false
                    showAssignmentDetail = false
                    submissionText = ""
                    submissionFiles = emptyList()
                    onStatus("Proof submitted to the Universe.")
                    refreshGroup(groupId!!)
                }
            }
            loading = false
        }
    }

    fun reviewAssignment() {
        val assignment = selectedAssignment ?: return
        val task = selectedTask ?: return
        val aid = assignment.optString("id")
        val body = operationBody().apply {
            put("revision", assignment.optInt("revision", 1))
            put("task_revision", task.optInt("revision", 1))
            put("decision", reviewDecision)
            put("reason", reviewReason.trim())
        }
        postGroupAction(base + "/work/assignments/" + aid + "/review", body, "Submission decision recorded.")
        showReview = false
        showAssignmentDetail = false
        reviewReason = ""
    }

    fun startUniverseAiReview(assignment: JSONObject, task: JSONObject) {
        val aid = assignment.optString("id")
        val mode = if (task.optString("mode") == "shared" || (serverIsPro && !proUseByok)) "hosted" else "personal"
        aiQuoteMode = mode
        aiQuoteAssignmentId = aid
        aiQuoteTaskId = task.optString("id")
        scope.launch {
            val quote = call(base + "/ai/" + aid + "/quote", "POST", operationBody().put("mode", mode))
            if (quote != null) aiQuote = quote
        }
    }

    fun approveUniverseAiReview() {
        val quote = aiQuote ?: return
        val reviewId = quote.optString("id")
        scope.launch {
            loading = true
            val result = call(
                base + "/ai/" + reviewId + "/approve",
                "POST",
                operationBody().put("accept_max_credits", quote.optDouble("max_credits", 0.0))
            )
            if (result != null) {
                aiResultMessage = result.optJSONObject("result")?.optString("verdict")
                    ?: result.optString("state", "Review submitted")
                aiQuote = null
                refreshGroup(groupId!!)
            }
            loading = false
        }
    }

    if (authToken.isBlank()) {
        Column(Modifier.fillMaxSize().padding(20.dp), verticalArrangement = Arrangement.Center) {
            Text("Connect your Taskitator account to use Universe.", color = TextPrimary, fontWeight = FontWeight.Bold)
            Text("Universe is server-authoritative and requires an authenticated account.", color = TextSecondary, fontSize = 12.sp)
        }
        return
    }

    Column(
        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 12.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text("Universe", style = MaterialTheme.typography.headlineMedium, color = TextPrimary, fontWeight = FontWeight.ExtraBold)
                Text("Shared workspaces · roles · evidence · discussion", color = TextSecondary, fontSize = 11.sp)
            }
            TextButton(
                onClick = {
                    scope.launch {
                        if (groupId == null) refreshGroups() else refreshGroup(groupId!!)
                    }
                },
                enabled = !loading
            ) { Text("Refresh") }
        }

        if (loading) LinearProgressIndicator(modifier = Modifier.fillMaxWidth(), color = ElectricBlue)
        if (error != null) {
            Surface(
                color = PriorityP1.copy(alpha = 0.12f),
                shape = RoundedCornerShape(8.dp),
                border = BorderStroke(1.dp, PriorityP1.copy(alpha = 0.4f)),
                modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)
            ) {
                Text(error!!, color = PriorityP1, fontSize = 11.sp, modifier = Modifier.padding(10.dp))
            }
        }

        val caps = capabilities
        if (caps != null && !caps.optBoolean("ui", false)) {
            Card(
                colors = CardDefaults.cardColors(containerColor = DarkSurface),
                border = BorderStroke(1.dp, DarkBorder),
                modifier = Modifier.fillMaxWidth().padding(top = 10.dp)
            ) {
                Column(Modifier.padding(16.dp)) {
                    Text("Universe service is not ready", color = TextPrimary, fontWeight = FontWeight.Bold)
                    Text("Worker capability: " + caps.optString("reason", "schema unavailable"), color = TextSecondary, fontSize = 12.sp)
                    Text("The app will not simulate shared workspaces while the server feature is unavailable.", color = TextMuted, fontSize = 11.sp)
                    Spacer(Modifier.height(8.dp))
                    Button(onClick = { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://taskitator.mazendev.com"))) }) {
                        Text("Open Taskitator website")
                    }
                }
            }
        } else if (groupId == null) {
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = { showCreateGroup = true },
                    enabled = serverIsPro && !loading,
                    modifier = Modifier.weight(1f)
                ) { Text("Create Universe") }
                OutlinedButton(
                    onClick = { scope.launch { acceptInvitation() } },
                    enabled = joinToken.trim().length == 64 && !loading,
                    modifier = Modifier.weight(1f)
                ) { Text("Join") }
            }
            OutlinedTextField(
                value = joinToken,
                onValueChange = { joinToken = it.trim().lowercase() },
                label = { Text("Invitation token") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
            )
            if (!serverIsPro) Text("Creating a Universe requires active Pro. You can still join an existing one.", color = TextMuted, fontSize = 11.sp)
            Spacer(Modifier.height(10.dp))
            if (groups.isEmpty()) {
                Text("No Universes are linked to this account.", color = TextSecondary, fontSize = 12.sp)
            } else {
                groups.forEach { item ->
                    Card(
                        colors = CardDefaults.cardColors(containerColor = DarkSurface),
                        border = BorderStroke(1.dp, DarkBorder),
                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp).clickable {
                            selectedGroupId = item.optString("id")
                            currentTab = "Work"
                        }
                    ) {
                        Column(Modifier.padding(14.dp)) {
                            Text(item.optString("title", "Universe"), color = TextPrimary, fontWeight = FontWeight.Bold)
                            Text(item.optString("purpose"), color = TextSecondary, fontSize = 11.sp)
                            Text("Role: " + item.optString("role") + " · Members: " + item.optString("member_count", "—"), color = CyanAccent, fontSize = 10.sp)
                            if (item.optBoolean("read_only", false)) Text("Read-only: owner Pro renewal required", color = WarningAmber, fontSize = 10.sp)
                        }
                    }
                }
            }
        } else {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(onClick = { selectedGroupId = null; group = null; scope.launch { refreshGroups() } }) { Text("← Universes") }
                Column(Modifier.weight(1f)) {
                    Text(group?.optString("title", "Universe") ?: "Universe", color = TextPrimary, fontWeight = FontWeight.Bold)
                    Text(groupRole.uppercase(), color = CyanAccent, fontSize = 10.sp)
                }
            }
            ScrollableTabRow(
                selectedTabIndex = listOf("Work", "People", "Discussion", "Controls").indexOf(currentTab).coerceAtLeast(0),
                containerColor = DarkSurfaceVariant,
                contentColor = ElectricBlue,
                edgePadding = 0.dp
            ) {
                listOf("Work", "People", "Discussion", "Controls").forEach { tab ->
                    Tab(selected = currentTab == tab, onClick = { currentTab = tab }, text = { Text(tab, fontSize = 11.sp) })
                }
            }

            Column(
                modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(top = 10.dp)
            ) {
                when (currentTab) {
                    "Work" -> {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("Projects & tasks", color = TextPrimary, fontWeight = FontWeight.Bold)
                            Row {
                                TextButton(onClick = { showCreateProject = true }, enabled = isManager) { Text("+ Project") }
                                TextButton(onClick = { showCreateTask = true }, enabled = isManager && projects.isNotEmpty()) { Text("+ Task") }
                            }
                        }
                        projects.forEach { project ->
                            Text("📁 " + project.optString("title"), color = CyanAccent, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                            tasks.filter { it.optString("project_id") == project.optString("id") }.forEach { task ->
                                Card(
                                    colors = CardDefaults.cardColors(containerColor = DarkSurface),
                                    border = BorderStroke(1.dp, DarkBorder),
                                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
                                ) {
                                    Column(Modifier.padding(12.dp)) {
                                        Text(task.optString("title"), color = TextPrimary, fontWeight = FontWeight.Bold)
                                        Text(task.optString("criteria"), color = TextSecondary, fontSize = 11.sp)
                                        Text(
                                            task.optString("mode") + " · " +
                                                (if (task.optBoolean("ai_required")) "AI review required" else "standard review") +
                                                " · revision " + task.optInt("revision", 1),
                                            color = TextMuted,
                                            fontSize = 10.sp
                                        )
                                        val taskAssignments = assignments.filter { it.optString("task_id") == task.optString("id") }
                                        taskAssignments.forEach { assignment ->
                                            val state = assignment.optString("state")
                                            val assignedTo = assignment.optString("account_id").ifBlank { "Shared assignment" }
                                            Row(
                                                modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                                                horizontalArrangement = Arrangement.SpaceBetween,
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                Column(Modifier.weight(1f)) {
                                                    Text(assignedTo.take(30), color = TextPrimary, fontSize = 10.sp)
                                                    Text(state.uppercase(), color = if (state == "approved") SuccessGreen else TextMuted, fontSize = 10.sp)
                                                }
                                                TextButton(onClick = { openAssignment(assignment, task) }) { Text(if (state == "pending") "Review" else "Open") }
                                                if (state in listOf("open", "rejected") &&
                                                    (assignment.optString("account_id") == currentUserId || assignment.isNull("account_id")) &&
                                                    (task.optString("mode") != "shared" || task.optJSONArray("members")?.containsString(currentUserId) == true)
                                                ) {
                                                    TextButton(onClick = {
                                                        selectedAssignment = assignment
                                                        selectedTask = task
                                                        submissionText = ""
                                                        submissionFiles = emptyList()
                                                        showSubmit = true
                                                    }) { Text("Submit") }
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                        if (projects.isEmpty()) Text("Create a project before adding Universe tasks.", color = TextMuted, fontSize = 11.sp)
                    }

                    "People" -> {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("Members", color = TextPrimary, fontWeight = FontWeight.Bold)
                            TextButton(onClick = { showInvite = true }, enabled = groupRole in listOf("owner", "admin")) { Text("Invite") }
                        }
                        members.forEach { member ->
                            val id = member.optString("account_id")
                            val role = member.optString("role")
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text(id.take(38), color = TextPrimary, fontSize = 11.sp)
                                    Text(role.uppercase(), color = CyanAccent, fontSize = 10.sp)
                                }
                                if (isManager && id != currentUserId && role != "owner") {
                                    TextButton(onClick = {
                                        val next = if (role == "member") "reviewer" else "member"
                                        postGroupAction(base + "/role", operationBody()
                                            .put("revision", groupRevision)
                                            .put("target_id", id)
                                            .put("role", next), "Member role updated.")
                                    }) { Text(if (role == "member") "Make reviewer" else "Make member") }
                                    TextButton(onClick = {
                                        postGroupAction(base + "/remove", operationBody()
                                            .put("revision", groupRevision)
                                            .put("target_id", id), "Member removed.")
                                    }) { Text("Remove", color = PriorityP1) }
                                }
                            }
                        }
                        if (inviteToken != null) {
                            Card(colors = CardDefaults.cardColors(containerColor = WarningAmber.copy(alpha = 0.12f)), modifier = Modifier.fillMaxWidth()) {
                                Column(Modifier.padding(12.dp)) {
                                    Text("Copy this invitation token now", color = WarningAmber, fontWeight = FontWeight.Bold, fontSize = 11.sp)
                                    Text(inviteToken!!, color = TextPrimary, fontSize = 10.sp)
                                    Button(onClick = {
                                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                        clipboard.setPrimaryClip(ClipData.newPlainText("universe_invitation", inviteToken))
                                        onStatus("Invitation token copied.")
                                    }) { Text("Copy invitation") }
                                }
                            }
                        }
                    }

                    "Discussion" -> {
                        Text("Discussion", color = TextPrimary, fontWeight = FontWeight.Bold)
                        OutlinedTextField(
                            value = discussionDraft,
                            onValueChange = { discussionDraft = it },
                            label = { Text("Write a message") },
                            modifier = Modifier.fillMaxWidth(),
                            minLines = 2
                        )
                        Button(
                            onClick = {
                                val text = discussionDraft.trim()
                                if (text.isNotBlank()) {
                                    postGroupAction(base + "/discussion", operationBody().put("body", text), "Discussion message posted.")
                                    discussionDraft = ""
                                }
                            },
                            enabled = discussionDraft.isNotBlank()
                        ) { Text("Post message") }
                        comments.forEach { comment ->
                            Card(
                                colors = CardDefaults.cardColors(containerColor = DarkSurface),
                                border = BorderStroke(0.5.dp, DarkBorder),
                                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
                            ) {
                                Column(Modifier.padding(10.dp)) {
                                    Text(comment.optString("actor_id").take(36), color = CyanAccent, fontSize = 10.sp)
                                    Text(comment.optString("body"), color = TextPrimary, fontSize = 12.sp)
                                    if (comment.optBoolean("hidden")) Text("Moderated", color = TextMuted, fontSize = 10.sp)
                                }
                            }
                        }
                    }

                    "Controls" -> {
                        Text("Workspace controls", color = TextPrimary, fontWeight = FontWeight.Bold)
                        val validateCriteria = groupSettings?.optFlag("validate_criteria", true) ?: true
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text("Validate criteria", color = TextPrimary, fontSize = 12.sp)
                                Text("Require the server AI criteria check when creating or editing tasks.", color = TextMuted, fontSize = 10.sp)
                            }
                            Switch(
                                checked = validateCriteria,
                                enabled = groupRole == "owner",
                                onCheckedChange = { value ->
                                    postGroupAction(base + "/settings", operationBody()
                                        .put("revision", groupRevision)
                                        .put("validate_criteria", value), "Universe settings updated.")
                                }
                            )
                        }

                        val consentEnabled = lockConsent?.optFlag("enabled", false) ?: false
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text("Consent to phone-lock eligibility", color = TextPrimary, fontSize = 12.sp)
                                Text("This is optional consent for eligible Universe assignments to affect device access.", color = TextMuted, fontSize = 10.sp)
                            }
                            Switch(
                                checked = consentEnabled,
                                onCheckedChange = { value ->
                                    postGroupAction(base + "/consent", operationBody().put("enabled", value), "Phone-lock consent updated.")
                                }
                            )
                        }

                        if (isManager) {
                            Text("Assignment lock eligibility", color = TextPrimary, fontWeight = FontWeight.SemiBold, fontSize = 12.sp)
                            locks.forEach { lock ->
                                val id = lock.optString("id")
                                val eligible = lock.optInt("eligible", 0) == 1 || lock.optBoolean("eligible", false)
                                Row(
                                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(id.take(24), color = TextSecondary, fontSize = 10.sp, modifier = Modifier.weight(1f))
                                    Switch(
                                        checked = eligible,
                                        onCheckedChange = { value ->
                                            postGroupAction(base + "/locks/" + id, operationBody()
                                                .put("eligible", value)
                                                .put("revision", lock.optInt("revision", 1))
                                                .put("task_revision", lock.optInt("task_revision", 1)), "Lock eligibility updated.")
                                        }
                                    )
                                }
                            }
                        }

                        Spacer(Modifier.height(10.dp))
                        Text("Recent activity", color = TextPrimary, fontWeight = FontWeight.SemiBold, fontSize = 12.sp)
                        activity.take(30).forEach { event ->
                            Text(
                                event.optString("kind") + " · " + event.optString("actor_id").take(20),
                                color = TextMuted,
                                fontSize = 10.sp,
                                modifier = Modifier.padding(vertical = 2.dp)
                            )
                        }

                        Spacer(Modifier.height(12.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(
                                onClick = {
                                    postGroupAction(base + "/leave", operationBody().put("revision", groupRevision), "Left the Universe.")
                                    selectedGroupId = null
                                },
                                enabled = groupRole != "owner"
                            ) { Text("Leave") }
                            if (groupRole == "owner") {
                                OutlinedButton(
                                    onClick = {
                                        postGroupAction(base + "/delete", operationBody().put("revision", groupRevision).put("confirm", true), "Universe deletion requested.")
                                        selectedGroupId = null
                                    }
                                ) { Text("Delete Universe", color = PriorityP1) }
                            }
                        }
                        OutlinedButton(
                            onClick = { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://taskitator.mazendev.com"))) },
                            modifier = Modifier.fillMaxWidth()
                        ) { Text("Open website for backup import/export and advanced transfer") }
                    }
                }
            }
        }
    }

    if (showCreateGroup) {
        AlertDialog(
            onDismissRequest = { showCreateGroup = false },
            title = { Text("Create Universe") },
            text = {
                Column {
                    OutlinedTextField(value = createTitle, onValueChange = { createTitle = it }, label = { Text("Name") })
                    OutlinedTextField(value = createPurpose, onValueChange = { createPurpose = it }, label = { Text("Purpose") })
                }
            },
            confirmButton = { TextButton(onClick = { createUniverse() }, enabled = !loading) { Text("Create") } },
            dismissButton = { TextButton(onClick = { showCreateGroup = false }) { Text("Cancel") } }
        )
    }

    if (showCreateProject) {
        AlertDialog(
            onDismissRequest = { showCreateProject = false },
            title = { Text("New project") },
            text = { OutlinedTextField(value = newProjectTitle, onValueChange = { newProjectTitle = it }, label = { Text("Project title") }) },
            confirmButton = { TextButton(onClick = { createProject() }, enabled = !loading) { Text("Create") } },
            dismissButton = { TextButton(onClick = { showCreateProject = false }) { Text("Cancel") } }
        )
    }

    if (showCreateTask) {
        AlertDialog(
            onDismissRequest = { showCreateTask = false },
            title = { Text("New Universe task") },
            text = {
                Column(Modifier.heightIn(max = 430.dp).verticalScroll(rememberScrollState())) {
                    OutlinedTextField(value = newTaskTitle, onValueChange = { newTaskTitle = it; criteriaValidation = null }, label = { Text("Task title") })
                    OutlinedTextField(value = newTaskCriteria, onValueChange = { newTaskCriteria = it; criteriaValidation = null }, label = { Text("Verifiable proof criteria") }, minLines = 3)
                    Text("Assignment mode", fontSize = 11.sp)
                    Row {
                        FilterChip(selected = newTaskMode == "everyone", onClick = { newTaskMode = "everyone" }, label = { Text("Everyone") })
                        Spacer(Modifier.width(6.dp))
                        FilterChip(selected = newTaskMode == "shared", onClick = { newTaskMode = "shared" }, label = { Text("Shared") })
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedButton(
                            onClick = {
                                val initial = Calendar.getInstance()
                                DatePickerDialog(
                                    context,
                                    { _, year, month, day ->
                                        val due = Calendar.getInstance().apply {
                                            set(year, month, day, 23, 59, 0)
                                            set(Calendar.MILLISECOND, 0)
                                        }
                                        dueAtMillis = due.timeInMillis
                                    },
                                    initial.get(Calendar.YEAR),
                                    initial.get(Calendar.MONTH),
                                    initial.get(Calendar.DAY_OF_MONTH)
                                ).apply { datePicker.minDate = System.currentTimeMillis() }.show()
                            },
                            modifier = Modifier.weight(1f)
                        ) { Text(if (dueAtMillis == null) "Set due date" else "Change due date") }
                        if (dueAtMillis != null) {
                            TextButton(onClick = { dueAtMillis = null }) { Text("Clear") }
                        }
                    }
                    if (dueAtMillis != null) {
                        Text(
                            "Due: " + SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date(dueAtMillis!!)) + " (end of day)",
                            color = CyanAccent,
                            fontSize = 11.sp
                        )
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = newTaskAiRequired, onCheckedChange = {
                            newTaskAiRequired = it
                            criteriaValidation = null
                        })
                        Text("Require AI evidence review", fontSize = 12.sp)
                    }
                    if (newTaskAiRequired && serverValidationRequired) {
                        OutlinedButton(
                            onClick = { validateUniverseCriteria() },
                            enabled = !loading && newTaskTitle.isNotBlank() && newTaskCriteria.isNotBlank(),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(if (loading) "Validating…" else "Validate criteria")
                        }
                        criteriaValidation?.let { validation ->
                            Text(
                                "Score: " + validation.optInt("score") + "/10 · " +
                                    validation.optString("critique", ""),
                                color = if (validation.optBoolean("passed", false)) SuccessGreen else WarningAmber,
                                fontSize = 11.sp
                            )
                        }
                    }
                    Text("All active Universe members are assigned. Review permissions are enforced by the Worker.", color = TextMuted, fontSize = 10.sp)
                }
            },
            confirmButton = { TextButton(onClick = { createTask() }, enabled = !loading) { Text("Create task") } },
            dismissButton = { TextButton(onClick = { showCreateTask = false }) { Text("Cancel") } }
        )
    }

    if (showInvite) {
        AlertDialog(
            onDismissRequest = { showInvite = false },
            title = { Text("Invite member") },
            text = {
                Column {
                    Text("Role", color = TextSecondary, fontSize = 11.sp)
                    Row {
                        listOf("member", "reviewer", "admin").forEach { role ->
                            FilterChip(
                                selected = inviteRole == role,
                                onClick = { inviteRole = role },
                                enabled = role != "admin" || groupRole == "owner",
                                label = { Text(role) }
                            )
                            Spacer(Modifier.width(4.dp))
                        }
                    }
                    Text("Invitation token is generated locally, hashed by the Worker, and shown once.", color = TextMuted, fontSize = 10.sp)
                }
            },
            confirmButton = { TextButton(onClick = { createInvitation() }, enabled = !loading) { Text("Create invitation") } },
            dismissButton = { TextButton(onClick = { showInvite = false }) { Text("Cancel") } }
        )
    }

    if (showSubmit) {
        AlertDialog(
            onDismissRequest = { if (!loading) showSubmit = false },
            title = { Text("Submit proof") },
            text = {
                Column {
                    Text(selectedTask?.optString("title").orEmpty(), color = TextSecondary, fontSize = 11.sp)
                    OutlinedTextField(value = submissionText, onValueChange = { submissionText = it }, label = { Text("Evidence notes") }, minLines = 2)
                    OutlinedButton(
                        onClick = { proofPicker.launch(arrayOf("image/jpeg", "image/png", "application/pdf", "text/plain")) },
                        enabled = !loading
                    ) { Text("Attach files (max 3, 5 MiB each)") }
                    submissionFiles.forEach { Text(it.name + " · " + it.mime, color = TextMuted, fontSize = 10.sp) }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = { submitAssignment() },
                    enabled = !loading && (submissionText.isNotBlank() || submissionFiles.isNotEmpty())
                ) { Text(if (loading) "Uploading…" else "Submit") }
            },
            dismissButton = { TextButton(onClick = { showSubmit = false }) { Text("Cancel") } }
        )
    }

    if (showAssignmentDetail && selectedAssignment != null && selectedTask != null) {
        val detail = selectedAssignmentDetail
        val submissionList = detail?.optJSONArray("submissions")?.toObjectList().orEmpty()
        val latest = submissionList.firstOrNull()
        val isSubmitter = latest?.optString("actor_id") == currentUserId
        val canReview = groupRole in listOf("owner", "admin", "reviewer") && latest != null && !isSubmitter
        AlertDialog(
            onDismissRequest = { showAssignmentDetail = false },
            title = { Text(selectedTask?.optString("title").orEmpty() + " · " + selectedAssignment?.optString("state").orEmpty()) },
            text = {
                Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                    if (latest == null) {
                        Text("No submission has been recorded yet.", color = TextSecondary, fontSize = 12.sp)
                    } else {
                        Text("Submitted by " + latest.optString("actor_id").take(36), color = CyanAccent, fontSize = 10.sp)
                        Text(latest.optString("text", ""), color = TextPrimary, fontSize = 12.sp)
                        val fileIds = latest.optJSONArray("files") ?: JSONArray()
                        if (fileIds.length() > 0) {
                            Text("Proof attachments", color = CyanAccent, fontWeight = FontWeight.SemiBold, fontSize = 11.sp)
                            (0 until fileIds.length()).forEach { index ->
                                TextButton(
                                    onClick = { downloadAttachment(fileIds.optString(index)) },
                                    enabled = !downloadBusy && !loading
                                ) {
                                    Text(if (downloadBusy) "Preparing download…" else "Save proof file " + (index + 1))
                                }
                            }
                        }
                        val referenceFiles = detail?.optJSONArray("references")?.toObjectList().orEmpty()
                        if (referenceFiles.isNotEmpty()) {
                            Spacer(Modifier.height(6.dp))
                            Text("Reference files", color = CyanAccent, fontWeight = FontWeight.SemiBold, fontSize = 11.sp)
                            referenceFiles.forEach { reference ->
                                TextButton(
                                    onClick = { downloadAttachment(reference.optString("id")) },
                                    enabled = !downloadBusy && !loading
                                ) {
                                    Text("Save " + reference.optString("name", "reference file"))
                                }
                            }
                        }
                        Text("Decision: " + latest.optString("decision", "pending"), color = TextSecondary, fontSize = 11.sp)
                        Text(latest.optString("reason"), color = TextMuted, fontSize = 11.sp)
                    }
                    if (canReview) {
                        Spacer(Modifier.height(8.dp))
                        Text("A different reviewer must decide the submission.", color = TextMuted, fontSize = 10.sp)
                    }
                }
            },
            confirmButton = {
                Row {
                    if (selectedTask?.optBoolean("ai_required") == true && isSubmitter && latest != null && selectedAssignment?.optString("state") == "pending" && (latest.isNull("decision") || latest.optString("decision").isBlank())) {
                        TextButton(onClick = {
                            startUniverseAiReview(selectedAssignment!!, selectedTask!!)
                            showAssignmentDetail = false
                        }) { Text("AI review") }
                    }
                    if (canReview) {
                        TextButton(onClick = {
                            reviewDecision = "approved"
                            showReview = true
                        }) { Text("Approve") }
                        TextButton(onClick = {
                            reviewDecision = "rejected"
                            showReview = true
                        }) { Text("Reject", color = PriorityP1) }
                    }
                    TextButton(onClick = { showAssignmentDetail = false }) { Text("Close") }
                }
            }
        )
    }

    if (showReview) {
        AlertDialog(
            onDismissRequest = { showReview = false },
            title = { Text(if (reviewDecision == "approved") "Approve submission" else "Reject submission") },
            text = { Column { OutlinedTextField(value = reviewReason, onValueChange = { reviewReason = it }, label = { Text("Reason / feedback") }, minLines = 2); Text("A review reason is required by the server.", color = TextMuted, fontSize = 10.sp) } },
            confirmButton = { TextButton(onClick = { reviewAssignment() }, enabled = !loading && reviewReason.isNotBlank()) { Text("Confirm") } },
            dismissButton = { TextButton(onClick = { showReview = false }) { Text("Cancel") } }
        )
    }

    if (aiQuote != null) {
        val quoted = aiQuote!!
        AlertDialog(
            onDismissRequest = { aiQuote = null },
            title = { Text("Universe AI review quote") },
            text = {
                Column {
                    Text("Mode: " + aiQuoteMode, color = TextSecondary, fontSize = 11.sp)
                    Text("Maximum credits: " + quoted.optString("max_credits", "0"), color = TextPrimary, fontWeight = FontWeight.Bold)
                    Text("The review starts only after you accept the quote.", color = TextMuted, fontSize = 10.sp)
                }
            },
            confirmButton = { TextButton(onClick = { approveUniverseAiReview() }, enabled = !loading) { Text("Accept and review") } },
            dismissButton = { TextButton(onClick = { aiQuote = null }) { Text("Cancel") } }
        )
    }

    if (aiResultMessage != null) {
        AlertDialog(
            onDismissRequest = { aiResultMessage = null },
            title = { Text("Universe AI review result") },
            text = { Text(aiResultMessage!!, color = TextPrimary) },
            confirmButton = { TextButton(onClick = { aiResultMessage = null }) { Text("Close") } }
        )
    }
}

private suspend fun readUniverseFile(context: Context, uri: Uri): UniverseUploadFile = withContext(Dispatchers.IO) {
    val resolver = context.contentResolver
    val name = resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
        if (cursor.moveToFirst()) cursor.getString(0) else null
    } ?: "proof"
    var mime = resolver.getType(uri)?.lowercase().orEmpty()
    if (mime.isBlank() || mime == "application/octet-stream") {
        mime = when (name.substringAfterLast('.', "").lowercase()) {
            "jpg", "jpeg" -> "image/jpeg"
            "png" -> "image/png"
            "pdf" -> "application/pdf"
            "txt" -> "text/plain"
            else -> mime
        }
    }
    if (mime !in setOf("image/jpeg", "image/png", "application/pdf", "text/plain")) {
        throw IllegalArgumentException("Use JPEG, PNG, PDF or plain text files.")
    }
    val output = ByteArrayOutputStream()
    resolver.openInputStream(uri)?.use { input ->
        val buffer = ByteArray(8192)
        var total = 0
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            total += count
            if (total > 5 * 1024 * 1024) throw IllegalArgumentException(name + " exceeds the 5 MiB Universe upload limit.")
            output.write(buffer, 0, count)
        }
    } ?: throw IllegalArgumentException("Unable to open " + name)
    val bytes = output.toByteArray()
    val hash = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    UniverseUploadFile(name, mime, bytes, hash)
}
