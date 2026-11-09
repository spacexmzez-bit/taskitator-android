package com.example.ui.components

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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
import com.example.data.model.AuthState
import com.example.data.model.UserSettings
import com.example.ui.theme.*

@Composable
fun AuthDialog(
    authState: AuthState,
    settings: UserSettings,
    onSignInToken: (String) -> Unit,
    onConnectAccount: (String) -> Unit,
    onSignOut: () -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    var tokenInput by remember { mutableStateOf("") }
    var showAdvancedToken by remember { mutableStateOf(false) }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = DarkSurface,
            border = androidx.compose.foundation.BorderStroke(1.dp, DarkBorder),
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 16.dp)
        ) {
            Column(
                modifier = Modifier
                    .padding(20.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = "Account Authentication",
                        style = MaterialTheme.typography.titleLarge,
                        color = TextPrimary,
                        fontWeight = FontWeight.Bold
                    )
                    IconButton(onClick = onDismiss) {
                        Text("✕", color = TextMuted, fontSize = 16.sp)
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                when (authState) {
                    is AuthState.Authenticated -> {
                        Surface(
                            shape = RoundedCornerShape(10.dp),
                            color = SuccessGreen.copy(alpha = 0.12f),
                            border = androidx.compose.foundation.BorderStroke(1.dp, SuccessGreen.copy(alpha = 0.5f)),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(modifier = Modifier.padding(14.dp)) {
                                Text("✓ Connected to Cloud Account", color = SuccessGreen, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                                Spacer(modifier = Modifier.height(4.dp))
                                Text("User: ${authState.username}", color = TextPrimary, fontWeight = FontWeight.SemiBold)
                                Text("Worker User ID: ${authState.workerUser}", color = TextSecondary, fontSize = 11.sp)
                                if (authState.isOffline) {
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Text("Status: Working in offline mode", color = WarningAmber, fontSize = 11.sp)
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(16.dp))

                        Button(
                            onClick = {
                                onSignOut()
                                onDismiss()
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = PriorityP1),
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("btn_sign_out")
                        ) {
                            Text("Sign Out", fontWeight = FontWeight.Bold)
                        }
                    }

                    is AuthState.Unlinked -> {
                        Surface(
                            shape = RoundedCornerShape(10.dp),
                            color = WarningAmber.copy(alpha = 0.15f),
                            border = androidx.compose.foundation.BorderStroke(1.dp, WarningAmber),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(modifier = Modifier.padding(14.dp)) {
                                Text("⚠️ Account Unlinked", color = WarningAmber, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    "Your Clerk token is valid, but no cloud account is linked in the Worker. Connect your account to begin syncing.",
                                    color = TextPrimary,
                                    fontSize = 12.sp
                                )
                                Spacer(modifier = Modifier.height(10.dp))
                                Button(
                                    onClick = { onConnectAccount(authState.token) },
                                    colors = ButtonDefaults.buttonColors(containerColor = WarningAmber),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text("Connect Worker Account", color = DarkBackground, fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                    }

                    is AuthState.SessionExpired -> {
                        Surface(
                            shape = RoundedCornerShape(10.dp),
                            color = PriorityP1.copy(alpha = 0.15f),
                            border = androidx.compose.foundation.BorderStroke(1.dp, PriorityP1),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(modifier = Modifier.padding(14.dp)) {
                                Text("⚠️ Session Expired (401)", color = PriorityP1, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(authState.message, color = TextPrimary, fontSize = 12.sp)
                            }
                        }
                        Spacer(modifier = Modifier.height(12.dp))
                    }

                    is AuthState.AccountDeleted -> {
                        Surface(
                            shape = RoundedCornerShape(10.dp),
                            color = PriorityP1.copy(alpha = 0.15f),
                            border = androidx.compose.foundation.BorderStroke(1.dp, PriorityP1),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(modifier = Modifier.padding(14.dp)) {
                                Text("🚫 Account Deleted (410)", color = PriorityP1, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                                Spacer(modifier = Modifier.height(4.dp))
                                Text("This cloud account has been permanently deleted.", color = TextPrimary, fontSize = 12.sp)
                            }
                        }
                        Spacer(modifier = Modifier.height(12.dp))
                    }

                    else -> {
                        Text(
                            text = "Sign in to synchronize your tasks, breaks, and settings with the website.",
                            fontSize = 12.sp,
                            color = TextSecondary
                        )
                        Spacer(modifier = Modifier.height(14.dp))
                    }
                }

                if (authState !is AuthState.Authenticated) {
                    // Sign In Options
                    Button(
                        onClick = {
                            val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://taskitator.mazendev.com"))
                            context.startActivity(intent)
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = CyanAccent),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("btn_open_web_sign_in")
                    ) {
                        Text("Sign In on Taskitator Web", color = DarkBackground, fontWeight = FontWeight.Bold)
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    OutlinedButton(
                        onClick = { showAdvancedToken = !showAdvancedToken },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(if (showAdvancedToken) "Hide Session Token Input" else "Enter Clerk Session Token Directly")
                    }

                    if (showAdvancedToken) {
                        Spacer(modifier = Modifier.height(12.dp))
                        OutlinedTextField(
                            value = tokenInput,
                            onValueChange = { tokenInput = it },
                            label = { Text("Clerk Session JWT") },
                            placeholder = { Text("Paste Bearer token...") },
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("input_session_token"),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = ElectricBlue,
                                unfocusedBorderColor = DarkBorder,
                                focusedTextColor = TextPrimary,
                                unfocusedTextColor = TextPrimary
                            )
                        )

                        Spacer(modifier = Modifier.height(10.dp))

                        Button(
                            onClick = {
                                if (tokenInput.isNotBlank()) {
                                    onSignInToken(tokenInput.trim())
                                }
                            },
                            enabled = tokenInput.isNotBlank(),
                            colors = ButtonDefaults.buttonColors(containerColor = ElectricBlue),
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("btn_submit_token")
                        ) {
                            Text("Verify & Link Token", fontWeight = FontWeight.Bold)
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                TextButton(
                    onClick = onDismiss,
                    modifier = Modifier.align(Alignment.End)
                ) {
                    Text("Close", color = TextSecondary)
                }
            }
        }
    }
}
