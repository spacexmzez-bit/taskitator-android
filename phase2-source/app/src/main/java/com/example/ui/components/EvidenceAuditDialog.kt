package com.example.ui.components

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
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
import com.example.data.model.Task
import com.example.data.network.ProofAttachmentInput
import com.example.data.network.ProofAuditTask
import com.example.data.network.TaskitatorWorkerApi
import com.example.ui.theme.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream

@Composable
fun EvidenceAuditDialog(
    tasks: List<Task>,
    isPro: Boolean = false,
    useByok: Boolean = false,
    authToken: String = "",
    onDismiss: () -> Unit,
    onAuditApproved: (List<String>, String) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val selectedTasks = remember(tasks) { tasks.take(20) }
    val batch = selectedTasks.size > 1
    val hosted = isPro && !useByok

    var selectedFiles by remember { mutableStateOf<List<ProofAttachmentInput>>(emptyList()) }
    var isBusy by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var quote by remember { mutableStateOf<JSONObject?>(null) }
    var review by remember { mutableStateOf<JSONObject?>(null) }
    var operationId by remember { mutableStateOf(TaskitatorWorkerApi.newOperationId()) }
    var jobId by remember { mutableStateOf("") }
    var eligibleTaskIds by remember { mutableStateOf<List<String>>(emptyList()) }
    var reviewFeedback by remember { mutableStateOf("") }

    val filePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        if (uris.isNotEmpty()) {
            scope.launch {
                isBusy = true
                errorMessage = null
                try {
                    if (uris.size > 5) throw IllegalArgumentException("Select no more than five evidence files.")
                    val loaded = mutableListOf<ProofAttachmentInput>()
                    var total = 0L
                    for (uri in uris) {
                        val file = readProofAttachment(context, uri)
                        total += file.bytes.size
                        if (total > 40L * 1024 * 1024) {
                            throw IllegalArgumentException("Combined evidence must not exceed 40 MiB.")
                        }
                        loaded += file
                    }
                    selectedFiles = loaded
                    quote = null
                    review = null
                    jobId = ""
                    eligibleTaskIds = emptyList()
                    operationId = TaskitatorWorkerApi.newOperationId()
                } catch (e: Exception) {
                    errorMessage = e.message ?: "Unable to read the selected evidence."
                } finally {
                    isBusy = false
                }
            }
        }
    }

    val auditTasks = remember(selectedTasks) {
        selectedTasks.map { task ->
            ProofAuditTask(
                id = task.id,
                title = task.resolvedTitle.ifBlank { task.title },
                criteria = task.proofCriteria
            )
        }
    }

    fun showReviewResult(result: JSONObject) {
        review = result
        jobId = result.optString("job_id")
        val resultTasks = result.optJSONArray("tasks")
        if (batch && resultTasks != null) {
            val eligible = mutableListOf<String>()
            val reasons = mutableListOf<String>()
            for (i in 0 until resultTasks.length()) {
                val item = resultTasks.optJSONObject(i) ?: continue
                val id = item.optString("task_id")
                val status = item.optString("status")
                if (status == "eligible" && id.isNotBlank()) eligible += id
                val title = auditTasks.firstOrNull { it.id == id }?.title ?: id
                reasons += title + " — " + status.uppercase() + ": " + item.optString("reason", "")
            }
            eligibleTaskIds = eligible
            reviewFeedback = reasons.joinToString("\n")
        } else {
            val verdict = result.optString("verdict")
            eligibleTaskIds = if (verdict == "approved" && selectedTasks.size == 1) listOf(selectedTasks.first().id) else emptyList()
            reviewFeedback = result.optString("critique", result.optString("feedback", "The provider returned no explanation."))
        }
        if (jobId.isBlank() && eligibleTaskIds.isNotEmpty()) {
            eligibleTaskIds = emptyList()
            errorMessage = "The provider did not return a server confirmation receipt. The task remains locked."
        }
    }

    Dialog(onDismissRequest = { if (!isBusy) onDismiss() }) {
        Surface(
            shape = RoundedCornerShape(18.dp),
            color = DarkSurface,
            border = androidx.compose.foundation.BorderStroke(1.dp, ElectricBlue.copy(alpha = 0.5f)),
            modifier = Modifier.fillMaxWidth().fillMaxHeight(0.9f).padding(vertical = 12.dp)
        ) {
            Column(
                modifier = Modifier.fillMaxSize().padding(18.dp).verticalScroll(rememberScrollState())
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            if (batch) "Batch AI Evidence Audit" else "AI Evidence Audit",
                            style = MaterialTheme.typography.titleLarge,
                            color = TextPrimary,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            if (hosted) "Hosted Pro review · quote required before charge" else "Personal provider review",
                            color = TextSecondary,
                            fontSize = 11.sp
                        )
                    }
                    IconButton(onClick = onDismiss, enabled = !isBusy) {
                        Icon(Icons.Default.Close, contentDescription = "Close", tint = TextSecondary)
                    }
                }

                Spacer(Modifier.height(12.dp))
                Card(
                    colors = CardDefaults.cardColors(containerColor = DarkSurfaceVariant),
                    border = androidx.compose.foundation.BorderStroke(0.5.dp, DarkBorder),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(Modifier.padding(12.dp)) {
                        Text(
                            if (batch) selectedTasks.size.toString() + " protected tasks selected" else selectedTasks.firstOrNull()?.resolvedTitle ?: selectedTasks.firstOrNull()?.title.orEmpty(),
                            color = TextPrimary,
                            fontWeight = FontWeight.Bold
                        )
                        if (batch) {
                            selectedTasks.forEach { task ->
                                Text("• " + task.resolvedTitle.ifBlank { task.title }, color = TextSecondary, fontSize = 12.sp)
                            }
                        } else {
                            Spacer(Modifier.height(6.dp))
                            Text("Required criteria", color = CyanAccent, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                            Text(selectedTasks.firstOrNull()?.proofCriteria.orEmpty(), color = TextSecondary, fontSize = 13.sp)
                        }
                    }
                }

                Spacer(Modifier.height(14.dp))
                Text("Evidence files", color = TextPrimary, fontWeight = FontWeight.SemiBold)
                Text("Up to 5 files · photos 8 MiB each · PDFs 30 MiB each · 40 MiB combined", color = TextSecondary, fontSize = 11.sp)
                Spacer(Modifier.height(8.dp))
                OutlinedButton(
                    onClick = { filePicker.launch(arrayOf("image/jpeg", "image/png", "image/webp", "application/pdf")) },
                    enabled = !isBusy && quote == null && review == null,
                    modifier = Modifier.fillMaxWidth().testTag("select_evidence_files_button")
                ) {
                    Text("Choose evidence files")
                }
                selectedFiles.forEachIndexed { index, file ->
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(file.name, color = TextPrimary, fontSize = 12.sp, maxLines = 2)
                            Text(file.mimeType + " · " + "%.2f".format(file.bytes.size / 1048576.0) + " MiB", color = TextMuted, fontSize = 10.sp)
                        }
                        IconButton(
                            onClick = {
                                selectedFiles = selectedFiles.filterIndexed { i, _ -> i != index }
                                quote = null
                                review = null
                                jobId = ""
                                eligibleTaskIds = emptyList()
                                operationId = TaskitatorWorkerApi.newOperationId()
                            },
                            enabled = !isBusy && quote == null && review == null
                        ) {
                            Icon(Icons.Default.Delete, contentDescription = "Remove file", tint = PriorityP1)
                        }
                    }
                }

                if (selectedFiles.isNotEmpty()) {
                    Text(
                        "Total: " + "%.2f".format(selectedFiles.sumOf { it.bytes.size.toLong() } / 1048576.0) + " MiB",
                        color = TextMuted,
                        fontSize = 11.sp
                    )
                }

                Spacer(Modifier.height(14.dp))

                if (quote == null && review == null) {
                    Button(
                        onClick = {
                            if (selectedFiles.isEmpty()) {
                                errorMessage = "Choose at least one evidence file."
                            } else {
                                isBusy = true
                                errorMessage = null
                                scope.launch {
                                    try {
                                        val result = if (hosted) {
                                            TaskitatorWorkerApi.quoteProofReview(auditTasks, selectedFiles, operationId, batch, authToken)
                                        } else {
                                            TaskitatorWorkerApi.submitPersonalProofReview(auditTasks, selectedFiles, operationId, batch, authToken)
                                        }
                                        result.fold(
                                            onSuccess = { response ->
                                                if (hosted && !response.optBoolean("completed", false)) {
                                                    if (response.optString("quote_id").isBlank() || response.optDouble("maximum_credits", 0.0) <= 0.0) {
                                                        errorMessage = "The Worker returned an invalid price quote. No hosted review was started."
                                                    } else {
                                                        quote = response
                                                    }
                                                } else {
                                                    val completed = response.optJSONObject("result") ?: response
                                                    showReviewResult(completed)
                                                }
                                            },
                                            onFailure = { errorMessage = it.message ?: "Unable to start the AI review." }
                                        )
                                    } finally {
                                        isBusy = false
                                    }
                                }
                            }
                        },
                        enabled = !isBusy && selectedFiles.isNotEmpty() && selectedTasks.isNotEmpty() && selectedTasks.size <= 20,
                        colors = ButtonDefaults.buttonColors(containerColor = ElectricBlue),
                        modifier = Modifier.fillMaxWidth().testTag("submit_evidence_audit_button")
                    ) {
                        Text(if (isBusy) "Contacting AI service..." else if (hosted) "Get hosted audit quote" else "Submit to personal AI review", fontWeight = FontWeight.Bold)
                    }
                }

                quote?.let { quoted ->
                    Spacer(Modifier.height(12.dp))
                    Card(
                        colors = CardDefaults.cardColors(containerColor = DarkSurfaceVariant),
                        border = androidx.compose.foundation.BorderStroke(1.dp, WarningAmber),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(Modifier.padding(12.dp)) {
                            Text("Hosted review price", color = WarningAmber, fontWeight = FontWeight.Bold)
                            Text("Estimated: " + quoted.optString("estimated_credits", "not provided") + " credits", color = TextPrimary)
                            Text("Maximum charge: " + quoted.optString("maximum_credits", "not provided") + " credits", color = TextPrimary, fontWeight = FontWeight.Bold)
                            Text("The review will not start until you explicitly accept this maximum.", color = TextSecondary, fontSize = 11.sp)
                            Spacer(Modifier.height(8.dp))
                            Button(
                                onClick = {
                                    isBusy = true
                                    errorMessage = null
                                    scope.launch {
                                        try {
                                            val result = TaskitatorWorkerApi.submitHostedProofReview(
                                                auditTasks, selectedFiles, operationId, quoted.optString("quote_id"), batch, authToken
                                            )
                                            result.fold(
                                                onSuccess = { showReviewResult(it); quote = null },
                                                onFailure = { errorMessage = it.message ?: "Hosted review failed. The task remains protected." }
                                            )
                                        } finally {
                                            isBusy = false
                                        }
                                    }
                                },
                                enabled = !isBusy,
                                colors = ButtonDefaults.buttonColors(containerColor = WarningAmber),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(if (isBusy) "Starting review..." else "Accept maximum and run review", color = DarkBackground, fontWeight = FontWeight.Bold)
                            }
                            TextButton(onClick = { quote = null; operationId = TaskitatorWorkerApi.newOperationId() }, enabled = !isBusy) {
                                Text("Cancel quote", color = TextSecondary)
                            }
                        }
                    }
                }

                review?.let { result ->
                    Spacer(Modifier.height(14.dp))
                    val singleVerdict = result.optString("verdict")
                    val passed = if (batch) eligibleTaskIds.isNotEmpty() else singleVerdict == "approved"
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = if (passed) SuccessGreen.copy(alpha = 0.12f) else PriorityP1.copy(alpha = 0.12f)
                        ),
                        border = androidx.compose.foundation.BorderStroke(1.dp, if (passed) SuccessGreen else PriorityP1),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(Modifier.padding(12.dp)) {
                            Text(
                                if (batch) "BATCH REVIEW RESULTS" else "AUDIT " + singleVerdict.uppercase().ifBlank { "UNAVAILABLE" },
                                color = if (passed) SuccessGreen else PriorityP1,
                                fontWeight = FontWeight.Bold
                            )
                            Spacer(Modifier.height(6.dp))
                            Text(reviewFeedback, color = TextPrimary, fontSize = 12.sp)
                            if (eligibleTaskIds.isNotEmpty() && jobId.isNotBlank()) {
                                Spacer(Modifier.height(12.dp))
                                Button(
                                    onClick = {
                                        isBusy = true
                                        errorMessage = null
                                        scope.launch {
                                            try {
                                                val confirmed = TaskitatorWorkerApi.confirmProofReview(jobId, eligibleTaskIds, batch, authToken)
                                                confirmed.fold(
                                                    onSuccess = { response ->
                                                        val ids = response.optJSONArray("confirmed_task_ids")
                                                        val confirmedIds = if (ids != null) {
                                                            (0 until ids.length()).mapNotNull { ids.optString(it).takeIf { value -> value.isNotBlank() } }
                                                        } else eligibleTaskIds
                                                        onAuditApproved(confirmedIds, reviewFeedback)
                                                        onDismiss()
                                                    },
                                                    onFailure = { errorMessage = it.message ?: "Server confirmation failed. The task remains locked." }
                                                )
                                            } finally {
                                                isBusy = false
                                            }
                                        }
                                    },
                                    enabled = !isBusy,
                                    colors = ButtonDefaults.buttonColors(containerColor = SuccessGreen),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text(if (isBusy) "Confirming with Worker..." else "Confirm approved tasks on server", fontWeight = FontWeight.Bold)
                                }
                            } else {
                                Text(
                                    "No task was approved. Nothing was completed; protected tasks remain locked.",
                                    color = PriorityP1,
                                    fontSize = 11.sp
                                )
                            }
                        }
                    }
                }

                if (errorMessage != null) {
                    Spacer(Modifier.height(10.dp))
                    Surface(
                        color = PriorityP1.copy(alpha = 0.12f),
                        shape = RoundedCornerShape(8.dp),
                        border = androidx.compose.foundation.BorderStroke(1.dp, PriorityP1.copy(alpha = 0.4f)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(errorMessage!!, color = PriorityP1, fontSize = 12.sp, modifier = Modifier.padding(10.dp))
                    }
                }
            }
        }
    }
}

