package com.example.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.ui.viewmodel.TaskitatorViewModel
import java.text.DateFormat
import java.util.Date

@Composable
fun DraftsScreen(viewModel: TaskitatorViewModel) {
    val drafts by viewModel.taskDrafts.collectAsState()
    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Text("Task drafts", style = MaterialTheme.typography.headlineMedium)
        Text("Saved in your synced settings. Drafts do not create or lock tasks.", style = MaterialTheme.typography.bodySmall)
        Spacer(Modifier.height(12.dp))
        if (drafts.isEmpty()) Text("No drafts saved.")
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(drafts, key = { (it.editTaskId ?: "new:") + it.id }) { draft ->
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp)) {
                        Text(draft.task.title, style = MaterialTheme.typography.titleMedium)
                        Text(if (draft.editTaskId != null) "Unsaved edit" else "New task", style = MaterialTheme.typography.labelMedium)
                        Text(DateFormat.getDateTimeInstance().format(Date(draft.savedAt)), style = MaterialTheme.typography.bodySmall)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TextButton(onClick = { viewModel.openEditTaskDialog(draft.task) }) { Text("Continue editing") }
                            TextButton(onClick = { viewModel.repository.removeTaskDraft(draft) }) { Text("Discard") }
                        }
                    }
                }
            }
        }
    }
}
