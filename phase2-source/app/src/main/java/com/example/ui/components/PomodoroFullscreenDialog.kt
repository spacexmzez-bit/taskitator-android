package com.example.ui.components

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ActivityInfo
import android.view.WindowManager
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.data.model.PomodoroState

private tailrec fun Context.activityOrNull(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.activityOrNull()
    else -> null
}

@Composable
fun PomodoroFullscreenDialog(
    state: PomodoroState,
    onClose: () -> Unit,
    onPlayPause: () -> Unit,
    onReset: () -> Unit
) {
    val activity = LocalContext.current.activityOrNull()
    DisposableEffect(activity) {
        val previous = activity?.requestedOrientation ?: ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        activity?.window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose {
            activity?.requestedOrientation = previous
            activity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.fillMaxSize().padding(24.dp),
                verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                Text(state.mode.displayName, style = MaterialTheme.typography.titleLarge)
                Spacer(Modifier.height(12.dp))
                Text("%02d:%02d".format(state.remainingSeconds / 60, state.remainingSeconds % 60),
                    fontSize = 64.sp, fontWeight = FontWeight.ExtraBold)
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button(onClick = onPlayPause) { Text(if (state.isRunning) "Pause" else "Start") }
                    OutlinedButton(onClick = onReset) { Text("Reset") }
                    OutlinedButton(onClick = onClose) { Text("Exit fullscreen") }
                }
            }
        }
    }
}
