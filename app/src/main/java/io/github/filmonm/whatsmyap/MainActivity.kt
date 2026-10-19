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
import io.github.filmonm.whatsmyap.ui.DebugApScreen
import io.github.filmonm.whatsmyap.ui.DebugWlcScreen
import io.github.filmonm.whatsmyap.ui.HomeScreen
import io.github.filmonm.whatsmyap.ui.InspectScreen
import io.github.filmonm.whatsmyap.ui.NetEngScreen
import io.github.filmonm.whatsmyap.ui.NetEngViewModel
import io.github.filmonm.whatsmyap.ui.ReportScreen
import io.github.filmonm.whatsmyap.ui.SettingsScreen
import io.github.filmonm.whatsmyap.ui.VerifyScreen

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
    val neteng: NetEngViewModel = viewModel(viewModelStoreOwner = activity)
    neteng.field = field
    NavHost(navController = nav, startDestination = "home") {
        composable("home") { HomeScreen(onField = { nav.navigate("field") }, onNetEng = { nav.navigate("neteng") },
                onResults = { nav.navigate("results") }, onSettings = { nav.navigate("settings") }) }
        composable("settings") { SettingsScreen(field, onBack = { nav.popBackStack() }) }
        composable("field") {
            FieldSurveyScreen(field, onCamera = { nav.navigate("camera") }, onReport = { nav.navigate("report") },
                onBack = { nav.popBackStack() })
        }
        composable("camera") { CameraScreen(field, onDone = { nav.popBackStack() }) }
        composable("report") { ReportScreen(field, onBack = { nav.popBackStack() }) }
        composable("results") { ReportScreen(field, onBack = { nav.popBackStack() }, all = true) }
        composable("neteng") {
            NetEngScreen(neteng, onInspect = { nav.navigate("inspect") }, onDebugAp = { nav.navigate("debugap") },
                onDebugWlc = { nav.navigate("debugwlc") }, onVerify = { nav.navigate("verify") }, onBack = { nav.popBackStack() })
        }
        composable("inspect") { InspectScreen(neteng, field, onCamera = { nav.navigate("camera") }, onBack = { nav.popBackStack() }) }
        composable("debugap") { DebugApScreen(neteng, field, onBack = { nav.popBackStack() }) }
        composable("debugwlc") { DebugWlcScreen(neteng, field, onBack = { nav.popBackStack() }) }
        composable("verify") { VerifyScreen(neteng, onBack = { nav.popBackStack() }) }
    }
}
