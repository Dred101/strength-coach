@file:OptIn(ExperimentalMaterial3Api::class)

package app.repcoach.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.repcoach.AppViewModel
import app.repcoach.Screen

private val tabs = listOf(Screen.HOME to "Today", Screen.HISTORY to "History", Screen.PROGRESS to "Charts", Screen.RECORDS to "Records", Screen.SETTINGS to "Settings")

@Composable
fun App(vm: AppViewModel) {
    val state by vm.state.collectAsState()
    val inFlow = state.screen in setOf(Screen.CHECK_IN, Screen.PLAN, Screen.WORKOUT)

    BackHandler(enabled = state.screen != Screen.HOME && state.screen != Screen.WORKOUT) {
        vm.go(if (state.screen == Screen.PLAN) Screen.CHECK_IN else Screen.HOME)
    }

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Scaffold(
            modifier = Modifier.safeDrawingPadding(),
            bottomBar = {
                if (!inFlow) {
                    TabRow(selectedTabIndex = tabs.indexOfFirst { it.first == state.screen }.coerceAtLeast(0)) {
                        tabs.forEach { (screen, label) ->
                            Tab(selected = state.screen == screen, onClick = { vm.go(screen) }, text = { Text(label) })
                        }
                    }
                }
            },
        ) { padding ->
            Box(Modifier.padding(padding).fillMaxSize()) {
                Column(Modifier.fillMaxSize()) {
                    state.message?.let { Banner(it) { vm.dismissMessage() } }
                    when (state.screen) {
                        Screen.HOME -> HomeScreen(state, vm)
                        Screen.CHECK_IN -> CheckInScreen(state, vm)
                        Screen.PLAN -> PlanScreen(state, vm)
                        Screen.WORKOUT -> WorkoutScreen(state, vm)
                        Screen.HISTORY -> HistoryScreen(state, vm)
                        Screen.PROGRESS -> ProgressScreen(state)
                        Screen.RECORDS -> RecordsScreen(state)
                        Screen.SETTINGS -> SettingsScreen(state, vm)
                    }
                }
                state.busy?.let { Busy(it) }
            }
        }
    }
}

@Composable
private fun Banner(text: String, onDismiss: () -> Unit) {
    Card(
        Modifier.fillMaxWidth().padding(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
    ) {
        Row(Modifier.padding(start = 16.dp, end = 4.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(text, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
            TextButton(onClick = onDismiss) { Text("OK") }
        }
    }
}

@Composable
private fun Busy(text: String) {
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background.copy(alpha = 0.85f)) {
        Column(
            Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            CircularProgressIndicator(Modifier.size(48.dp))
            Spacer(Modifier.height(16.dp))
            Text(text, style = MaterialTheme.typography.titleMedium)
        }
    }
}
