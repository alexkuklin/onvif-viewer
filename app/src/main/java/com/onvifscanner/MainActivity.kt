package com.onvifscanner

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.onvifscanner.ui.screens.CameraListScreen
import com.onvifscanner.ui.screens.VideoPlayerScreen
import com.onvifscanner.ui.theme.OnvifScannerTheme
import com.onvifscanner.viewmodel.CameraViewModel

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            OnvifScannerTheme {
                OnvifScannerApp()
            }
        }
    }
}

@Composable
fun OnvifScannerApp() {
    val navController = rememberNavController()
    val viewModel: CameraViewModel = viewModel()

    NavHost(
        navController = navController,
        startDestination = "cameras",
        modifier = Modifier.fillMaxSize()
    ) {
        composable("cameras") {
            CameraListScreen(
                viewModel = viewModel,
                onCameraSelected = { deviceId ->
                    navController.navigate("player")
                }
            )
        }

        composable("player") {
            VideoPlayerScreen(
                viewModel = viewModel,
                onBack = {
                    navController.popBackStack()
                }
            )
        }
    }
}
