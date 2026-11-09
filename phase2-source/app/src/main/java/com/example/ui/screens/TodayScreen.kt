package com.example.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.model.BreakPlanningStateEnum
import com.example.data.model.PomodoroMode
import com.example.data.model.Task
import com.example.data.model.TaskStatus
import com.example.data.util.DateUtils
import com.example.data.util.TaskOrdering
import com.example.ui.components.BatchRescheduleDialog
import com.example.ui.components.FloatingPomodoroBar
import com.example.ui.components.PomodoroFullscreenDialog
import com.example.ui.components.TaskItemCard
import com.example.ui.theme.*
import com.example.ui.viewmodel.TaskitatorViewModel
import kotlinx.coroutines.delay
import java.util.Calendar

@Composable
fun TodayScreen(
    viewModel: TaskitatorViewModel,
    onOpenCreateTask: () -> Unit,
    modifier: Modifier = Modifier
) {
    val tasks by viewModel.tasks.collectAsState()
    val settings by viewModel.settings.collectAsState()
    val isSelectMode by viewModel.isSelectMode.collectAsState()
    val selectedTaskIds by viewModel.selectedTaskIds.collectAsState()
    val overdueCount by viewModel.overdueTasksCount.collectAsState()
    val priorityFilter by viewModel.priorityFilter.collectAsState()
    val tagFilter by viewModel.tagFilter.collectAsState()
    val statusBanner by viewModel.statusBannerMessage.collectAsState()
    val pomodoroState by viewModel.pomodoroState.collectAsState()
    val breakPlanning by viewModel.breakPlanning.collectAsState()
    val lastCompletedTask by viewModel.repository.lastCompletedTask.collectAsState()

    val authState by viewModel.authState.collectAsState()
    val syncState by viewModel.syncState.collectAsState()
    val syncConflictReport by viewModel.syncConflictReport.collectAsState()

    val todayDate = remember(settings.dayStartHour) { DateUtils.getLogicalDate(settings.dayStartHour) }
    val tasksById = remember(tasks) { tasks.associateBy { it.id } }

    // Helper: belongsToCurrentView for Today (8.3)
    fun taskBelongsToToday(t: Task): Boolean {
        if (t.status == TaskStatus.TRASH) return false
        val effDate = DateUtils.getEffectiveDueDate(t, tasksById)
        if (effDate == "today" || (effDate.isNotBlank() && effDate <= todayDate)) return true
        // Check if any non-trash descendant belongs
        val children = tasks.filter { it.parentId == t.id && it.status != TaskStatus.TRASH }
        return children.any { taskBelongsToToday(it) }
    }

    val todayRootTasks = remember(tasks, priorityFilter, tagFilter, todayDate, settings.taskSortMode, settings.customPriorities) {
        tasks.filter { task ->
            task.status != TaskStatus.TRASH &&
            task.parentId == null &&
            taskBelongsToToday(task) &&
            (priorityFilter == null || task.priorityId == priorityFilter) &&
            (tagFilter == null || tagFilter in task.tags)
        }.let { TaskOrdering.sort(it, settings.customPriorities, settings.taskSortMode) }
    }

    var showRescheduleModal by remember { mutableStateOf(false) }

    // 10-second undo countdown
    var undoRemainingSeconds by remember { mutableIntStateOf(0) }
    LaunchedEffect(lastCompletedTask) {
        if (lastCompletedTask != null) {
            undoRemainingSeconds = 10
            while (undoRemainingSeconds > 0) {
                delay(1000L)
                undoRemainingSeconds -= 1
            }
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp)
    ) {
        Spacer(modifier = Modifier.height(12.dp))

        // Header: Title, logical date, and action icons (No search bar per Rule 4)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(
                    text = "Today's Focus",
                    style = MaterialTheme.typography.headlineMedium,
                    color = TextPrimary,
                    fontWeight = FontWeight.ExtraBold
                )
                Text(
                    text = "$todayDate (Day starts at ${String.format("%02d:00", settings.dayStartHour)})",
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextSecondary
                )
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                // Multi-select toggle
                IconButton(
                    onClick = { viewModel.toggleSelectMode() },
                    modifier = Modifier.testTag("toggle_select_mode_button")
                ) {
                    Text(if (isSelectMode) "☑" else "☐", fontSize = 20.sp, color = if (isSelectMode) ElectricBlue else TextSecondary)
                }

                // Filter toggle
                IconButton(
                    onClick = { viewModel.setFilterModalVisible(true) },
                    modifier = Modifier.testTag("filter_button")
                ) {
                    Text("🔍", fontSize = 18.sp)
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        // Status or Error Feedback Banner (e.g. Shield protection alert)
        if (statusBanner != null) {
            Surface(
                shape = RoundedCornerShape(8.dp),
                color = PriorityP1.copy(alpha = 0.15f),
                border = androidx.compose.foundation.BorderStroke(1.dp, PriorityP1.copy(alpha = 0.4f)),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 8.dp)
            ) {
                Row(
                    modifier = Modifier.padding(10.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(statusBanner!!, style = MaterialTheme.typography.bodyMedium, color = TextPrimary, modifier = Modifier.weight(1f))
                    IconButton(onClick = { viewModel.clearStatusMessage() }, modifier = Modifier.size(24.dp)) {
                        Icon(Icons.Default.Close, contentDescription = "Dismiss", tint = TextSecondary, modifier = Modifier.size(16.dp))
                    }
                }
            }
        }

        // 10-second Undo Grace Period Snackbar (VERIFIED 8.6)
        if (lastCompletedTask != null && undoRemainingSeconds > 0) {
            Surface(
                shape = RoundedCornerShape(8.dp),
                color = DarkSurfaceElevated,
                border = androidx.compose.foundation.BorderStroke(1.dp, SuccessGreen),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 8.dp)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Completed '${lastCompletedTask!!.title}' (${undoRemainingSeconds}s)",
                        style = MaterialTheme.typography.bodyMedium,
                        color = TextPrimary,
                        modifier = Modifier.weight(1f)
                    )
                    TextButton(
                        onClick = { viewModel.repository.undoCompletion(lastCompletedTask!!.id) },
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                    ) {
                        Text("Undo", color = CyanAccent, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }

        // Break rules are server-authoritative. Never show local planning as truth.
        val breakLabel = "Breaks · Open controls"

        Surface(
            shape = RoundedCornerShape(10.dp),
            color = CyanAccent.copy(alpha = 0.1f),
            border = androidx.compose.foundation.BorderStroke(
                1.dp,
                CyanAccent.copy(alpha = 0.3f)
            ),
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 8.dp)
                .clickable { viewModel.navigateTo(com.example.ui.viewmodel.AppScreen.BREAKS) }
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    Text("☕", fontSize = 16.sp)
                    Column {
                        Text(
                            text = breakLabel,
                            style = MaterialTheme.typography.labelMedium,
                            color = CyanAccent,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "Open server-controlled breaks",
                            style = MaterialTheme.typography.labelSmall,
                            color = TextMuted,
                            fontSize = 10.sp
                        )
                    }
                }
                Text("Manage ›", color = CyanAccent, fontSize = 12.sp, fontWeight = FontWeight.Bold)
            }
        }

        // Overdue Reschedule Warning Bar (AI-locked can only roll over to today per 8.3)
        if (overdueCount > 0) {
            Surface(
                shape = RoundedCornerShape(8.dp),
                color = PriorityP1.copy(alpha = 0.12f),
                border = androidx.compose.foundation.BorderStroke(1.dp, PriorityP1.copy(alpha = 0.4f)),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 8.dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "⚠️ $overdueCount overdue tasks",
                        style = MaterialTheme.typography.labelMedium,
                        color = PriorityP1,
                        fontWeight = FontWeight.Bold
                    )
                    OutlinedButton(
                        onClick = { showRescheduleModal = true },
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = PriorityP1),
                        border = androidx.compose.foundation.BorderStroke(1.dp, PriorityP1.copy(alpha = 0.5f))
                    ) {
                        Text("📅 Reschedule all", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }

        // Active Emergency Banner (12.3)
        if (settings.emergencyBypassActive) {
            Surface(
                shape = RoundedCornerShape(8.dp),
                color = PriorityP1.copy(alpha = 0.2f),
                border = androidx.compose.foundation.BorderStroke(1.dp, PriorityP1),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 8.dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("🚨 Emergency Mode Active: Device guardrails bypassed (${settings.emergencyBypassUsesRemaining}/4 uses remaining)", style = MaterialTheme.typography.labelMedium, color = TextPrimary, fontWeight = FontWeight.Bold)
                }
            }
        }

        // Milestone 1 Sync & Auth Status Banners
        when {
            syncConflictReport != null -> {
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = WarningAmber.copy(alpha = 0.2f),
                    border = androidx.compose.foundation.BorderStroke(1.dp, WarningAmber),
                    modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = "⚠️ Cloud conflict (${syncConflictReport!!.conflictingTasks.size} tasks)",
                            color = WarningAmber,
                            fontWeight = FontWeight.Bold,
                            fontSize = 12.sp,
                            modifier = Modifier.weight(1f)
                        )
                        Text(
                            text = "Tap to review",
                            color = CyanAccent,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
            syncState == com.example.data.model.SyncState.PULLING -> {
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = DarkSurfaceVariant,
                    modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp, color = ElectricBlue)
                        Text("Syncing with Cloudflare Worker...", color = TextSecondary, fontSize = 11.sp)
                    }
                }
            }
            authState is com.example.data.model.AuthState.SignedOut -> {
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = DarkSurfaceVariant,
                    border = androidx.compose.foundation.BorderStroke(1.dp, DarkBorder),
                    modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("☁️ Signed out (offline demo-free mode)", color = TextMuted, fontSize = 11.sp, modifier = Modifier.weight(1f))
                        TextButton(
                            onClick = { viewModel.setAuthDialogVisible(true) },
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                        ) {
                            Text("Sign In", color = CyanAccent, fontWeight = FontWeight.Bold, fontSize = 11.sp)
                        }
                    }
                }
            }
            authState is com.example.data.model.AuthState.SessionExpired -> {
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = PriorityP1.copy(alpha = 0.15f),
                    border = androidx.compose.foundation.BorderStroke(1.dp, PriorityP1.copy(alpha = 0.5f)),
                    modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("⚠️ Clerk session expired (401)", color = PriorityP1, fontSize = 11.sp, modifier = Modifier.weight(1f))
                        TextButton(
                            onClick = { viewModel.setAuthDialogVisible(true) },
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                        ) {
                            Text("Re-auth", color = PriorityP1, fontWeight = FontWeight.Bold, fontSize = 11.sp)
                        }
                    }
                }
            }
        }

        // Recursive Multi-Level Tasks Tree List
        if (todayRootTasks.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentAlignment = Alignment.Center
            ) {
                if (syncState == com.example.data.model.SyncState.PULLING) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator(color = ElectricBlue, modifier = Modifier.size(36.dp))
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            text = "Downloading cloud snapshot...",
                            style = MaterialTheme.typography.bodyMedium,
                            color = TextSecondary
                        )
                    }
                } else {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("🎯", fontSize = 48.sp)
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            text = "All clear for today!",
                            style = MaterialTheme.typography.titleLarge,
                            color = TextPrimary,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "Tap '+ New task' below to start your focus session.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = TextMuted
                        )
                    }
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp),
                contentPadding = PaddingValues(bottom = 88.dp)
            ) {
                items(todayRootTasks, key = { it.id }) { rootTask ->
                    val effectiveDate = DateUtils.getEffectiveDueDate(rootTask, tasksById)
                    val isOverdue = effectiveDate.isNotBlank() && effectiveDate < todayDate

                    TaskItemCard(
                        task = rootTask,
                        allTasks = tasks,
                        indentLevel = 0,
                        isSelectMode = isSelectMode,
                        isSelected = rootTask.id in selectedTaskIds,
                        isOverdue = isOverdue,
                        emergencyBypassActive = settings.emergencyBypassActive,
                        onToggleCompletion = { id ->
                            val (ok, error) = viewModel.repository.toggleTaskCompletion(id)
                            if (!ok && error != null) {
                                viewModel.setStatusMessage(error)
                            } else {
                                viewModel.clearStatusMessage()
                            }
                        },
                        onToggleSelection = { viewModel.toggleTaskSelection(it) },
                        onEditTask = { viewModel.openEditTaskDialog(it) },
                        onAddSubtask = { viewModel.openCreateTaskDialog(parentId = it) },
                        onTrashTask = { viewModel.repository.trashBranch(it) },
                        onOpenAudit = { viewModel.openEvidenceAuditDialog(it) }
                    )
                }
            }
        }

        // Floating Pomodoro Focus Bar (Docked above bottom edge)
        val focusedTask = remember(tasks, pomodoroState.currentTaskId) {
            tasks.find { it.id == pomodoroState.currentTaskId }
        }

        Spacer(modifier = Modifier.height(4.dp))
        var fullscreen by remember { mutableStateOf(false) }
        FloatingPomodoroBar(
            pomodoroState = pomodoroState,
            focusedTask = focusedTask,
            onPlayPause = {
                if (pomodoroState.isRunning) {
                    viewModel.repository.pausePomodoro()
                } else {
                    viewModel.repository.startPomodoro()
                }
            },
            onReset = { viewModel.repository.resetPomodoro() },
            onFullscreen = { fullscreen = true },
            onSkipMode = {
                val nextMode = when (pomodoroState.mode) {
                    PomodoroMode.WORK -> PomodoroMode.SHORT_BREAK
                    PomodoroMode.SHORT_BREAK -> PomodoroMode.WORK
                    PomodoroMode.LONG_BREAK -> PomodoroMode.WORK
                }
                viewModel.repository.switchPomodoroMode(nextMode)
            },
            modifier = Modifier.padding(bottom = 8.dp)
        )
        if (fullscreen) PomodoroFullscreenDialog(
            state = pomodoroState,
            onClose = { fullscreen = false },
            onPlayPause = {
                if (pomodoroState.isRunning) viewModel.repository.pausePomodoro()
                else viewModel.repository.startPomodoro()
            },
            onReset = { viewModel.repository.resetPomodoro() }
        )
    }

    if (showRescheduleModal) {
        BatchRescheduleDialog(
            title = "Reschedule Overdue Tasks",
            onDismiss = { showRescheduleModal = false },
            onConfirm = { newDate ->
                viewModel.repository.rescheduleAllOverdue(newDate)
                showRescheduleModal = false
            }
        )
    }
}
