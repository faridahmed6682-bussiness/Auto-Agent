package com.example

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import com.example.ai.configureAppCheck
import com.example.ui.AgentViewModel
import com.example.ui.DashboardScreen
import com.example.ui.theme.TapPilotTheme

class MainActivity : ComponentActivity() {

    private val viewModel: AgentViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Configure Firebase App Check with launch intent before any UI or Firebase calls
        configureAppCheck(this, intent)

        enableEdgeToEdge()
        setContent {
            TapPilotTheme {
                DashboardScreen(viewModel = viewModel)
            }
        }
    }
}
