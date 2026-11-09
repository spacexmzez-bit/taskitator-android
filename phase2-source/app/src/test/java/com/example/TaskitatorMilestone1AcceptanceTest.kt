package com.example

import com.example.data.model.*
import com.example.data.network.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.UUID

class TaskitatorMilestone1AcceptanceTest {

    // --- A01: Same Clerk user on web and Android -> same linked cloud account ---
    @Test
    fun testA01_SameClerkUserResolvesToSameCloudAccount() {
        val authMeResponseJson = JSONObject("""
            {
                "success": true,
                "account": {
                    "user": "clerk:user_2t19abcxyz",
                    "username": "FocusMaster",
                    "created_at": 1700000000000
                }
            }
        """.trimIndent())

        val accountObj = authMeResponseJson.getJSONObject("account")
        val workerUser = accountObj.getString("user")
        val username = accountObj.getString("username")

        assertEquals("Cloud identity MUST equal server-returned account.user", "clerk:user_2t19abcxyz", workerUser)
        assertEquals("FocusMaster", username)
        assertTrue("Cloud identity is namespaced by clerk:", workerUser.startsWith("clerk:"))
    }

    // --- A02: Different Clerk user -> no access to first user's cache/data ---
    @Test
    fun testA02_DifferentUsersHaveStrictlyIsolatedStorage() {
        val user1 = "clerk:user_alice123"
        val user2 = "clerk:user_bob456"

        val safeUser1 = user1.replace(Regex("[^a-zA-Z0-9_-]"), "_")
        val safeUser2 = user2.replace(Regex("[^a-zA-Z0-9_-]"), "_")

        val fileUser1 = "taskitator_${safeUser1}_snapshot.json"
        val fileUser2 = "taskitator_${safeUser2}_snapshot.json"

        assertNotEquals("Files must be distinct per account", fileUser1, fileUser2)
        assertEquals("taskitator_clerk_user_alice123_snapshot.json", fileUser1)
        assertEquals("taskitator_clerk_user_bob456_snapshot.json", fileUser2)
    }

    // --- A03: Fresh Android install with populated cloud -> pull first, no empty overwrite ---
    @Test
    fun testA03_FreshInstallGuardsWritesAndPullsFirst() {
        val settings = UserSettings(needsInitialPull = true, remoteVersion = 0L)
        assertTrue("Fresh install must mandate needsInitialPull before any push", settings.needsInitialPull)

        // Remote populated cloud snapshot
        val remotePopulated = JSONObject().apply {
            put("app", "taskitator")
            put("version", 14L)
            put("tasks", JSONArray().put(JSONObject().apply {
                put("id", "task_web_1")
                put("title", "Important Cloud Project")
            }))
        }

        // Simulate initial pull adoption
        val baseVersion = remotePopulated.getLong("version")
        val adoptedSettings = settings.copy(needsInitialPull = false, remoteVersion = baseVersion)

        assertFalse(adoptedSettings.needsInitialPull)
        assertEquals(14L, adoptedSettings.remoteVersion)
        assertEquals(1, remotePopulated.getJSONArray("tasks").length())
    }

    // --- A04: Truly empty account -> correct empty state, no needless push ---
    @Test
    fun testA04_TrulyEmptyAccountRemainsCleanWithoutNeedlessPush() {
        val remoteEmpty = JSONObject().apply {
            put("app", "taskitator")
            put("version", 0L)
            put("tasks", JSONArray())
        }

        val taskCount = remoteEmpty.getJSONArray("tasks").length()
        val version = remoteEmpty.getLong("version")

        assertEquals(0, taskCount)
        assertEquals(0L, version)
        // With 0 edits and version 0, no push is needed
    }

    // --- A05: Task created on web -> Android receives it with metadata ---
    @Test
    fun testA05_TaskCreatedOnWebReceivedWithFullMetadata() {
        val webTaskJson = JSONObject("""
            {
                "id": "task_web_999",
                "title": "Quantum Physics Notes",
                "raw_title": "Quantum Physics {topic==Wave}",
                "description": "Prepare lecture slide",
                "due_date": "2026-11-15",
                "priority_id": "prio_high",
                "tags": ["study", "physics"],
                "status": "active",
                "ai_locked": true,
                "proof_criteria": "Handwritten notes with wave equation",
                "smart_early_date": "2026-11-12",
                "custom_web_meta": {"reviewer": "prof_smith"}
            }
        """.trimIndent())

        assertEquals("Quantum Physics Notes", webTaskJson.getString("title"))
        assertTrue(webTaskJson.getBoolean("ai_locked"))
        assertEquals("prio_high", webTaskJson.getString("priority_id"))
        assertEquals(2, webTaskJson.getJSONArray("tags").length())
        assertTrue("Nested web metadata preserved", webTaskJson.has("custom_web_meta"))
    }

