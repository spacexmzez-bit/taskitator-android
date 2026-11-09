package com.example.ui.components

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.example.data.model.ConflictTaskItem
import com.example.data.model.SyncConflictReport
import com.example.ui.theme.*

@Composable
fun ConflictResolutionDialog(
    report: SyncConflictReport,
    onAdoptCloud: () -> Unit,
    onKeepLocal: () -> Unit,
    onResolvePerTask: (Set<String>) -> Unit,
    onExportBackup: () -> String,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    // Set of task IDs that the user chooses to keep local (default to empty = keep cloud)
    val keepLocalSelections = remember { mutableStateMapOf<String, Boolean>() }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = DarkSurface,
            border = androidx.compose.foundation.BorderStroke(1.dp, WarningAmber),
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.85f)
                .padding(vertical = 12.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp)
            ) {
                // Header
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("⚠️", fontSize = 20.sp)
                        Column {
                            Text(
                                text = "Sync Conflict (409)",
                                style = MaterialTheme.typography.titleMedium,
                                color = WarningAmber,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = "${report.conflictingTasks.size} task(s) changed on both device and cloud",
                                fontSize = 11.sp,
                                color = TextMuted
                            )
                        }
                    }
                    IconButton(onClick = onDismiss) {
                        Text("✕", color = TextMuted)
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = DarkSurfaceVariant,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(10.dp)) {
                        Text("Automatic 3-way merge isolated non-conflicting edits.", fontSize = 11.sp, color = TextSecondary)
                        Text("Safety backup saved: ${report.backupFilePath}", fontSize = 10.sp, color = CyanAccent)
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                // List of conflicting tasks
                LazyColumn(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    items(report.conflictingTasks) { item ->
                        val isLocal = keepLocalSelections[item.taskId] ?: false
                        Card(
                            shape = RoundedCornerShape(10.dp),
                            colors = CardDefaults.cardColors(containerColor = DarkBackground),
                            border = androidx.compose.foundation.BorderStroke(1.dp, DarkBorder),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(modifier = Modifier.padding(12.dp)) {
                                Text(
                                    text = "Task ID: ${item.taskId}",
                                    fontSize = 10.sp,
                                    color = TextMuted
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = "Conflicting fields: ${item.conflictingFields.joinToString(", ")}",
                                    fontSize = 11.sp,
                                    color = WarningAmber,
                                    fontWeight = FontWeight.SemiBold
                                )

                                Spacer(modifier = Modifier.height(8.dp))

                                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    // Device Column
                                    Surface(
                                        shape = RoundedCornerShape(6.dp),
                                        color = if (isLocal) ElectricBlue.copy(alpha = 0.2f) else DarkSurface,
                                        border = androidx.compose.foundation.BorderStroke(1.dp, if (isLocal) ElectricBlue else DarkBorder),
                                        modifier = Modifier.weight(1f)
                                    ) {
                                        Column(modifier = Modifier.padding(8.dp)) {
                                            Text("📱 This Device", fontSize = 10.sp, color = ElectricBlue, fontWeight = FontWeight.Bold)
                                            Text(item.localTitle, fontSize = 12.sp, color = TextPrimary, fontWeight = FontWeight.Medium)
                                            Text("Status: ${item.localStatus}", fontSize = 10.sp, color = TextMuted)
                                        }
                                    }

                                    // Cloud Column
                                    Surface(
                                        shape = RoundedCornerShape(6.dp),
                                        color = if (!isLocal) CyanAccent.copy(alpha = 0.2f) else DarkSurface,
                                        border = androidx.compose.foundation.BorderStroke(1.dp, if (!isLocal) CyanAccent else DarkBorder),
                                        modifier = Modifier.weight(1f)
                                    ) {
                                        Column(modifier = Modifier.padding(8.dp)) {
                                            Text("☁️ Cloud Server", fontSize = 10.sp, color = CyanAccent, fontWeight = FontWeight.Bold)
                                            Text(item.remoteTitle, fontSize = 12.sp, color = TextPrimary, fontWeight = FontWeight.Medium)
                                            Text("Status: ${item.remoteStatus}", fontSize = 10.sp, color = TextMuted)
                                        }
                                    }
                                }

                                Spacer(modifier = Modifier.height(8.dp))

                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text("Resolution choice:", fontSize = 11.sp, color = TextSecondary)
                                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                        FilterChip(
                                            selected = !isLocal,
                                            onClick = { keepLocalSelections[item.taskId] = false },
                                            label = { Text("Use Cloud", fontSize = 10.sp) }
                                        )
                                        FilterChip(
                                            selected = isLocal,
                                            onClick = { keepLocalSelections[item.taskId] = true },
                                            label = { Text("Use Device", fontSize = 10.sp) }
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                // Action Buttons
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(
                        onClick = onAdoptCloud,
                        modifier = Modifier
                            .weight(1f)
                            .testTag("btn_adopt_cloud")
                    ) {
                        Text("All Cloud", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    }

                    OutlinedButton(
                        onClick = onKeepLocal,
                        modifier = Modifier
                            .weight(1f)
                            .testTag("btn_keep_local")
                    ) {
                        Text("All Device", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    }

                    Button(
                        onClick = {
                            val localChosenIds = keepLocalSelections.filter { it.value }.keys.toSet()
                            onResolvePerTask(localChosenIds)
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = ElectricBlue),
                        modifier = Modifier
                            .weight(1.2f)
                            .testTag("btn_apply_conflict_resolution")
                    ) {
                        Text("Apply Choice", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                TextButton(
                    onClick = {
                        val backup = onExportBackup()
                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        clipboard.setPrimaryClip(ClipData.newPlainText("conflict_backup", backup))
                        Toast.makeText(context, "Conflict backup copied to clipboard", Toast.LENGTH_SHORT).show()
                    },
                    modifier = Modifier.align(Alignment.CenterHorizontally)
                ) {
                    Text("Copy Conflict Backup JSON", fontSize = 11.sp, color = CyanAccent)
                }
            }
        }
    }
}
