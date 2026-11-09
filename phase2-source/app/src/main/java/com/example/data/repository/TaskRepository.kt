package com.example.data.repository

import android.content.Context
import com.example.data.model.*
import com.example.data.network.*
import com.example.data.util.CriteriaValidator
import com.example.data.util.DateUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

class TaskRepository(private val context: Context) {
    private val scope = CoroutineScope(Dispatchers.IO)
    private val authPrefs = context.getSharedPreferences("taskitator_auth_secure", Context.MODE_PRIVATE)
    private val displayPrefs = context.getSharedPreferences("taskitator_display", Context.MODE_PRIVATE)

    // Auth & Account State (Phase 1.1)
    private val _authState = MutableStateFlow<AuthState>(AuthState.SignedOut)
    val authState: StateFlow<AuthState> = _authState.asStateFlow()

    // Sync state
    private val _syncState = MutableStateFlow(SyncState.IDLE)
    val syncState: StateFlow<SyncState> = _syncState.asStateFlow()

    private val _syncStatusMessage = MutableStateFlow<String?>(null)
    val syncStatusMessage: StateFlow<String?> = _syncStatusMessage.asStateFlow()

    // Conflict Report State (Phase 1.3)
    private val _syncConflictReport = MutableStateFlow<SyncConflictReport?>(null)
    val syncConflictReport: StateFlow<SyncConflictReport?> = _syncConflictReport.asStateFlow()

    // Core Task State
    private val _tasks = MutableStateFlow<List<Task>>(emptyList())
    val tasks: StateFlow<List<Task>> = _tasks.asStateFlow()

    private val _projects = MutableStateFlow<List<TaskProject>>(listOf(TaskProject("inbox", "Inbox", isDefault = true)))
    val projects: StateFlow<List<TaskProject>> = _projects.asStateFlow()

    private val _taskDrafts = MutableStateFlow<List<TaskDraft>>(emptyList())
    val taskDrafts: StateFlow<List<TaskDraft>> = _taskDrafts.asStateFlow()

    private val _settings = MutableStateFlow(UserSettings())
    val settings: StateFlow<UserSettings> = _settings.asStateFlow()

    private val _savedCriteria = MutableStateFlow<List<SavedCriteria>>(emptyList())
    val savedCriteria: StateFlow<List<SavedCriteria>> = _savedCriteria.asStateFlow()

    private val _breakPlanning = MutableStateFlow(BreakPlanningState())
    val breakPlanning: StateFlow<BreakPlanningState> = _breakPlanning.asStateFlow()

    private val _breaks = MutableStateFlow<List<BreakSession>>(emptyList())
    val breaks: StateFlow<List<BreakSession>> = _breaks.asStateFlow()

    private val _notes = MutableStateFlow<List<DistractionNote>>(emptyList())
    val notes: StateFlow<List<DistractionNote>> = _notes.asStateFlow()

    private val _pomodoro = MutableStateFlow(PomodoroState())
    val pomodoro: StateFlow<PomodoroState> = _pomodoro.asStateFlow()

    private val _lastCompletedTask = MutableStateFlow<Task?>(null)
    val lastCompletedTask: StateFlow<Task?> = _lastCompletedTask.asStateFlow()

    // Authoritative Raw JSON trees (Phase 1.2)
    private var rawSnapshot: JSONObject = JSONObject()
    private var baseSnapshot: JSONObject = JSONObject()
    private var baseVersion: Long = 0L

    // In-flight idempotent upload tracking
    private var pendingMutationId: String? = null
    private var pendingPushPayload: JSONObject? = null
    private var isSyncInProgress: Boolean = false

    init {
        restorePersistedAuthSession()
        loadData()
    }

    // --- Account Isolation & File Paths (Phase 1.1) ---

    private fun getSafeUserId(): String {
        return when (val state = _authState.value) {
            is AuthState.Authenticated -> state.workerUser.replace(Regex("[^a-zA-Z0-9_-]"), "_")
            is AuthState.Unlinked -> state.clerkUserId.replace(Regex("[^a-zA-Z0-9_-]"), "_")
            else -> {
                val savedUser = authPrefs.getString("saved_worker_user", "anonymous") ?: "anonymous"
                savedUser.replace(Regex("[^a-zA-Z0-9_-]"), "_")
            }
        }
    }

    private fun getSnapshotFile(): File = File(context.filesDir, "taskitator_${getSafeUserId()}_snapshot.json")
    private fun getBaseSnapshotFile(): File = File(context.filesDir, "taskitator_${getSafeUserId()}_base.json")
    private fun getRecoveryFile(): File = File(context.filesDir, "taskitator_${getSafeUserId()}_recovery.json")
    private fun getBackupFile(): File = File(context.filesDir, "taskitator_${getSafeUserId()}_conflict_backup.json")
    private val notesFile: File get() = File(context.filesDir, "taskitator_${getSafeUserId()}_notes.json")

    private fun restorePersistedAuthSession() {
        val savedToken = authPrefs.getString("saved_token", "") ?: ""
        val savedWorkerUser = authPrefs.getString("saved_worker_user", "") ?: ""
        val savedClerkUser = authPrefs.getString("saved_clerk_user", "") ?: ""
        val savedUsername = authPrefs.getString("saved_username", "Focus Champion") ?: "Focus Champion"

        if (savedToken.isNotBlank() && savedWorkerUser.isNotBlank()) {
            _authState.value = AuthState.Authenticated(
                clerkUserId = savedClerkUser,
                workerUser = savedWorkerUser,
                username = savedUsername,
                isOffline = false
            )
            _settings.value = _settings.value.copy(
                authToken = savedToken,
                clerkUserId = savedClerkUser,
                workerUser = savedWorkerUser,
                username = savedUsername
            )
        } else {
            _authState.value = AuthState.SignedOut
        }
    }

    // --- Auth Actions (Phase 1.1) ---

    suspend fun signInWithToken(token: String): Pair<Boolean, String> {
        if (token.isBlank()) {
            _authState.value = AuthState.SignedOut
            return Pair(false, "Token cannot be empty.")
        }

        _syncStatusMessage.value = "Verifying account identity with Worker..."
        _authState.value = AuthState.Connecting

        val meResult = TaskitatorWorkerApi.getAuthMe(token)
        if (meResult.isSuccess) {
            val obj = meResult.getOrThrow()
            val accountObj = obj.optJSONObject("account")
            val workerUser = accountObj?.optString("user") ?: obj.optString("user", "")
            val username = accountObj?.optString("username") ?: obj.optString("username", "Focus Champion")
            val clerkId = obj.optString("clerk_id", obj.optString("id", workerUser))

            if (workerUser.isBlank()) {
                // Token valid with Clerk, but account not yet linked on Worker
                _authState.value = AuthState.Unlinked(clerkUserId = clerkId, token = token)
                return Pair(false, "Clerk session verified, but no cloud account is linked. Please connect account.")
            }

            // Persist session securely
            authPrefs.edit()
                .putString("saved_token", token)
                .putString("saved_worker_user", workerUser)
                .putString("saved_clerk_user", clerkId)
                .putString("saved_username", username)
                .apply()

            _authState.value = AuthState.Authenticated(
                clerkUserId = clerkId,
                workerUser = workerUser,
                username = username
            )

            _settings.value = _settings.value.copy(
                authToken = token,
                clerkUserId = clerkId,
                workerUser = workerUser,
                username = username,
                needsInitialPull = true
            )

            // Reload user's isolated local store. Never overwrite unsent offline edits.
            loadData()
            val dirty = authPrefs.getBoolean("dirty_${getSafeUserId()}", false)
            if (dirty && !getBaseSnapshotFile().exists()) {
                _syncState.value = SyncState.CONFLICT
                return Pair(true, "Local edits need manual backup before first cloud pull. Export your local data; no data was overwritten.")
            }
            if (dirty) _settings.value = _settings.value.copy(needsInitialPull = false)
            val pullResult = if (dirty) performSync() else initialPull()
            return if (pullResult.first) {
                Pair(true, "Signed in as $username ($workerUser). Cloud synced.")
            } else {
                Pair(true, "Signed in as $username. Cloud pull pending: ${pullResult.second}")
            }
        } else {
            val ex = meResult.exceptionOrNull()
            return when (ex) {
                is UnauthorizedException -> {
                    _authState.value = AuthState.SessionExpired("Session expired or invalid (401).")
                    Pair(false, "Session expired or invalid (401). Please sign in again.")
                }
                is AccountDeletedException -> {
                    _authState.value = AuthState.AccountDeleted("Account has been permanently deleted.")
                    Pair(false, "Account deletion has been accepted (410).")
                }
                is WorkerProtectionException -> {
                    _authState.value = AuthState.Error("Access restricted by Worker policy (403).")
                    Pair(false, "Access restricted (403): ${ex.message}")
                }
                else -> {
                    // Network failure: If token was already known, keep offline
                    val savedWorkerUser = authPrefs.getString("saved_worker_user", "") ?: ""
                    if (savedWorkerUser.isNotBlank()) {
                        _authState.value = AuthState.Authenticated(
                            clerkUserId = authPrefs.getString("saved_clerk_user", "") ?: "",
                            workerUser = savedWorkerUser,
                            username = authPrefs.getString("saved_username", "Focus Champion") ?: "Focus Champion",
                            isOffline = true
                        )
                        Pair(true, "Working offline. Could not reach server.")
                    } else {
                        _authState.value = AuthState.Error(ex?.message ?: "Authentication failed.")
                        Pair(false, ex?.message ?: "Authentication failed.")
                    }
                }
            }
        }
    }