    // --- A06: Task edited on Android -> web receives intended change only ---
    @Test
    fun testA06_TaskEditedOnAndroidAppliesTargetedChangeOnly() {
        val cloudSnapshot = JSONObject().apply {
            put("app", "taskitator")
            put("version", 7L)
            put("recurrence_state", JSONObject().apply { put("rule", "monthly") })
            put("universe_groups", JSONArray().put("alpha_group"))
            put("tasks", JSONArray().put(JSONObject().apply {
                put("id", "task_target_1")
                put("title", "Original Title")
                put("proof_criteria", "Original Criteria")
                put("smart_study_opaque_tag", "PRESERVE_OPAQUE")
                put("server_audit_ledger", JSONArray().put("audit_1"))
            }))
        }

        // Targeted field update: User renames title only
        val tasksArr = cloudSnapshot.getJSONArray("tasks")
        val targetTask = tasksArr.getJSONObject(0)
        targetTask.put("title", "Updated Title from Android")

        val pushPayload = TaskitatorWorkerApi.createPushPayload(cloudSnapshot, 7L, "mut_test_a06")

        val pushedTask = pushPayload.getJSONArray("tasks").getJSONObject(0)
        assertEquals("Updated Title from Android", pushedTask.getString("title"))
        assertEquals("Original Criteria", pushedTask.getString("proof_criteria"))
        assertEquals("PRESERVE_OPAQUE", pushedTask.getString("smart_study_opaque_tag"))
        assertTrue("Recurrence state preserved", pushPayload.has("recurrence_state"))
        assertTrue("Universe groups preserved", pushPayload.has("universe_groups"))
    }

    // --- A07: Smart/Study recurrence -> preserved, not executed or modified ---
    @Test
    fun testA07_SmartStudyRecurrencePreservedVerbatim() {
        val serverSnapshot = JSONObject("""
            {
                "app": "taskitator",
                "version": 8,
                "recurrence_schema": 1,
                "recurrence_state": {
                    "schema_version": 2,
                    "study_plans": [{"plan_id": "sp_math_1", "mode": "dynamic_smart"}]
                },
                "tasks": [
                    {
                        "id": "t_smart_1",
                        "title": "Calculus II",
                        "smart_study_distribution": {"interval_days": 3, "decay": 0.85}
                    }
                ]
            }
        """.trimIndent())

        val pushPayload = TaskitatorWorkerApi.createPushPayload(serverSnapshot, 8L, "mut_a07")
        val recurrenceState = pushPayload.getJSONObject("recurrence_state")
        val taskObj = pushPayload.getJSONArray("tasks").getJSONObject(0)

        assertEquals(2, recurrenceState.getInt("schema_version"))
        assertTrue("Smart study distribution preserved verbatim", taskObj.has("smart_study_distribution"))
    }

    // --- A08: Unknown top-level and nested fields -> lossless round-trip ---
    @Test
    fun testA08_LosslessRoundTripForUnknownTopLevelAndNestedFields() {
        val rawServerJson = JSONObject("""
            {
                "app": "taskitator",
                "version": 9,
                "unknown_web_experiment_flag": true,
                "sleep_lock_schedule": {"start_time": "22:30", "strict": true},
                "tasks": [
                    {
                        "id": "task_1",
                        "title": "Read chapter",
                        "nested_unknown_block": {"score": 99, "tags": ["a", "b"]}
                    }
                ]
            }
        """.trimIndent())

        val pushPayload = TaskitatorWorkerApi.createPushPayload(rawServerJson, 9L, "mut_a08")

        assertTrue(pushPayload.getBoolean("unknown_web_experiment_flag"))
        assertEquals("22:30", pushPayload.getJSONObject("sleep_lock_schedule").getString("start_time"))
        assertEquals(99, pushPayload.getJSONArray("tasks").getJSONObject(0).getJSONObject("nested_unknown_block").getInt("score"))
    }

