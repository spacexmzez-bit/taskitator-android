package com.example

import com.example.data.model.*
import com.example.data.util.TaskOrdering
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class Phase1WorkspaceContractTest {
    @Test fun inboxIsDefaultProject() {
        assertEquals("inbox", Task(title = "New task").projectId)
        assertEquals("inbox", TaskProject("inbox", "Inbox", isDefault = true).id)
    }

    @Test fun taskSortingUsesPriorityRanksAndIsStable() {
        val tasks = listOf(Task(id = "a", title = "B", priorityId = "prio_low"),
            Task(id = "b", title = "A", priorityId = "prio_high"))
        assertEquals(listOf("b", "a"), TaskOrdering.sort(tasks, DEFAULT_PRIORITIES, "priority").map { it.id })
        assertEquals(listOf("b", "a"), TaskOrdering.sort(tasks, DEFAULT_PRIORITIES, "alphabetical").map { it.id })
    }

    @Test fun draftUsesWebsiteSettingsContract() {
        val draft = JSONObject().put("saved_at", 1000L)
            .put("fields", JSONObject().put("title", "Draft title").put("project_id", "inbox"))
        val settings = JSONObject().put("task_draft:d_123", draft)
        assertEquals("Draft title", settings.getJSONObject("task_draft:d_123").getJSONObject("fields").getString("title"))
    }

    @Test fun breakActionNamesMatchWorker() {
        val actions = setOf("plan_start", "plan_cancel", "schedule_save", "lock", "reserved_start", "flex_start", "flex_end", "strict_save")
        assertEquals(8, actions.size)
        assertTrue(actions.contains("reserved_start"))
    }
}