    suspend fun connectWorkerAccount(token: String): Pair<Boolean, String> {
        val connectRes = TaskitatorWorkerApi.connectAccount(token, mode = "create")
        if (connectRes.isSuccess) {
            return signInWithToken(token)
        } else {
            return Pair(false, connectRes.exceptionOrNull()?.message ?: "Account connection failed.")
        }
    }

    fun signOut() {
        authPrefs.edit()
            .remove("saved_token")
            .remove("saved_worker_user")
            .remove("saved_clerk_user")
            .remove("saved_username")
            .apply()

        _authState.value = AuthState.SignedOut
        _settings.value = UserSettings(needsInitialPull = true)
        _tasks.value = emptyList()
        _syncConflictReport.value = null
        pendingMutationId = null
        pendingPushPayload = null

        // Load isolated anonymous store
        loadData()
    }

    // --- Lossless Local Storage & Recovery (Phase 1.2) ---

    private fun loadData() {
        val sFile = getSnapshotFile()
        val rFile = getRecoveryFile()
        var loaded = false

        if (sFile.exists() && sFile.length() > 0) {
            try {
                val jsonStr = sFile.readText()
                if (jsonStr.isNotBlank()) {
                    rawSnapshot = JSONObject(jsonStr)
                    baseVersion = rawSnapshot.optLong("version", rawSnapshot.optLong("base_version", 0L))
                    parseSnapshot(rawSnapshot)
                    loaded = true
                }
            } catch (e: Exception) {
                // Primary snapshot file corrupted; attempt recovery
            }
        }

        if (!loaded && rFile.exists() && rFile.length() > 0) {
            try {
                val rJsonStr = rFile.readText()
                if (rJsonStr.isNotBlank()) {
                    rawSnapshot = JSONObject(rJsonStr)
                    baseVersion = rawSnapshot.optLong("version", rawSnapshot.optLong("base_version", 0L))
                    parseSnapshot(rawSnapshot)
                    loaded = true
                    // Safely restore primary file from recovery
                    rFile.copyTo(sFile, overwrite = true)
                }
            } catch (e: Exception) {
                // Recovery file corrupted
            }
        }

        if (!loaded) {
            initDefaultSnapshot()
        }

        val bFile = getBaseSnapshotFile()
        if (bFile.exists() && bFile.length() > 0) {
            try {
                baseSnapshot = JSONObject(bFile.readText())
            } catch (e: Exception) {
                baseSnapshot = JSONObject(rawSnapshot.toString())
            }
        } else {
            baseSnapshot = JSONObject(rawSnapshot.toString())
        }

        // A prior synced base allows safe push/rebase of unsent changes after app restart.
        if (authPrefs.getBoolean("dirty_${getSafeUserId()}", false) && bFile.exists()) {
            _settings.value = _settings.value.copy(needsInitialPull = false)
        }
        _notes.value = readNotesAndCleanup()
        checkEmergencyBypassCycleReset()
    }

    private fun initDefaultSnapshot() {
        rawSnapshot = JSONObject().apply {
            put("app", "taskitator")
            put("username", _settings.value.username.ifBlank { "Focus Champion" })
            put("updated_at", System.currentTimeMillis())
            put("force", false)
            put("recurrence_state", JSONObject())
            put("recurrence_schema", 1)
            put("tasks", JSONArray())
            put("projects", JSONArray())
            put("settings", JSONObject())
            put("today_breaks", JSONObject())
            put("completed_audit_ledger", JSONArray())
            put("saved_criteria", JSONArray())
            put("last_login", System.currentTimeMillis())
        }

        // Strict invariant: NO DEMO TASKS.
        _tasks.value = emptyList()
        _settings.value = _settings.value.copy(needsInitialPull = true)
        saveLocalSnapshot(localChange = false)
    }

