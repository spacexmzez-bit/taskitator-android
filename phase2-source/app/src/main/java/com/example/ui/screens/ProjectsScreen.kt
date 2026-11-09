package com.example.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.data.model.TaskStatus
import com.example.ui.viewmodel.TaskitatorViewModel

@Composable
fun ProjectsScreen(viewModel: TaskitatorViewModel, modifier: Modifier = Modifier) {
    val projects by viewModel.projects.collectAsState()
    val tasks by viewModel.tasks.collectAsState()
    var name by remember { mutableStateOf("") }
    var editingId by remember { mutableStateOf<String?>(null) }
    var deleteId by remember { mutableStateOf<String?>(null) }
    var expanded by remember { mutableStateOf(setOf("inbox")) }

    Column(modifier.fillMaxSize().padding(16.dp)) {
        Text("Projects", style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(name, { name = it }, modifier = Modifier.weight(1f), singleLine = true,
                label = { Text(if (editingId == null) "New project" else "Rename project") })
            Button(onClick = {
                val (ok, result) = viewModel.repository.saveProject(name, editingId)
                viewModel.setStatusMessage(if (ok) "Project saved" else result)
                if (ok) { name = ""; editingId = null }
            }, enabled = name.isNotBlank()) { Text("Save") }
        }
        Spacer(Modifier.height(12.dp))
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(projects, key = { it.id }) { project ->
                val members = tasks.filter { (it.projectId ?: "inbox") == project.id && it.status != TaskStatus.TRASH }
                val completed = members.count { it.status == TaskStatus.COMPLETED }
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp)) {
                        Text("${project.icon} ${project.name}", style = MaterialTheme.typography.titleMedium)
                        Text("$completed / ${members.size} completed")
                        if (members.isNotEmpty()) LinearProgressIndicator(
                            progress = { completed.toFloat() / members.size.toFloat() }, modifier = Modifier.fillMaxWidth())
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            TextButton(onClick = { expanded = if (project.id in expanded) expanded - project.id else expanded + project.id }) {
                                Text(if (project.id in expanded) "Hide tasks" else "Show tasks")
                            }
                            if (!project.isDefault) {
                                TextButton(onClick = { editingId = project.id; name = project.name }) { Text("Rename") }
                                TextButton(onClick = { deleteId = project.id }) { Text("Delete") }
                            }
                        }
                        if (project.id in expanded) members.forEach { task ->
                            TextButton(onClick = { viewModel.openEditTaskDialog(task) }) {
                                Text("${if (task.status == TaskStatus.COMPLETED) "✓" else "○"} ${task.title}")
                            }
                        }
                    }
                }
            }
        }
    }
    if (deleteId != null) AlertDialog(
        onDismissRequest = { deleteId = null },
        title = { Text("Delete project?") },
        text = { Text("All tasks in this project will be moved to Inbox. No tasks will be deleted.") },
        confirmButton = { TextButton(onClick = {
            deleteId?.let { viewModel.repository.deleteProjectToInbox(it) }
            deleteId = null
        }) { Text("Move tasks and delete") } },
        dismissButton = { TextButton(onClick = { deleteId = null }) { Text("Cancel") } }
    )
}