private suspend fun readProofAttachment(context: Context, uri: Uri): ProofAttachmentInput = withContext(Dispatchers.IO) {
    val resolver = context.contentResolver
    val name = resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
        if (cursor.moveToFirst()) cursor.getString(0) else null
    } ?: "evidence"
    var mime = resolver.getType(uri)?.lowercase().orEmpty()
    if (mime == "image/jpg") mime = "image/jpeg"
    if (mime.isBlank() || mime == "application/octet-stream") {
        mime = when (name.substringAfterLast('.', "").lowercase()) {
            "jpg", "jpeg" -> "image/jpeg"
            "png" -> "image/png"
            "webp" -> "image/webp"
            "pdf" -> "application/pdf"
            else -> mime
        }
    }
    if (mime !in setOf("application/pdf", "image/jpeg", "image/png", "image/webp")) {
        throw IllegalArgumentException("Unsupported evidence type for " + name + ". Use PDF, JPEG, PNG or WebP.")
    }
    val maxBytes = if (mime == "application/pdf") 30L * 1024 * 1024 else 8L * 1024 * 1024
    val output = ByteArrayOutputStream()
    resolver.openInputStream(uri)?.use { input ->
        val buffer = ByteArray(8192)
        var total = 0L
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            total += count
            if (total > maxBytes) throw IllegalArgumentException(name + " exceeds the per-file size limit.")
            output.write(buffer, 0, count)
        }
    } ?: throw IllegalArgumentException("Unable to open " + name + ".")
    ProofAttachmentInput(name, mime, output.toByteArray())
}