    private fun parseSnapshot(json: JSONObject) {
        // 1. Settings
        val settingsObj = json.optJSONObject("settings") ?: JSONObject()
        val customPrioList = mutableListOf<CustomPriority>()
        val customPrioArr = settingsObj.optJSONArray("custom_priorities")
        if (customPrioArr != null && customPrioArr.length() > 0) {
            for (i in 0 until customPrioArr.length()) {
                val p = customPrioArr.getJSONObject(i)
                customPrioList.add(
                    CustomPriority(
                        id = p.getString("id"),
                        label = p.getString("label"),
                        rank = p.getInt("rank"),
                        colorHex = p.optString("color", "#3B82F6")
                    )
                )
            }
        } else {
            customPrioList.addAll(DEFAULT_PRIORITIES)
        }

        val customTagsList = mutableListOf<String>()
        val tagsArr = settingsObj.optJSONArray("custom_tags")
        if (tagsArr != null && tagsArr.length() > 0) {
            for (i in 0 until tagsArr.length()) {
                customTagsList.add(tagsArr.getString(i))
            }
        } else {
            customTagsList.addAll(DEFAULT_TAGS)
        }

        _settings.value = _settings.value.copy(
            username = json.optString("username", _settings.value.username),
            copilotName = settingsObj.optString("copilot_name", "Ari"),
            language = displayPrefs.getString("language", "en") ?: "en",
            appearanceTheme = displayPrefs.getString("theme", "midnight") ?: "midnight",
            taskSortMode = settingsObj.optString("task_sort_mode", "priority"),
            isPro = settingsObj.optBoolean("is_pro", false),
            useByok = settingsObj.optBoolean("use_byok", false),
            dayStartHour = settingsObj.optInt("day_start_hour", 0).coerceIn(0, 4),
            weekendStart = settingsObj.optInt("weekend_start", 5),
            weekendEnd = settingsObj.optInt("weekend_end", 6),
            breakTimezone = settingsObj.optString("break_timezone", "UTC"),
            dumpDurationSeconds = settingsObj.optInt("dump_duration", 90).coerceIn(30, 300),
            strictCriteriaValidation = settingsObj.optBoolean("strict_criteria_validation", true),
            soundEffectsEnabled = settingsObj.optBoolean("sound_effects_enabled", true),
            hapticsEnabled = settingsObj.optBoolean("haptics_enabled", true),
            emergencyBypassUsesRemaining = settingsObj.optInt("emergency_bypass_uses", 4),
            emergencyBypassActive = settingsObj.optBoolean("emergency_bypass_active", false),
            emergencyBypassUntil = settingsObj.optLong("emergency_bypass_until", 0L),
            customPriorities = customPrioList,
            customTags = customTagsList,
            remoteVersion = baseVersion
        )

        // 2. Projects are a first-class top-level synced snapshot collection.
        val projectsArray = json.optJSONArray("projects") ?: JSONArray()
        val projectsList = mutableListOf(TaskProject("inbox", "Inbox", isDefault = true))
        for (i in 0 until projectsArray.length()) {
            val item = projectsArray.optJSONObject(i) ?: continue
            val id = item.optString("id")
            if (id.isBlank() || id == "inbox") continue
            projectsList.add(TaskProject(
                id = id, name = item.optString("name", "Untitled"),
                color = item.optString("color", "#3B82F6"),
                icon = item.optString("icon", "📁"),
                isDefault = item.optBoolean("is_default", false)
            ))
        }
        _projects.value = projectsList

        // Draft keys live inside synced settings, matching task-drafts.js.
        val drafts = mutableListOf<TaskDraft>()
        val settingsKeys = settingsObj.keys()
        while (settingsKeys.hasNext()) {
            val key = settingsKeys.next()
            if (!key.startsWith("task_draft:") && !key.startsWith("edit_draft:")) continue
            val wrapper = settingsObj.optJSONObject(key) ?: continue
            val fields = wrapper.optJSONObject("fields") ?: continue
            val taskId = if (key.startsWith("edit_draft:")) key.removePrefix("edit_draft:") else null
            val id = taskId ?: key.removePrefix("task_draft:")
            val tagArr = fields.optJSONArray("tags") ?: JSONArray()
            val tags = (0 until tagArr.length()).mapNotNull { tagArr.optString(it).takeIf(String::isNotBlank) }
            drafts.add(TaskDraft(
                id = id, editTaskId = taskId,
                savedAt = wrapper.optLong("saved_at", 0),
                task = Task(id = taskId ?: id, title = fields.optString("title"),
                    description = fields.optString("description"), dueDate = fields.optString("due_date"),
                    parentId = if (fields.isNull("parent_id")) null else fields.optString("parent_id").takeIf { it.isNotBlank() },
                    projectId = fields.optString("project_id", "inbox"),
                    priorityId = fields.optString("priority_id", "prio_med"), tags = tags,
                    aiLocked = fields.optBoolean("ai_locked"),
                    proofCriteria = fields.optString("criteria"),
                    requireAllSubtasksFirst = fields.optBoolean("strict_prerequisites"),
                    isBonus = fields.optBoolean("is_bonus"))
            ))
        }
        _taskDrafts.value = drafts.sortedByDescending { it.savedAt }

        // 3. Tasks
        val tasksArr = json.optJSONArray("tasks") ?: JSONArray()
        val loadedTasks = mutableListOf<Task>()
        for (i in 0 until tasksArr.length()) {
            val t = tasksArr.getJSONObject(i)
            val tags = mutableListOf<String>()
            val tArr = t.optJSONArray("tags")
            if (tArr != null) {
                for (j in 0 until tArr.length()) tags.add(tArr.getString(j))
            }

            val unmapped = mutableMapOf<String, Any?>()
            val iter = t.keys()
            val known = setOf("id", "parent_id", "title", "raw_title", "description", "due_date", "priority_id", "tags", "status", "ai_locked", "proof_criteria", "raw_proof_criteria", "strict_prerequisites", "is_bonus", "created_at", "completed_at", "order_index")
            while (iter.hasNext()) {
                val k = iter.next()
                if (k !in known) unmapped[k] = t.opt(k)
            }

            val statusStr = t.optString("status", "active").lowercase()
            val status = when (statusStr) {
                "completed" -> TaskStatus.COMPLETED
                "trash" -> TaskStatus.TRASH
                else -> TaskStatus.ACTIVE
            }

            loadedTasks.add(
                Task(
                    id = t.optString("id", UUID.randomUUID().toString()),
                    parentId = if (t.isNull("parent_id")) null else t.optString("parent_id").takeIf { it.isNotBlank() },
                    title = t.optString("title", ""),
                    rawTitle = t.optString("raw_title", t.optString("title", "")),
                    description = t.optString("description", ""),
                    dueDate = t.optString("due_date", ""),
                    priorityId = t.optString("priority_id", "prio_med"),
                    tags = tags,
                    status = status,
                    aiLocked = t.optBoolean("ai_locked", false),
                    auditApproved = t.optBoolean("audit_approved", false),
                    proofCriteria = t.optString("proof_criteria", ""),
                    rawProofCriteria = t.optString("raw_proof_criteria", ""),
                    requireAllSubtasksFirst = t.optBoolean("strict_prerequisites", false),
                    isBonus = t.optBoolean("is_bonus", false),
                    smartEarlyDate = t.optString("smart_early_date").takeIf { it.isNotBlank() },
                    projectId = if (t.isNull("project_id")) "inbox" else t.optString("project_id", "inbox").ifBlank { "inbox" },
                    createdAt = t.optLong("created_at", System.currentTimeMillis()),
                    completedAt = t.optLong("completed_at").takeIf { it > 0 },
                    orderIndex = t.optInt("order_index", i),
                    unmappedFields = unmapped
                )
            )
        }
        _tasks.value = loadedTasks

        // 3. Saved Criteria
        val scArr = json.optJSONArray("saved_criteria") ?: JSONArray()
        val scList = mutableListOf<SavedCriteria>()
        for (i in 0 until scArr.length()) {
            val sc = scArr.getJSONObject(i)
            scList.add(
                SavedCriteria(
                    id = sc.optString("id", UUID.randomUUID().toString()),
                    title = sc.optString("title", ""),
                    criteria = sc.optString("criteria", ""),
                    score = sc.optInt("score", 9),
                    hasExemplar = sc.optBoolean("has_exemplar", false),
                    exemplar = sc.optString("exemplar").takeIf { it.isNotBlank() },
                    createdAt = sc.optLong("created_at", System.currentTimeMillis())
                )
            )
        }
        _savedCriteria.value = scList
    }

    /**
     * Atomically saves the authoritative raw snapshot with safety recovery backup.
     */
    private fun saveLocalSnapshot(localChange: Boolean = true) {
        if (localChange) authPrefs.edit().putBoolean("dirty_${getSafeUserId()}", true).apply()
        else authPrefs.edit().putBoolean("dirty_${getSafeUserId()}", false).apply()
        val sFile = getSnapshotFile()
        val rFile = getRecoveryFile()
        val tmpFile = File(context.filesDir, "${sFile.name}.tmp")
        try {
            tmpFile.writeText(rawSnapshot.toString(2))
            if (sFile.exists() && sFile.length() > 0) {
                sFile.copyTo(rFile, overwrite = true)
            }
            if (!tmpFile.renameTo(sFile)) {
                tmpFile.copyTo(sFile, overwrite = true)
                tmpFile.delete()
            }
        } catch (e: Exception) {
            tmpFile.delete()
        }
    }

    /**
     * Targeted field update: modifies ONLY the specific task's fields in the raw JSON,
     * guaranteeing 100% preservation of unknown fields, recurrence state, and web metadata.
     */
    private fun applyTaskToRawSnapshot(task: Task) {
        val tasksArr = rawSnapshot.optJSONArray("tasks") ?: JSONArray().also { rawSnapshot.put("tasks", it) }
        var taskObj: JSONObject? = null
        for (i in 0 until tasksArr.length()) {
            val obj = tasksArr.getJSONObject(i)
            if (obj.optString("id") == task.id) {
                taskObj = obj
                break
            }
        }

        if (taskObj == null) {
            // New task created on Android
            taskObj = JSONObject().apply {
                put("id", task.id)
                if (task.parentId != null) put("parent_id", task.parentId)
                put("title", task.title)
                put("raw_title", task.rawTitle)
                put("description", task.description)
                put("due_date", task.dueDate)
                put("priority_id", task.priorityId)
                put("tags", JSONArray(task.tags))
                put("status", task.status.name.lowercase())
                put("ai_locked", task.aiLocked)
                put("audit_approved", task.auditApproved)
                put("proof_criteria", task.proofCriteria)
                put("raw_proof_criteria", task.rawProofCriteria)
                put("strict_prerequisites", task.requireAllSubtasksFirst)
                put("is_bonus", task.isBonus)
                if (task.projectId != null) put("project_id", task.projectId)
                put("created_at", task.createdAt)
                if (task.completedAt != null) put("completed_at", task.completedAt)
                put("order_index", task.orderIndex)
            }
            tasksArr.put(taskObj)
        } else {
            // Existing task: Targeted in-place update of edited fields ONLY
            taskObj.put("title", task.title)
            taskObj.put("raw_title", task.rawTitle)
            taskObj.put("description", task.description)
            taskObj.put("due_date", task.dueDate)
            taskObj.put("priority_id", task.priorityId)
            taskObj.put("tags", JSONArray(task.tags))
            taskObj.put("status", task.status.name.lowercase())
            taskObj.put("ai_locked", task.aiLocked)
            taskObj.put("audit_approved", task.auditApproved)
            taskObj.put("proof_criteria", task.proofCriteria)
            taskObj.put("raw_proof_criteria", task.rawProofCriteria)
            taskObj.put("strict_prerequisites", task.requireAllSubtasksFirst)
            taskObj.put("is_bonus", task.isBonus)
            if (task.parentId != null) taskObj.put("parent_id", task.parentId) else taskObj.remove("parent_id")
            taskObj.put("project_id", task.projectId ?: "inbox")
            if (task.completedAt != null) taskObj.put("completed_at", task.completedAt) else taskObj.remove("completed_at")
            taskObj.put("order_index", task.orderIndex)
        }
        rawSnapshot.put("updated_at", System.currentTimeMillis())
    }