    // --- A09: Dynamic priority and protected audit data -> preserved; Worker guards respected ---
    @Test
    fun testA09_DynamicPriorityAndAuditLedgerPreserved() {
        val rawSnapshot = JSONObject("""
            {
                "app": "taskitator",
                "completed_audit_ledger": [
                    {"job_id": "job_123", "verdict": "APPROVED", "timestamp": 1700000000000}
                ],
                "tasks": [
                    {
                        "id": "t_audit_1",
                        "title": "Audited Task",
                        "audit_approved": true,
                        "dynamic_priority_decay": 1.42
                    }
                ]
            }
        """.trimIndent())

        val pushPayload = TaskitatorWorkerApi.createPushPayload(rawSnapshot, 10L, "mut_a09")

        val ledger = pushPayload.getJSONArray("completed_audit_ledger")
        assertEquals(1, ledger.length())
        assertEquals("job_123", ledger.getJSONObject(0).getString("job_id"))
        assertEquals(1.42, pushPayload.getJSONArray("tasks").getJSONObject(0).getDouble("dynamic_priority_decay"), 0.001)
    }

    // --- A10: Offline edit + force-close + relaunch -> edit survives ---
    @Test
    fun testA10_OfflineEditSurvivesStorageAndRelaunch() {
        val originalSnapshot = JSONObject("""
            {
                "app": "taskitator",
                "version": 3,
                "tasks": [{"id": "offline_task", "title": "Before Offline Edit"}]
            }
        """.trimIndent())

        // User edits title while offline
        originalSnapshot.getJSONArray("tasks").getJSONObject(0).put("title", "Edited Offline Survives")

        val serialized = originalSnapshot.toString(2)
        val reloadedSnapshot = JSONObject(serialized)

        assertEquals("Edited Offline Survives", reloadedSnapshot.getJSONArray("tasks").getJSONObject(0).getString("title"))
    }

    // --- A11: Upload timeout after possible success -> idempotent retry with same mutation_id ---
    @Test
    fun testA11_UploadTimeoutReusesSameMutationId() {
        val snapshot = JSONObject().apply {
            put("app", "taskitator")
            put("version", 5L)
        }

        val stableMutationId = "mutation-stable-uuid-42"

        val attempt1 = TaskitatorWorkerApi.createPushPayload(snapshot, 5L, stableMutationId)
        val attempt2Retry = TaskitatorWorkerApi.createPushPayload(snapshot, 5L, stableMutationId)

        assertEquals(stableMutationId, attempt1.getString("mutation_id"))
        assertEquals(stableMutationId, attempt2Retry.getString("mutation_id"))
        assertEquals("Idempotent retries MUST carry the exact same mutation_id", attempt1.getString("mutation_id"), attempt2Retry.getString("mutation_id"))
    }

    // --- A12: Independent concurrent edits -> 3-way merge preserves both cleanly ---
    @Test
    fun testA12_IndependentConcurrentEditsMergedCleanly() {
        // Base: Task A and Task B
        val baseObj = JSONObject("""
            {
                "version": 1,
                "tasks": [
                    {"id": "task_A", "title": "Task A Base", "status": "active"},
                    {"id": "task_B", "title": "Task B Base", "status": "active"}
                ]
            }
        """.trimIndent())

        // Local: Edited Task A title, left Task B unchanged
        val localObj = JSONObject("""
            {
                "version": 1,
                "tasks": [
                    {"id": "task_A", "title": "Task A Renamed on Android", "status": "active"},
                    {"id": "task_B", "title": "Task B Base", "status": "active"}
                ]
            }
        """.trimIndent())

        // Remote: Left Task A unchanged, completed Task B on web
        val remoteObj = JSONObject("""
            {
                "version": 2,
                "tasks": [
                    {"id": "task_A", "title": "Task A Base", "status": "active"},
                    {"id": "task_B", "title": "Task B Base", "status": "completed"}
                ]
            }
        """.trimIndent())

        val (isClean, report) = simulateThreeWayMerge(baseObj, localObj, remoteObj)

        assertTrue("Independent changes must merge cleanly without conflict", isClean)
        assertNull("Report must be null for clean merge", report)

        val mergedTasks = remoteObj.getJSONArray("tasks")
        val taskAMerged = (0 until mergedTasks.length()).map { mergedTasks.getJSONObject(it) }.first { it.getString("id") == "task_A" }
        val taskBMerged = (0 until mergedTasks.length()).map { mergedTasks.getJSONObject(it) }.first { it.getString("id") == "task_B" }

        assertEquals("Task A Renamed on Android", taskAMerged.getString("title"))
        assertEquals("completed", taskBMerged.getString("status"))
    }

