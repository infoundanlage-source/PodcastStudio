package com.timerflow

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.timerflow.ui.screens.EditScreen
import com.timerflow.ui.screens.HomeScreen
import com.timerflow.ui.screens.RunScreen
import com.timerflow.ui.theme.TimerFlowTheme
import com.timerflow.viewmodel.RunViewModel
import com.timerflow.viewmodel.SequenceViewModel

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            TimerFlowTheme {
                val navController = rememberNavController()
                val seqViewModel: SequenceViewModel = viewModel()
                val runViewModel: RunViewModel = viewModel()

                NavHost(navController = navController, startDestination = "home") {

                    composable("home") {
                        HomeScreen(
                            viewModel = seqViewModel,
                            onEdit = { id -> navController.navigate("edit/${id ?: 0}") },
                            onRun = { id -> navController.navigate("run/$id") }
                        )
                    }

                    composable(
                        "edit/{sequenceId}",
                        arguments = listOf(navArgument("sequenceId") { type = NavType.LongType })
                    ) { back ->
                        val id = back.arguments?.getLong("sequenceId")?.takeIf { it != 0L }
                        EditScreen(
                            sequenceId = id,
                            viewModel = seqViewModel,
                            onBack = { navController.popBackStack() }
                        )
                    }

                    composable(
                        "run/{sequenceId}",
                        arguments = listOf(navArgument("sequenceId") { type = NavType.LongType })
                    ) { back ->
                        val id = back.arguments!!.getLong("sequenceId")
                        RunScreen(
                            sequenceId = id,
                            seqViewModel = seqViewModel,
                            runViewModel = runViewModel,
                            onBack = { navController.popBackStack() }
                        )
                    }
                }
            }
        }
    }
}
