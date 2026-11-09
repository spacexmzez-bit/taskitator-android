package com.example.data.util

import com.example.data.model.Task
import com.example.data.model.CustomPriority

object TaskOrdering {
    /** Sorts only; never mutates server-provided order_index or priority IDs. */
    fun sort(tasks: List<Task>, priorities: List<CustomPriority>, mode: String): List<Task> {
        val ranks = priorities.associate { it.id to it.rank }
        val base = compareBy<Task> { ranks[it.priorityId] ?: Int.MAX_VALUE }
            .thenBy { it.orderIndex }
            .thenBy { it.id }
        val comparator = when (mode) {
            "alphabetical" -> compareBy<Task> { it.title.lowercase() }.then(base)
            "required_first" -> compareByDescending<Task> { it.aiLocked || it.requireAllSubtasksFirst }.then(base)
            else -> base
        }
        return tasks.sortedWith(comparator)
    }
}
