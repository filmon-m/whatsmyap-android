package io.github.filmonm.whatsmyap

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import io.github.filmonm.whatsmyap.ui.CameraScreen
import io.github.filmonm.whatsmyap.ui.FieldSurveyScreen
import io.github.filmonm.whatsmyap.ui.FieldViewModel
import io.github.filmonm.whatsmyap.ui.HomeScreen
import io.github.filmonm.whatsmyap.ui.ReportScreen

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { AppTheme { App(this) } }
    }
}

@Composable
fun AppTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme(), content = content)
}

@Composable
fun App(activity: ComponentActivity) {
    val nav = rememberNavController()
    // one ViewModel for the whole Field Survey flow (survey, camera and report share it)
    val field: FieldViewModel = viewModel(viewModelStoreOwner = activity)
    NavHost(navController = nav, startDestination = "home") {
        composable("home") { HomeScreen(onField = { nav.navigate("field") }) }
        composable("field") {
            FieldSurveyScreen(field, onCamera = { nav.navigate("camera") }, onReport = { nav.navigate("report") },
                onBack = { nav.popBackStack() })
        }
        composable("camera") { CameraScreen(field, onDone = { nav.popBackStack() }) }
        composable("report") { ReportScreen(field, onBack = { nav.popBackStack() }) }
    }
}