    private fun markTaskTrashInRawSnapshot(taskId: String) {
        val tasksArr = rawSnapshot.optJSONArray("tasks") ?: return
        for (i in 0 until tasksArr.length()) {
            val obj = tasksArr.getJSONObject(i)
            if (obj.optString("id") == taskId) {
                obj.put("status", "trash")
                break
            }
        }
        rawSnapshot.put("updated_at", System.currentTimeMillis())
    }

    private fun permanentlyRemoveTaskFromRawSnapshot(taskId: String) {
        val tasksArr = rawSnapshot.optJSONArray("tasks") ?: return
        val newArr = JSONArray()
        for (i in 0 until tasksArr.length()) {
            val obj = tasksArr.getJSONObject(i)
            if (obj.optString("id") != taskId) {
                newArr.put(obj)
            }
        }
        rawSnapshot.put("tasks", newArr)
        rawSnapshot.put("updated_at", System.currentTimeMillis())
    }


    // --- Projects and cross-device drafts ---

    fun saveProject(name: String, id: String? = null): Pair<Boolean, String> {
        val trimmed = name.trim()
        if (trimmed.isEmpty() || trimmed.length > 100) return false to "Project name must be 1–100 characters."
        if (id == "inbox") return false to "Inbox cannot be renamed."
        val projectId = id ?: "proj_${System.currentTimeMillis()}_${UUID.randomUUID().toString().take(6)}"
        val arr = rawSnapshot.optJSONArray("projects") ?: JSONArray().also { rawSnapshot.put("projects", it) }
        var existing: JSONObject? = null
        for (i in 0 until arr.length()) if (arr.optJSONObject(i)?.optString("id") == projectId) existing = arr.getJSONObject(i)
        if (id != null && existing == null) return false to "Project no longer exists."
        if (existing != null) existing.put("name", trimmed)
        else arr.put(JSONObject().apply {
            put("id", projectId); put("name", trimmed); put("color", "#3B82F6")
            put("icon", "📁"); put("is_default", false)
        })
        _projects.value = _projects.value.filterNot { it.id == projectId } +
            TaskProject(projectId, trimmed)
        saveLocalSnapshot()
        return true to projectId
    }

    fun deleteProjectToInbox(id: String): Pair<Boolean, String> {
        if (id == "inbox") return false to "Inbox cannot be deleted."
        if (_projects.value.none { it.id == id }) return false to "Project not found."
        val arr = rawSnapshot.optJSONArray("projects") ?: JSONArray()
        val updated = JSONArray()
        for (i in 0 until arr.length()) {
            val item = arr.optJSONObject(i) ?: continue
            if (item.optString("id") != id) updated.put(item)
        }
        rawSnapshot.put("projects", updated)
        _projects.value = _projects.value.filterNot { it.id == id }
        _tasks.value.filter { it.projectId == id }.forEach { task ->
            val updatedTask = task.copy(projectId = "inbox")
            applyTaskToRawSnapshot(updatedTask)
        }
        _tasks.value = _tasks.value.map { if (it.projectId == id) it.copy(projectId = "inbox") else it }
        saveLocalSnapshot()
        return true to "Project deleted; tasks moved to Inbox."
    }

    fun saveTaskDraft(task: Task, editTaskId: String? = null): Pair<Boolean, String> {
        if (task.title.isBlank()) return false to "Enter a title before saving a draft."
        val settingsObj = rawSnapshot.optJSONObject("settings") ?: JSONObject().also { rawSnapshot.put("settings", it) }
        val key = if (editTaskId != null) "edit_draft:$editTaskId" else "task_draft:${task.id}"
        if (editTaskId == null && _taskDrafts.value.count { it.editTaskId == null && it.id != task.id } >= 50)
            return false to "Maximum 50 new-task drafts."
        val fields = JSONObject().apply {
            put("title", task.title.take(500)); put("description", task.description.take(5000))
            put("due_date", task.dueDate); put("parent_id", task.parentId ?: JSONObject.NULL)
            put("project_id", task.projectId ?: "inbox"); put("priority_id", task.priorityId)
            put("tags", JSONArray(task.tags)); put("is_bonus", task.isBonus)
            put("strict_prerequisites", task.requireAllSubtasksFirst)
            put("ai_locked", task.aiLocked); put("criteria", task.proofCriteria.take(5000))
        }
        val now = System.currentTimeMillis()
        settingsObj.put(key, JSONObject().put("saved_at", now).put("fields", fields))
        val draft = TaskDraft(task.id, editTaskId, now, task)
        _taskDrafts.value = (_taskDrafts.value.filterNot { it.id == draft.id && it.editTaskId == editTaskId } + draft)
            .sortedByDescending { it.savedAt }
        saveLocalSnapshot()
        return true to "Draft saved. Sync to make it available on other devices."
    }

    fun removeTaskDraft(draft: TaskDraft) {
        val key = if (draft.editTaskId != null) "edit_draft:${draft.editTaskId}" else "task_draft:${draft.id}"
        rawSnapshot.optJSONObject("settings")?.remove(key)
        _taskDrafts.value = _taskDrafts.value.filterNot { it.id == draft.id && it.editTaskId == draft.editTaskId }
        saveLocalSnapshot()
    }

    // --- Task Engine & Tree Hierarchy ---

    fun isDescendant(potentialChildId: String, ancestorId: String): Boolean {
        if (potentialChildId == ancestorId) return true
        val tasksById = _tasks.value.associateBy { it.id }
        var current = tasksById[potentialChildId]
        while (current?.parentId != null) {
            if (current.parentId == ancestorId) return true
            current = tasksById[current.parentId]
        }
        return false
    }

    fun hasNestedRecurrence(taskId: String, newParentId: String?): Boolean {
        val tasksById = _tasks.value.associateBy { it.id }
        val target = tasksById[taskId] ?: return false
        if (target.recurrence == RecurrenceRule.NONE) return false

        var curParent = newParentId?.let { tasksById[it] }
        while (curParent != null) {
            if (curParent.recurrence != RecurrenceRule.NONE) return true
            curParent = curParent.parentId?.let { tasksById[it] }
        }

        val descendants = getDescendants(taskId)
        return descendants.any { it.recurrence != RecurrenceRule.NONE }
    }

    private fun getDescendants(parentId: String): List<Task> {
        val list = mutableListOf<Task>()
        val children = _tasks.value.filter { it.parentId == parentId && it.status != TaskStatus.TRASH }
        for (child in children) {
            list.add(child)
            list.addAll(getDescendants(child.id))
        }
        return list
    }

    fun upsertTask(task: Task): Pair<Boolean, String?> {
        if (task.parentId != null && isDescendant(task.parentId, task.id)) {
            return Pair(false, "Invalid hierarchy: A task cannot be a subtask of itself or its descendants.")
        }

        if (hasNestedRecurrence(task.id, task.parentId)) {
            return Pair(false, "Nested recurrence is not permitted.")
        }

        val current = _tasks.value.toMutableList()
        val index = current.indexOfFirst { it.id == task.id }
        if (index >= 0) {
            val existing = current[index]
            if (existing.aiLocked && !task.aiLocked) return Pair(false, "AI protection cannot be removed locally.")
            if (existing.requireAllSubtasksFirst && !task.requireAllSubtasksFirst)
                return Pair(false, "Protected prerequisite rules cannot be removed locally.")
            current[index] = task
        } else {
            current.add(task)
        }
        _tasks.value = current

        applyTaskToRawSnapshot(task)
        saveLocalSnapshot()
        return Pair(true, null)
    }

