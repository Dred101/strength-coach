package app.repcoach

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import app.repcoach.ui.App
import app.repcoach.ui.RepCoachTheme

class MainActivity : ComponentActivity() {
    private val vm: AppViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // Opened from Health Connect's "why does this app need access" link: go straight to settings.
        if (intent?.action == "androidx.health.ACTION_SHOW_PERMISSIONS_RATIONALE" ||
            intent?.action == "android.intent.action.VIEW_PERMISSION_USAGE"
        ) vm.go(Screen.SETTINGS)
        setContent {
            RepCoachTheme { App(vm) }
        }
    }

    override fun onResume() {
        super.onResume()
        vm.refreshHealth()
    }
}