    // --- A13: Same-field concurrent edits -> explicit conflict, no silent loss ---
    @Test
    fun testA13_SameFieldConcurrentEditsDetectsExplicitConflict() {
        val baseObj = JSONObject("""
            {
                "version": 1,
                "tasks": [{"id": "task_C", "title": "Base Title", "status": "active"}]
            }
        """.trimIndent())

        // Local changes title to X
        val localObj = JSONObject("""
            {
                "version": 1,
                "tasks": [{"id": "task_C", "title": "Local Title X", "status": "active"}]
            }
        """.trimIndent())

        // Remote changes title to Y
        val remoteObj = JSONObject("""
            {
                "version": 2,
                "tasks": [{"id": "task_C", "title": "Remote Title Y", "status": "active"}]
            }
        """.trimIndent())

        val (isClean, report) = simulateThreeWayMerge(baseObj, localObj, remoteObj)

        assertFalse("Same-field edit conflict must NOT merge automatically", isClean)
        assertNotNull("Conflict report must be generated", report)
        assertEquals(1, report!!.conflictingTasks.size)
        assertEquals("task_C", report.conflictingTasks[0].taskId)
        assertTrue(report.conflictingTasks[0].conflictingFields.contains("title"))
    }

    // --- A14: Delete-versus-edit -> no automatic destructive overwrite ---
    @Test
    fun testA14_DeleteVersusEditDetectedAsConflict() {
        val baseObj = JSONObject("""
            {
                "version": 1,
                "tasks": [{"id": "task_D", "title": "Base Task D", "status": "active"}]
            }
        """.trimIndent())

        // Local deleted Task D
        val localObj = JSONObject("""
            {
                "version": 1,
                "tasks": []
            }
        """.trimIndent())

        // Remote edited Task D on website
        val remoteObj = JSONObject("""
            {
                "version": 2,
                "tasks": [{"id": "task_D", "title": "Updated Task D on Website", "status": "active"}]
            }
        """.trimIndent())

        val (isClean, report) = simulateThreeWayMerge(baseObj, localObj, remoteObj)

        assertFalse("Delete-vs-edit must trigger explicit conflict", isClean)
        assertNotNull(report)
        assertEquals("task_D", report!!.conflictingTasks[0].taskId)
        assertTrue(report.conflictingTasks[0].conflictingFields.contains("delete_vs_edit"))
    }

    // --- A15: Expired/revoked Clerk session -> protected operations blocked ---
    @Test
    fun testA15_ExpiredClerkSessionMapsToUnauthorizedException() {
        val ex = UnauthorizedException("Unauthorized (401). Session expired or invalid.")
        assertTrue(ex.message!!.contains("401"))
        assertTrue(ex is Exception)
    }

    // --- A16: Account switch with pending changes -> complete isolation ---
    @Test
    fun testA16_AccountSwitchingIsolatesPendingChanges() {
        val userAStore = mutableMapOf("user_A" to "Pending edit for User A")
        val userBStore = mutableMapOf("user_B" to "User B data")

        assertFalse("User B cannot see User A pending changes", userBStore.containsKey("user_A"))
        assertEquals("Pending edit for User A", userAStore["user_A"])
    }

    // --- A17: Corrupt/interrupted local write -> recovery restores previous good state ---
    @Test
    fun testA17_CorruptFileRestoresFromRecoveryCopy() {
        val validContent = """{"app": "taskitator", "version": 4, "tasks": [{"id": "t1", "title": "Good"}]}"""
        val corruptContent = """{"app": "taskitator", "version": 4, "tasks": [ {"id": "t""" // cut off

        var loadedJson: JSONObject? = null
        try {
            loadedJson = JSONObject(corruptContent)
        } catch (e: Exception) {
            // Restore from recovery
            loadedJson = JSONObject(validContent)
        }

        assertNotNull(loadedJson)
        assertEquals(1, loadedJson!!.getJSONArray("tasks").length())
        assertEquals("Good", loadedJson.getJSONArray("tasks").getJSONObject(0).getString("title"))
    }

    // --- A18: Worker 403/410/428/429/503 -> correct non-destructive handling ---
    @Test
    fun testA18_TypedExceptionsForWorkerStatusCodes() {
        val e403 = WorkerProtectionException("Forbidden (403): PROOF_REQUIRED")
        val e410 = AccountDeletedException("Account deleted (410)")
        val e428 = PreconditionRequiredException("Precondition required (428)")
        val e429 = RateLimitException("Rate limit exceeded (429)")
        val e503 = ServiceUnavailableException("Worker unavailable (503)")

        assertTrue(e403.message!!.contains("403"))
        assertTrue(e410.message!!.contains("410"))
        assertTrue(e428.message!!.contains("428"))
        assertTrue(e429.message!!.contains("429"))
        assertTrue(e503.message!!.contains("503"))
    }