    fun toggleTaskCompletion(taskId: String): Pair<Boolean, String?> {
        val current = _tasks.value.toMutableList()
        val index = current.indexOfFirst { it.id == taskId }
        if (index < 0) return Pair(false, "Task not found.")

        val task = current[index]
        if (task.status == TaskStatus.COMPLETED) {
            // Un-complete
            val updated = task.copy(status = TaskStatus.ACTIVE, completedAt = null, auditApproved = false)
            current[index] = updated
            _tasks.value = current
            applyTaskToRawSnapshot(updated)
            saveLocalSnapshot()
            return Pair(true, null)
        }

        // Rule 8.6: AI-locked tasks cannot be completed locally without approved audit
        if (task.aiLocked && !task.auditApproved) {
            return Pair(false, "AI-locked task cannot be completed without an approved evidence audit.")
        }

        val isBypass = _settings.value.emergencyBypassActive
        if (task.requireAllSubtasksFirst && !isBypass) {
            val descendants = getDescendants(task.id)
            val uncompletedNonBonus = descendants.filter { !it.isBonus && it.status != TaskStatus.COMPLETED }
            if (uncompletedNonBonus.isNotEmpty()) {
                return Pair(false, "Shield active: All subtasks must be completed before completing '${task.title}'.")
            }
        }

        // Never let parent completion silently approve protected descendants.
        val protectedDescendant = getDescendants(task.id).firstOrNull {
            it.status == TaskStatus.ACTIVE && it.aiLocked && !it.auditApproved
        }
        if (protectedDescendant != null)
            return Pair(false, "Complete protected subtask '${protectedDescendant.title}' through its own verified audit first.")

        val now = System.currentTimeMillis()
        val updated = task.copy(status = TaskStatus.COMPLETED, completedAt = now)
        current[index] = updated
        applyTaskToRawSnapshot(updated)

        // Cascade completion to descendants
        val descendants = getDescendants(task.id)
        for (d in descendants) {
            val dIndex = current.indexOfFirst { it.id == d.id }
            if (dIndex >= 0 && current[dIndex].status != TaskStatus.COMPLETED) {
                val dUpdated = current[dIndex].copy(status = TaskStatus.COMPLETED, completedAt = now)
                current[dIndex] = dUpdated
                applyTaskToRawSnapshot(dUpdated)
            }
        }

        _tasks.value = current
        _lastCompletedTask.value = updated
        saveLocalSnapshot()
        return Pair(true, null)
    }

    fun undoCompletion(taskId: String) {
        val current = _tasks.value.toMutableList()
        val index = current.indexOfFirst { it.id == taskId }
        if (index >= 0) {
            val updated = current[index].copy(status = TaskStatus.ACTIVE, completedAt = null)
            current[index] = updated
            _tasks.value = current
            _lastCompletedTask.value = null
            applyTaskToRawSnapshot(updated)
            saveLocalSnapshot()
        }
    }

    fun trashBranch(taskId: String): Pair<Boolean, String?> {
        val current = _tasks.value.toMutableList()
        val index = current.indexOfFirst { it.id == taskId }
        if (index < 0) return Pair(false, "Task not found.")

        val task = current[index]
        val isBypass = _settings.value.emergencyBypassActive
        if (!isBypass) {
            if (task.aiLocked && task.status == TaskStatus.ACTIVE) {
                return Pair(false, "Deleting an active AI-locked task is blocked unless Emergency Bypass is active.")
            }
            if (task.requireAllSubtasksFirst) {
                val descendants = getDescendants(task.id)
                if (descendants.any { it.status == TaskStatus.ACTIVE }) {
                    return Pair(false, "Deleting a shielded task with active subtasks is blocked unless Emergency Bypass is active.")
                }
            }
        }

        val allToTrash = listOf(task.id) + getDescendants(task.id).map { it.id }
        for (i in current.indices) {
            if (current[i].id in allToTrash) {
                current[i] = current[i].copy(status = TaskStatus.TRASH)
                markTaskTrashInRawSnapshot(current[i].id)
            }
        }
        _tasks.value = current
        saveLocalSnapshot()
        return Pair(true, null)
    }

    fun confirmAuditApproval(taskId: String, feedback: String) {
        val current = _tasks.value.toMutableList()
        val index = current.indexOfFirst { it.id == taskId }
        if (index >= 0) {
            val updated = current[index].copy(
                auditApproved = true,
                status = TaskStatus.COMPLETED,
                completedAt = System.currentTimeMillis()
            )
            current[index] = updated
            _tasks.value = current
            applyTaskToRawSnapshot(updated)
            saveLocalSnapshot()
        }
    }

    fun restoreTask(taskId: String) {
        val current = _tasks.value.toMutableList()
        val index = current.indexOfFirst { it.id == taskId }
        if (index >= 0) {
            val updated = current[index].copy(status = TaskStatus.ACTIVE)
            current[index] = updated
            _tasks.value = current
            applyTaskToRawSnapshot(updated)
            saveLocalSnapshot()
        }
    }

    fun permanentlyDeleteTask(taskId: String) {
        _tasks.value = _tasks.value.filter { it.id != taskId }
        permanentlyRemoveTaskFromRawSnapshot(taskId)
        saveLocalSnapshot()
    }

    fun emptyTrash() {
        val trashed = _tasks.value.filter { it.status == TaskStatus.TRASH }
        _tasks.value = _tasks.value.filter { it.status != TaskStatus.TRASH }
        trashed.forEach { permanentlyRemoveTaskFromRawSnapshot(it.id) }
        saveLocalSnapshot()
    }

    // --- Emergency Bypass (12.3) ---

    fun activateEmergencyBypassLocally(): Pair<Boolean, String> =
        Pair(false, "Emergency Bypass must be approved by /emergency on the Worker.")

    private fun checkEmergencyBypassCycleReset() {
        val s = _settings.value
        if (s.emergencyBypassUntil > 0 && System.currentTimeMillis() > s.emergencyBypassUntil) {
            _settings.value = s.copy(emergencyBypassActive = false, emergencyBypassUntil = 0L)
            // UI hint only; Worker owns emergency status and weekly usage.
        }
    }

    // --- Breaks & Pomodoro Local Only ---

    fun addBreak(type: BreakType, duration: Int) {
        val current = _breaks.value.toMutableList()
        current.add(0, BreakSession(type = type, durationMinutes = duration))
        _breaks.value = current
    }

    fun evaluatePhoneAccess(): PhoneAccessDecision {
        val isBypass = _settings.value.emergencyBypassActive
        if (isBypass) return PhoneAccessDecision(allowed = true, reason = "Emergency Bypass active.")
        val todayStr = DateUtils.getLogicalDate(_settings.value.dayStartHour)
        val tasksById = _tasks.value.associateBy { it.id }
        val pendingActive = _tasks.value.any {
            it.status == TaskStatus.ACTIVE && DateUtils.getEffectiveDueDate(it, tasksById).let { d -> d.isBlank() || d <= todayStr }
        }
        return if (!pendingActive) {
            PhoneAccessDecision(allowed = true, reason = "All scheduled focus tasks completed for today.")
        } else {
            PhoneAccessDecision(allowed = false, reason = "Active tasks pending in today's queue.")
        }
    }

    fun startPomodoro() {
        _pomodoro.value = _pomodoro.value.copy(isRunning = true)
    }

    fun pausePomodoro() {
        _pomodoro.value = _pomodoro.value.copy(isRunning = false)
    }

    fun resetPomodoro() {
        val s = _settings.value
        val sec = when (_pomodoro.value.mode) {
            PomodoroMode.WORK -> s.workDurationMinutes * 60
            PomodoroMode.SHORT_BREAK -> s.shortBreakMinutes * 60
            PomodoroMode.LONG_BREAK -> s.longBreakMinutes * 60
        }
        _pomodoro.value = _pomodoro.value.copy(remainingSeconds = sec, isRunning = false)
    }

    fun tickPomodoro() {
        val current = _pomodoro.value
        if (!current.isRunning) return
        if (current.remainingSeconds > 0) {
            _pomodoro.value = current.copy(remainingSeconds = current.remainingSeconds - 1)
        } else {
            if (current.mode == PomodoroMode.WORK) {
                val newSessions = current.completedSessions + 1
                val nextMode = if (newSessions % 4 == 0) PomodoroMode.LONG_BREAK else PomodoroMode.SHORT_BREAK
                val sec = if (nextMode == PomodoroMode.LONG_BREAK) _settings.value.longBreakMinutes * 60 else _settings.value.shortBreakMinutes * 60
                _pomodoro.value = current.copy(
                    mode = nextMode,
                    remainingSeconds = sec,
                    isRunning = false,
                    completedSessions = newSessions
                )
            } else {
                _pomodoro.value = current.copy(
                    mode = PomodoroMode.WORK,
                    remainingSeconds = _settings.value.workDurationMinutes * 60,
                    isRunning = false
                )
            }
        }
    }

    fun switchPomodoroMode(mode: PomodoroMode) {
        val sec = when (mode) {
            PomodoroMode.WORK -> _settings.value.workDurationMinutes * 60
            PomodoroMode.SHORT_BREAK -> _settings.value.shortBreakMinutes * 60
            PomodoroMode.LONG_BREAK -> _settings.value.longBreakMinutes * 60
        }
        _pomodoro.value = _pomodoro.value.copy(mode = mode, remainingSeconds = sec, isRunning = false)
    }

