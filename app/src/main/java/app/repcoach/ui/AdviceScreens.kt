package app.repcoach.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.repcoach.AppViewModel
import app.repcoach.COACH_CHAT
import app.repcoach.UiState
import app.repcoach.core.Exercises
import app.repcoach.core.Planner
import app.repcoach.core.Programme
import app.repcoach.core.Progression
import java.util.Locale

/** The 8-week block: what Full Body A and B are, why each lift is there, and where the lifter is in it. */
@Composable
fun ProgrammeScreen(state: UiState, vm: AppViewModel) {
    val p = remember(state.workouts) { Progression(state.workouts) }
    var historyFor by remember { mutableStateOf<String?>(null) }
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Your programme", style = MaterialTheme.typography.headlineSmall)
        val programme = state.programme
        if (programme == null) {
            Text("Full Body A and B are designed from your Hevy history.", style = MaterialTheme.typography.bodyMedium)
            Button(onClick = { vm.ensureProgramme() }, enabled = state.workouts.isNotEmpty()) { Text("Design my programme") }
            if (state.workouts.isEmpty()) Text("Import your Hevy history first (Today tab).", style = MaterialTheme.typography.bodySmall)
            return@Column
        }
        val week = programme.week(System.currentTimeMillis())
        val due = remember(state.workouts, programme) { Planner(p, programme).dueDay() }
        Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Block ${programme.block}, week ${week.coerceAtMost(Programme.BLOCK_WEEKS)} of ${Programme.BLOCK_WEEKS}", style = MaterialTheme.typography.titleMedium)
                Text(due?.let { "Next up: ${it.title}" } ?: "A and B are done this week. Any extra day is a mixed session.")
                if (week > Programme.BLOCK_WEEKS) Text("This block has run its course: start a new one below.", color = MaterialTheme.colorScheme.error)
            }
        }
        Text("How it works", style = MaterialTheme.typography.titleMedium)
        Text(
            "Do Full Body A and B once each a week, about ${Programme.MINUTES} minutes each. The main lifts (★) stay the same for " +
                "${Programme.BLOCK_WEEKS} weeks, so every session is a chance to beat last time. Any extra gym day is a mixed session " +
                "with different exercises for the muscles that need it most. Every muscle gets worked at least twice a week.",
            style = MaterialTheme.typography.bodyMedium,
        )
        Text(
            "Progression: hit the top of the rep range on every set and the weight goes up 2.5 kg next time; otherwise aim for one more rep.",
            style = MaterialTheme.typography.bodyMedium,
        )
        programme.days.forEach { day ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(day.title, style = MaterialTheme.typography.titleMedium)
                    day.exercises.forEachIndexed { i, e ->
                        if (i > 0) HorizontalDivider()
                        val info = Exercises.info(e.name)
                        val target = remember(state.workouts, e) { p.target(e.name, 7, e.sets) }
                        val trend = remember(state.workouts, e.name) { p.trend(e.name) }
                        Column {
                            Text((if (e.anchor) "★ " else "") + e.name, fontWeight = FontWeight.SemiBold)
                            Text(
                                "${e.sets} × ${info.repRange.first}–${info.repRange.last}" +
                                    (target.weightKg?.let { " · next time ${Progression.fmt(it)} kg" } ?: " · pick a starting weight"),
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            Text(
                                (if (e.anchor) "Main lift: fixed for the block. " else "Supporting lift: changes each block. ") + info.repReason + ".",
                                style = MaterialTheme.typography.bodySmall,
                            )
                            trend?.let { t ->
                                Text(
                                    "Best estimated 1RM ${t.bestRecentE1rm.toInt()} kg" +
                                        (t.changePercent?.let { " (%+.1f%% on the 8 weeks before)".format(Locale.UK, it) } ?: "") +
                                        if (t.plateau) ". Stalled." else ".",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = if (t.plateau) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Row {
                                TextButton(onClick = { vm.startQuestion(COACH_CHAT, "Why is ${e.name} in ${day.title.substringBefore(":")}? ") }) { Text("Ask") }
                                TextButton(onClick = { historyFor = e.name }) { Text("History") }
                            }
                        }
                    }
                }
            }
        }
        OutlinedButton(onClick = { vm.startQuestion(COACH_CHAT, "Talk me through my programme: ") }, Modifier.fillMaxWidth()) { Text("Ask the coach about the programme") }
        OutlinedButton(onClick = { vm.newBlock() }, Modifier.fillMaxWidth()) { Text("Start a new block") }
        Text("Keeps the main lifts and moves the supporting lifts on to another variation you know.", style = MaterialTheme.typography.bodySmall)
    }
    historyFor?.let { ExerciseHistoryDialog(it, state.workouts, onDismiss = { historyFor = null }) }
}

private val coachStarters = listOf(
    "How is my bench progressing?",
    "What should I focus on this block?",
    "Am I doing enough for each muscle?",
    "Do I need a deload?",
)

/** The general chat: questions about training, the programme and progress, not tied to one workout. */
@Composable
fun CoachScreen(state: UiState, vm: AppViewModel) {
    val listState = rememberLazyListState()
    val thinking = state.chatBusy == COACH_CHAT
    LaunchedEffect(state.coachChat.size, thinking) {
        if (state.coachChat.isNotEmpty()) listState.animateScrollToItem(listState.layoutInfo.totalItemsCount.coerceAtLeast(1) - 1)
    }
    Column(Modifier.fillMaxSize().imePadding()) {
        LazyColumn(Modifier.weight(1f).padding(horizontal = 16.dp), state = listState, verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item {
                Row(Modifier.padding(top = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Ask the coach", Modifier.weight(1f), style = MaterialTheme.typography.headlineSmall)
                    if (state.coachChat.isNotEmpty()) TextButton(onClick = { vm.clearCoachChat() }, enabled = !thinking) { Text("Clear") }
                }
                Text(
                    "Ask anything about your training. The coach sees your programme, your Hevy history, lift trends and weekly volume. " +
                        "For questions about one workout, open it from the Today tab.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            chatMessages(state.coachChat, thinking)
            item { Spacer(Modifier.height(8.dp)) }
        }
        ChatInput(
            hasKey = state.settings.apiKey.isNotBlank(),
            busy = state.chatBusy != null,
            error = state.chatError,
            starters = if (state.coachChat.isEmpty()) coachStarters else emptyList(),
            draft = state.draft?.takeIf { it.first == COACH_CHAT }?.second,
            onDraftUsed = vm::draftUsed,
            onAsk = vm::askCoach,
        )
    }
}