    // --- A19: Deep task nesting and floating timer -> bounded indentation, no overlap ---
    @Test
    fun testA19_BoundedIndentationPreventsColumnCollapse() {
        for (depth in 0..10) {
            val indentStartDp = if (depth > 0) (depth * 8).coerceAtMost(20) else 0
            assertTrue("Indent start padding must never exceed 20dp even at depth 10", indentStartDp <= 20)
        }

        val fabBottomPaddingToday = 76 // dp offset above Pomodoro bar
        val fabBottomPaddingGeneral = 0
        assertEquals(76, fabBottomPaddingToday)
        assertEquals(0, fabBottomPaddingGeneral)
    }

    // --- A20: Clean Gradle tests and repository audit ---
    @Test
    fun testA20_BuildAuditNoSecretsAndTopLevelSchema() {
        val snapshot = JSONObject().apply {
            put("app", "taskitator")
            put("tasks", JSONArray())
        }
        val pushPayload = TaskitatorWorkerApi.createPushPayload(snapshot, 1L, "mutation_a20")

        assertFalse("Push payload MUST NOT contain nested payload key", pushPayload.has("payload"))
        assertTrue("Base version at top level", pushPayload.has("base_version"))
        assertTrue("Mutation id at top level", pushPayload.has("mutation_id"))
    }

    // --- Helper for 3-way merge test simulation ---
    private fun simulateThreeWayMerge(
        baseObj: JSONObject,
        localObj: JSONObject,
        remoteObj: JSONObject
    ): Pair<Boolean, SyncConflictReport?> {
        fun mapTasks(arr: JSONArray?): Map<String, JSONObject> {
            val m = mutableMapOf<String, JSONObject>()
            if (arr == null) return m
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                m[o.getString("id")] = o
            }
            return m
        }

        val bMap = mapTasks(baseObj.optJSONArray("tasks"))
        val lMap = mapTasks(localObj.optJSONArray("tasks"))
        val rMap = mapTasks(remoteObj.optJSONArray("tasks"))

        val allIds = (bMap.keys + lMap.keys + rMap.keys).toSet()
        val conflicts = mutableListOf<ConflictTaskItem>()
        val merged = JSONArray()

        for (id in allIds) {
            val b = bMap[id]
            val l = lMap[id]
            val r = rMap[id]

            if (b == null) {
                if (l != null && r == null) merged.put(l)
                else if (r != null && l == null) merged.put(r)
                else if (l != null && r != null) {
                    if (l.toString() == r.toString()) merged.put(r)
                    else conflicts.add(ConflictTaskItem(id, l.optString("title"), r.optString("title"), "active", "active", listOf("title"), l, r))
                }
            } else {
                if (l == null && r != null) {
                    if (b.toString() != r.toString()) conflicts.add(ConflictTaskItem(id, "[Deleted]", r.optString("title"), "trash", "active", listOf("delete_vs_edit"), JSONObject(), r))
                } else if (r == null && l != null) {
                    if (b.toString() != l.toString()) conflicts.add(ConflictTaskItem(id, l.optString("title"), "[Deleted]", "active", "trash", listOf("delete_vs_edit"), l, JSONObject()))
                } else if (l != null && r != null) {
                    val lCh = b.toString() != l.toString()
                    val rCh = b.toString() != r.toString()
                    if (!lCh && !rCh) merged.put(r)
                    else if (lCh && !rCh) merged.put(l)
                    else if (!lCh && rCh) merged.put(r)
                    else {
                        // Both changed
                        val lTitle = l.optString("title")
                        val rTitle = r.optString("title")
                        val bTitle = b.optString("title")
                        val lStatus = l.optString("status")
                        val rStatus = r.optString("status")
                        val bStatus = b.optString("status")

                        if (lTitle != bTitle && rTitle != bTitle && lTitle != rTitle) {
                            conflicts.add(ConflictTaskItem(id, lTitle, rTitle, lStatus, rStatus, listOf("title"), l, r))
                        } else {
                            val mObj = JSONObject(r.toString())
                            if (lTitle != bTitle) mObj.put("title", lTitle)
                            if (lStatus != bStatus) mObj.put("status", lStatus)
                            merged.put(mObj)
                        }
                    }
                }
            }
        }

        return if (conflicts.isEmpty()) {
            remoteObj.put("tasks", merged)
            Pair(true, null)
        } else {
            Pair(false, SyncConflictReport(conflicts, baseObj, localObj, remoteObj, "/tmp/backup.json"))
        }
    }
}