    fun setPomodoroFocusedTask(taskId: String?) {
        _pomodoro.value = _pomodoro.value.copy(currentTaskId = taskId)
    }

    fun startPlanningWindow(): Pair<Boolean, String> =
        Pair(false, "Break planning is server-controlled. Open Breaks to use /breaks.")

    fun lockSchedule(intervals: List<BreakInterval>): Pair<Boolean, String> =
        Pair(false, "Break scheduling is server-controlled. Open Breaks to use /breaks.")

    // --- Distraction Notes (Local Only) ---

    private fun readNotesAndCleanup(): List<DistractionNote> {
        if (!notesFile.exists()) return emptyList()
        val list = mutableListOf<DistractionNote>()
        try {
            val arr = JSONArray(notesFile.readText())
            val now = System.currentTimeMillis()
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                val exp = obj.optLong("expires_at", now + 1)
                if (exp > now) {
                    list.add(
                        DistractionNote(
                            id = obj.getString("id"),
                            title = obj.optString("title").takeIf { it.isNotBlank() },
                            text = obj.getString("text"),
                            timestamp = obj.getLong("timestamp"),
                            expiresAt = exp
                        )
                    )
                }
            }
        } catch (e: Exception) {
            // ignore
        }
        return list
    }

    fun saveDistractionNote(text: String, title: String? = null) {
        val current = _notes.value.toMutableList()
        val note = DistractionNote(text = text, title = title)
        current.add(0, note)
        _notes.value = current

        val arr = JSONArray()
        current.forEach { n ->
            arr.put(JSONObject().apply {
                put("id", n.id)
                put("text", n.text)
                if (n.title != null) put("title", n.title)
                put("timestamp", n.timestamp)
                put("expires_at", n.expiresAt)
            })
        }
        notesFile.writeText(arr.toString())
    }

    fun deleteDistractionNote(id: String) {
        val current = _notes.value.filter { it.id != id }
        _notes.value = current
        val arr = JSONArray()
        current.forEach { n ->
            arr.put(JSONObject().apply {
                put("id", n.id)
                put("text", n.text)
                if (n.title != null) put("title", n.title)
                put("timestamp", n.timestamp)
                put("expires_at", n.expiresAt)
            })
        }
        notesFile.writeText(arr.toString())
    }

    // --- Settings Updates ---

    fun updateSettings(newSettings: UserSettings) {
        val previous = _settings.value
        displayPrefs.edit().putString("language", newSettings.language)
            .putString("theme", newSettings.appearanceTheme).apply()
        _settings.value = newSettings
        // Language/theme are device-only preferences and must not alter cloud state.
        if (newSettings.copy(language = previous.language, appearanceTheme = previous.appearanceTheme) == previous) return
        val settingsObj = rawSnapshot.optJSONObject("settings") ?: JSONObject().also { rawSnapshot.put("settings", it) }
        settingsObj.put("copilot_name", newSettings.copilotName)
        settingsObj.put("task_sort_mode", newSettings.taskSortMode)
        settingsObj.put("is_pro", newSettings.isPro)
        settingsObj.put("use_byok", newSettings.useByok)
        settingsObj.put("day_start_hour", newSettings.dayStartHour)
        settingsObj.put("weekend_start", newSettings.weekendStart)
        settingsObj.put("weekend_end", newSettings.weekendEnd)
        settingsObj.put("break_timezone", newSettings.breakTimezone)
        settingsObj.put("dump_duration", newSettings.dumpDurationSeconds)
        settingsObj.put("strict_criteria_validation", newSettings.strictCriteriaValidation)
        settingsObj.put("sound_effects_enabled", newSettings.soundEffectsEnabled)
        settingsObj.put("haptics_enabled", newSettings.hapticsEnabled)
        saveLocalSnapshot()
    }

    fun rescheduleAllOverdue(newDate: String) {
        val todayStr = DateUtils.getLogicalDate(_settings.value.dayStartHour)
        val current = _tasks.value.toMutableList()
        for (i in current.indices) {
            val t = current[i]
            if (t.status == TaskStatus.ACTIVE && t.dueDate.isNotBlank() && t.dueDate < todayStr) {
                val targetDate = if (t.aiLocked) todayStr else newDate
                val updated = t.copy(dueDate = targetDate)
                current[i] = updated
                applyTaskToRawSnapshot(updated)
            }
        }
        _tasks.value = current
        saveLocalSnapshot()
    }

    fun saveCriteriaPreset(saved: SavedCriteria): Pair<Boolean, String?> {
        val check = CriteriaValidator.validateSavedCriteriaRules(_savedCriteria.value, saved)
        if (!check.first) return check

        val current = _savedCriteria.value.toMutableList()
        current.add(saved)
        _savedCriteria.value = current

        val scArr = rawSnapshot.optJSONArray("saved_criteria") ?: JSONArray().also { rawSnapshot.put("saved_criteria", it) }
        scArr.put(JSONObject().apply {
            put("id", saved.id)
            put("title", saved.title)
            put("criteria", saved.criteria)
            put("score", saved.score)
            put("has_exemplar", saved.hasExemplar)
            if (saved.exemplar != null) put("exemplar", saved.exemplar)
            put("created_at", saved.createdAt)
        })

        saveLocalSnapshot()
        return Pair(true, null)
    }

    // --- Safe Cloud Sync & Gating (Phase 1.2 & 1.3) ---

    suspend fun initialPull(): Pair<Boolean, String> {
        val token = _settings.value.authToken
        if (token.isBlank()) {
            return Pair(false, "Not signed in. Token required to sync.")
        }

        _syncState.value = SyncState.PULLING
        val pullResult = TaskitatorWorkerApi.pullSync(token, _settings.value.username)
        if (pullResult.isSuccess) {
            val remoteSnapshot = pullResult.getOrThrow()
            rawSnapshot = remoteSnapshot
            baseSnapshot = JSONObject(remoteSnapshot.toString())
            baseVersion = remoteSnapshot.optLong("version", 0L)

            getBaseSnapshotFile().writeText(baseSnapshot.toString(2))
            parseSnapshot(rawSnapshot)

            _settings.value = _settings.value.copy(needsInitialPull = false, remoteVersion = baseVersion)
            saveLocalSnapshot(localChange = false)

            _syncState.value = SyncState.UP_TO_DATE
            _syncStatusMessage.value = "Synced with cloud (v$baseVersion)."
            return Pair(true, "Initial sync successful (v$baseVersion).")
        } else {
            val ex = pullResult.exceptionOrNull()
            _syncState.value = SyncState.ERROR
            _syncStatusMessage.value = "Initial pull failed: ${ex?.message}"
            return Pair(false, "Initial pull failed: ${ex?.message}")
        }
    }

    suspend fun performSync(): Pair<Boolean, String> {
        val token = _settings.value.authToken
        if (token.isBlank()) {
            _syncState.value = SyncState.OFFLINE
            return Pair(false, "Not signed in. Token required to sync.")
        }

        if (isSyncInProgress) {
            return Pair(false, "Sync is already in progress.")
        }

        // Rule: First pull is strictly mandatory before any writes can be pushed
        if (_settings.value.needsInitialPull) {
            return initialPull()
        }
        // No local edits: pull only. Never write an unchanged/stale snapshot.
        if (!authPrefs.getBoolean("dirty_${getSafeUserId()}", false)) return initialPull()

        isSyncInProgress = true
        _syncState.value = SyncState.PUSHING
        _syncStatusMessage.value = "Pushing changes to Worker..."

        // Stable retry-safe mutation_id
        val mutationId = pendingMutationId ?: UUID.randomUUID().toString().also { pendingMutationId = it }
        val pushResult = TaskitatorWorkerApi.pushSync(
            snapshot = rawSnapshot,
            baseVersion = baseVersion,
            mutationId = mutationId,
            token = token,
            username = _settings.value.username
        )

        isSyncInProgress = false

        if (pushResult.isSuccess) {
            // Push succeeded: Clear pending mutation ID and pull authoritative server state
            pendingMutationId = null
            pendingPushPayload = null

            val pullResult = TaskitatorWorkerApi.pullSync(token, _settings.value.username)
            if (pullResult.isSuccess) {
                val remoteSnapshot = pullResult.getOrThrow()
                rawSnapshot = remoteSnapshot
                baseSnapshot = JSONObject(remoteSnapshot.toString())
                baseVersion = remoteSnapshot.optLong("version", baseVersion + 1)
                getBaseSnapshotFile().writeText(baseSnapshot.toString(2))
                parseSnapshot(rawSnapshot)
                saveLocalSnapshot(localChange = false)

                _syncState.value = SyncState.UP_TO_DATE
                _syncStatusMessage.value = "Synced (v$baseVersion)."
                return Pair(true, "Sync successful (v$baseVersion).")
            } else {
                _syncState.value = SyncState.UP_TO_DATE
                return Pair(true, "Pushed successfully.")
            }
        } else {
            val ex = pushResult.exceptionOrNull()
            return when (ex) {
                is SyncConflictException -> {
                    _syncState.value = SyncState.CONFLICT
                    handle409Conflict(token)
                }
                is UnauthorizedException -> {
                    _syncState.value = SyncState.ERROR
                    _authState.value = AuthState.SessionExpired("Session expired or revoked (401).")
                    Pair(false, "Unauthorized (401). Please sign in again.")
                }
                is WorkerProtectionException -> {
                    _syncState.value = SyncState.ERROR
                    Pair(false, "Write rejected by Worker protection (403): ${ex.message}")
                }
                is AccountDeletedException -> {
                    _syncState.value = SyncState.ERROR
                    _authState.value = AuthState.AccountDeleted("Account deleted (410).")
                    Pair(false, "Account deleted (410).")
                }
                is RateLimitException -> {
                    _syncState.value = SyncState.ERROR
                    Pair(false, "Push rate limited (429). Retaining pending edits.")
                }
                is ServiceUnavailableException -> {
                    _syncState.value = SyncState.OFFLINE
                    Pair(false, "Server temporarily unavailable (503). Retaining pending edits.")
                }
                else -> {
                    // Retain pendingMutationId for idempotent retry
                    _syncState.value = SyncState.OFFLINE
                    Pair(false, "Network error: ${ex?.message}. Retaining edits.")
                }
            }
        }
    }

    // --- 3-Way Conflict Engine (Phase 1.3) ---

    private suspend fun handle409Conflict(token: String): Pair<Boolean, String> {
        val pullResult = TaskitatorWorkerApi.pullSync(token, _settings.value.username)
        if (pullResult.isFailure) {
            return Pair(false, "Conflict detected (409) but unable to pull latest remote snapshot.")
        }

        val remoteSnapshot = pullResult.getOrThrow()
        val (isCleanMerge, report) = performThreeWayMerge(
            baseObj = baseSnapshot,
            localObj = rawSnapshot,
            remoteObj = remoteSnapshot
        )

        if (isCleanMerge) {
            // Independent disjoint changes rebased automatically!
            rawSnapshot = remoteSnapshot
            baseSnapshot = JSONObject(remoteSnapshot.toString())
            baseVersion = remoteSnapshot.optLong("version", baseVersion + 1)
            getBaseSnapshotFile().writeText(baseSnapshot.toString(2))
            parseSnapshot(rawSnapshot)
            saveLocalSnapshot()

            // Retry push with NEW mutation_id onto the new base_version
            pendingMutationId = UUID.randomUUID().toString()
            return performSync()
        } else {
            // Ambiguous or same-field conflict detected: present to user with backup export
            _syncConflictReport.value = report
            _syncState.value = SyncState.CONFLICT
            return Pair(false, "Sync conflict detected on ${report?.conflictingTasks?.size ?: 1} task(s). Please review.")
        }
    }

    /**
     * 3-Way Merge comparison between Base, Local, and Remote snapshots.
     */
    fun performThreeWayMerge(
        baseObj: JSONObject,
        localObj: JSONObject,
        remoteObj: JSONObject
    ): Pair<Boolean, SyncConflictReport?> {
        val baseTasks = parseTasksArrayToMap(baseObj.optJSONArray("tasks"))
        val localTasks = parseTasksArrayToMap(localObj.optJSONArray("tasks"))
        val remoteTasks = parseTasksArrayToMap(remoteObj.optJSONArray("tasks"))

        val allIds = (baseTasks.keys + localTasks.keys + remoteTasks.keys).toSet()
        val conflicts = mutableListOf<ConflictTaskItem>()
        val mergedTasksArray = JSONArray()

        val protectedFields = setOf("ai_locked", "strict_prerequisites", "audit_approved", "completed_audit_ledger")

        for (id in allIds) {
            val b = baseTasks[id]
            val l = localTasks[id]
            val r = remoteTasks[id]

            if (b == null) {
                // Task added after base
                if (l != null && r == null) {
                    mergedTasksArray.put(JSONObject(l.toString()))
                } else if (r != null && l == null) {
                    mergedTasksArray.put(JSONObject(r.toString()))
                } else if (l != null && r != null) {
                    val conflictingFields = findConflictingFields(l, r)
                    if (conflictingFields.isEmpty()) {
                        mergedTasksArray.put(JSONObject(r.toString()))
                    } else {
                        conflicts.add(createConflictItem(id, l, r, conflictingFields))
                    }
                }
            } else {
                // Task existed in base
                if (l == null && r != null) {
                    val rChanged = !areJsonObjectsEqual(b, r)
                    if (rChanged) {
                        conflicts.add(createConflictItem(id, JSONObject().apply { put("id", id); put("title", "[Deleted locally]"); put("status", "trash") }, r, listOf("delete_vs_edit")))
                    }
                } else if (r == null && l != null) {
                    val lChanged = !areJsonObjectsEqual(b, l)
                    if (lChanged) {
                        conflicts.add(createConflictItem(id, l, JSONObject().apply { put("id", id); put("title", "[Deleted remotely]"); put("status", "trash") }, listOf("delete_vs_edit")))
                    }
                } else if (l != null && r != null) {
                    val lChanged = !areJsonObjectsEqual(b, l)
                    val rChanged = !areJsonObjectsEqual(b, r)

                    if (!lChanged && !rChanged) {
                        mergedTasksArray.put(JSONObject(r.toString()))
                    } else if (lChanged && !rChanged) {
                        // Only local changed -> keep local
                        mergedTasksArray.put(JSONObject(l.toString()))
                    } else if (!lChanged && rChanged) {
                        // Only remote changed -> take remote
                        mergedTasksArray.put(JSONObject(r.toString()))
                    } else {
                        // Both changed: check field by field
                        val conflictingFields = mutableListOf<String>()
                        val mergedTask = JSONObject(r.toString())

                        val allFields = (getJsonKeys(b) + getJsonKeys(l) + getJsonKeys(r)).toSet()
                        for (f in allFields) {
                            if (f in protectedFields) continue
                            val bVal = b.opt(f)?.toString() ?: ""
                            val lVal = l.opt(f)?.toString() ?: ""
                            val rVal = r.opt(f)?.toString() ?: ""

                            if (lVal != bVal && rVal == bVal) {
                                mergedTask.put(f, l.get(f))
                            } else if (rVal != bVal && lVal == bVal) {
                                mergedTask.put(f, r.get(f))
                            } else if (lVal == rVal) {
                                mergedTask.put(f, r.get(f))
                            } else {
                                conflictingFields.add(f)
                            }
                        }

                        if (conflictingFields.isEmpty()) {
                            mergedTasksArray.put(mergedTask)
                        } else {
                            conflicts.add(createConflictItem(id, l, r, conflictingFields))
                        }
                    }
                }
            }
        }

        // Merge independent personal workspace changes too. Tasks alone are not enough:
        // drafts live in settings and projects are a separate top-level collection.
        fun jsonValueEqual(a: Any?, b: Any?): Boolean = (a?.toString() ?: "<absent>") == (b?.toString() ?: "<absent>")
        fun mergeScope(key: String) {
            val b = baseObj.opt(key)
            val l = localObj.opt(key)
            val r = remoteObj.opt(key)
            if (jsonValueEqual(l, r)) return
            if (jsonValueEqual(b, r)) {
                if (l == null) remoteObj.remove(key) else remoteObj.put(key, l)
            } else if (!jsonValueEqual(b, l)) {
                conflicts.add(createConflictItem("scope:$key",
                    JSONObject().put("title", "Local $key"),
                    JSONObject().put("title", "Cloud $key"), listOf("concurrent_change")))
            }
        }
        // Project lists are merged per project id so two devices can create different projects.
        val bp = parseTasksArrayToMap(baseObj.optJSONArray("projects"))
        val lp = parseTasksArrayToMap(localObj.optJSONArray("projects"))
        val rp = parseTasksArrayToMap(remoteObj.optJSONArray("projects"))
        val projectIds = (bp.keys + lp.keys + rp.keys).toSortedSet()
        val mergedProjects = JSONArray()
        for (id in projectIds) {
            val b = bp[id]; val l = lp[id]; val r = rp[id]
            val lChanged = !jsonValueEqual(b, l)
            val rChanged = !jsonValueEqual(b, r)
            if (lChanged && rChanged && !jsonValueEqual(l, r)) {
                conflicts.add(createConflictItem("project:$id", l ?: JSONObject(), r ?: JSONObject(), listOf("project_change")))
            } else (if (lChanged) l else r)?.let { mergedProjects.put(it) }
        }
        // Settings are merged key-by-key, preserving separately synced draft keys.
        val bs = baseObj.optJSONObject("settings") ?: JSONObject()
        val ls = localObj.optJSONObject("settings") ?: JSONObject()
        val rs = remoteObj.optJSONObject("settings") ?: JSONObject()
        val mergedSettings = JSONObject(rs.toString())
        for (key in (getJsonKeys(bs) + getJsonKeys(ls) + getJsonKeys(rs))) {
            val b = bs.opt(key); val l = ls.opt(key); val r = rs.opt(key)
            val lChanged = !jsonValueEqual(b, l)
            val rChanged = !jsonValueEqual(b, r)
            if (lChanged && rChanged && !jsonValueEqual(l, r)) {
                conflicts.add(createConflictItem("setting:$key", JSONObject().put("title", "Local setting $key"),
                    JSONObject().put("title", "Cloud setting $key"), listOf("concurrent_change")))
            } else if (lChanged) {
                if (l == null) mergedSettings.remove(key) else mergedSettings.put(key, l)
            }
        }
        for (key in listOf("recurrence_state", "saved_criteria", "completed_audit_ledger")) mergeScope(key)
        if (conflicts.isEmpty()) {
            remoteObj.put("projects", mergedProjects)
            remoteObj.put("settings", mergedSettings)
            remoteObj.put("tasks", mergedTasksArray)
            return Pair(true, null)
        } else {
            // Save emergency backup
            val backupFile = getBackupFile()
            val backupJson = JSONObject().apply {
                put("timestamp", DateUtils.getIsoTimestamp())
                put("base", bObjSafe(baseObj))
                put("local", lObjSafe(localObj))
                put("remote", rObjSafe(remoteObj))
                put("conflicts_count", conflicts.size)
            }
            backupFile.writeText(backupJson.toString(2))

            val report = SyncConflictReport(
                conflictingTasks = conflicts,
                baseSnapshot = baseObj,
                localSnapshot = localObj,
                remoteSnapshot = remoteObj,
                backupFilePath = backupFile.absolutePath
            )
            return Pair(false, report)
        }
    }

    private fun bObjSafe(obj: JSONObject) = JSONObject(obj.toString())
    private fun lObjSafe(obj: JSONObject) = JSONObject(obj.toString())
    private fun rObjSafe(obj: JSONObject) = JSONObject(obj.toString())

    private fun parseTasksArrayToMap(arr: JSONArray?): Map<String, JSONObject> {
        val map = mutableMapOf<String, JSONObject>()
        if (arr == null) return map
        for (i in 0 until arr.length()) {
            val obj = arr.getJSONObject(i)
            val id = obj.optString("id")
            if (id.isNotBlank()) map[id] = obj
        }
        return map
    }

    private fun getJsonKeys(obj: JSONObject): Set<String> {
        val keys = mutableSetOf<String>()
        val it = obj.keys()
        while (it.hasNext()) keys.add(it.next())
        return keys
    }

    private fun areJsonObjectsEqual(a: JSONObject, b: JSONObject): Boolean {
        val aKeys = getJsonKeys(a)
        val bKeys = getJsonKeys(b)
        if (aKeys != bKeys) return false
        for (k in aKeys) {
            val aVal = a.opt(k)?.toString() ?: ""
            val bVal = b.opt(k)?.toString() ?: ""
            if (aVal != bVal) return false
        }
        return true
    }

    private fun findConflictingFields(l: JSONObject, r: JSONObject): List<String> {
        val conflicts = mutableListOf<String>()
        val keys = getJsonKeys(l) + getJsonKeys(r)
        for (k in keys) {
            val lVal = l.opt(k)?.toString() ?: ""
            val rVal = r.opt(k)?.toString() ?: ""
            if (lVal != rVal) conflicts.add(k)
        }
        return conflicts
    }

    private fun createConflictItem(
        id: String,
        l: JSONObject,
        r: JSONObject,
        conflictingFields: List<String>
    ): ConflictTaskItem {
        return ConflictTaskItem(
            taskId = id,
            localTitle = l.optString("title", "[Untitled]"),
            remoteTitle = r.optString("title", "[Untitled]"),
            localStatus = l.optString("status", "active"),
            remoteStatus = r.optString("status", "active"),
            conflictingFields = conflictingFields,
            localJson = l,
            remoteJson = r
        )
    }

    // --- Conflict Resolution Actions (Phase 1.3) ---

    fun resolveConflictAdoptCloud() {
        val report = _syncConflictReport.value ?: return
        rawSnapshot = report.remoteSnapshot
        baseSnapshot = JSONObject(report.remoteSnapshot.toString())
        baseVersion = report.remoteSnapshot.optLong("version", baseVersion + 1)
        getBaseSnapshotFile().writeText(baseSnapshot.toString(2))
        parseSnapshot(rawSnapshot)
        saveLocalSnapshot()
        _syncConflictReport.value = null
        _syncState.value = SyncState.UP_TO_DATE
        _syncStatusMessage.value = "Adopted cloud copy (v$baseVersion)."
    }

    fun resolveConflictKeepLocal() {
        val report = _syncConflictReport.value ?: return
        val remoteVersion = report.remoteSnapshot.optLong("version", baseVersion)
        baseVersion = remoteVersion
        baseSnapshot = JSONObject(report.remoteSnapshot.toString())
        getBaseSnapshotFile().writeText(baseSnapshot.toString(2))

        // Reapply local tasks into remoteObj to preserve remote top-level & unknown fields
        val remoteTasksMap = parseTasksArrayToMap(report.remoteSnapshot.optJSONArray("tasks")).toMutableMap()
        _tasks.value.forEach { t ->
            val existing = remoteTasksMap[t.id]
            if (existing != null) {
                // Targeted local overwrite on the remote object
                existing.put("title", t.title)
                existing.put("description", t.description)
                existing.put("due_date", t.dueDate)
                existing.put("status", t.status.name.lowercase())
                existing.put("priority_id", t.priorityId)
            } else {
                val newObj = JSONObject().apply {
                    put("id", t.id)
                    put("title", t.title)
                    put("status", t.status.name.lowercase())
                    put("due_date", t.dueDate)
                    put("priority_id", t.priorityId)
                }
                remoteTasksMap[t.id] = newObj
            }
        }

        val tasksArr = JSONArray()
        remoteTasksMap.values.forEach { tasksArr.put(it) }
        rawSnapshot = report.remoteSnapshot.apply { put("tasks", tasksArr) }

        pendingMutationId = UUID.randomUUID().toString()
        saveLocalSnapshot()
        _syncConflictReport.value = null
        _syncState.value = SyncState.IDLE

        // Queue re-push
        scope.launch { performSync() }
    }

    fun resolveConflictPerTask(keepLocalTaskIds: Set<String>) {
        val report = _syncConflictReport.value ?: return
        val remoteVersion = report.remoteSnapshot.optLong("version", baseVersion)
        baseVersion = remoteVersion
        baseSnapshot = JSONObject(report.remoteSnapshot.toString())
        getBaseSnapshotFile().writeText(baseSnapshot.toString(2))

        val remoteTasksMap = parseTasksArrayToMap(report.remoteSnapshot.optJSONArray("tasks")).toMutableMap()
        for (item in report.conflictingTasks) {
            if (item.taskId in keepLocalTaskIds) {
                // Keep local
                remoteTasksMap[item.taskId] = item.localJson
            } else {
                // Keep remote
                remoteTasksMap[item.taskId] = item.remoteJson
            }
        }

        val tasksArr = JSONArray()
        remoteTasksMap.values.forEach { tasksArr.put(it) }
        rawSnapshot = report.remoteSnapshot.apply { put("tasks", tasksArr) }

        parseSnapshot(rawSnapshot)
        saveLocalSnapshot()
        pendingMutationId = UUID.randomUUID().toString()
        _syncConflictReport.value = null
        _syncState.value = SyncState.IDLE

        scope.launch { performSync() }
    }

    fun exportConflictBackup(): String {
        val bFile = getBackupFile()
        return if (bFile.exists()) bFile.readText() else "{}"
    }

    fun dismissConflictReport() {
        _syncConflictReport.value = null
    }
}
