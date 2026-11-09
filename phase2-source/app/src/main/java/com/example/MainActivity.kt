package com.example

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.ui.MainApp
import com.example.ui.theme.TaskitatorTheme
import com.example.ui.viewmodel.TaskitatorViewModel
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val viewModel: TaskitatorViewModel = viewModel()
            val settings by viewModel.settings.collectAsState()
            TaskitatorTheme(natureTheme = settings.appearanceTheme == "nature") {
                CompositionLocalProvider(LocalLayoutDirection provides
                    if (settings.language == "ar") LayoutDirection.Rtl else LayoutDirection.Ltr) {
                    MainApp(viewModel = viewModel)
                }
            }
        }
    }
}
