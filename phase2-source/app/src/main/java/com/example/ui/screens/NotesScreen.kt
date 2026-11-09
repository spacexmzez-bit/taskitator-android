package com.example.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.data.model.Task
import com.example.ui.viewmodel.TaskitatorViewModel

@Composable
fun NotesScreen(viewModel: TaskitatorViewModel) {
    val notes by viewModel.notes.collectAsState()
    var title by remember { mutableStateOf("") }
    var body by remember { mutableStateOf("") }
    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Text("Scratchpad", style = MaterialTheme.typography.headlineMedium)
        Text("Private on-device notes expire after 48 hours. Convert important notes to synced tasks.",
            style = MaterialTheme.typography.bodySmall)
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(title, { title = it }, label = { Text("Title (optional)") }, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(body, { body = it }, label = { Text("Quick note") }, modifier = Modifier.fillMaxWidth(), minLines = 2)
        Button(onClick = { viewModel.repository.saveDistractionNote(body, title.ifBlank { null }); body = ""; title = "" },
            enabled = body.isNotBlank()) { Text("Save note") }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(notes, key = { it.id }) { note ->
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp)) {
                        Text(note.title ?: "Note", style = MaterialTheme.typography.titleMedium)
                        Text(note.text)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TextButton(onClick = {
                                val titleText = note.title?.takeIf { it.isNotBlank() } ?: note.text.take(80)
                                val result = viewModel.repository.upsertTask(Task(title = titleText, description = note.text))
                                if (result.first) viewModel.repository.deleteDistractionNote(note.id)
                                else viewModel.setStatusMessage(result.second ?: "Unable to create task")
                            }) { Text("Convert to task") }
                            TextButton(onClick = { viewModel.repository.deleteDistractionNote(note.id) }) { Text("Delete") }
                        }
                    }
                }
            }
        }
    }
}
