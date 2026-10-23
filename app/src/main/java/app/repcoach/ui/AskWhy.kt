@file:OptIn(ExperimentalLayoutApi::class)

package app.repcoach.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
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
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import app.repcoach.WhyChat

private val starters = listOf(
    "Explain this in plain words",
    "Why not more weight?",
    "I feel great today. Should I push harder?",
    "Is this too much given what I did recently?",
)

/**
 * "Ask why": the facts, rules and research behind one suggestion, then a chat with the coach about
 * it. Without a Claude key it still shows everything the app used, just without the chat.
 */
@Composable
fun AskWhyDialog(chat: WhyChat, hasKey: Boolean, onAsk: (String) -> Unit, onDismiss: () -> Unit) {
    var input by remember { mutableStateOf("") }
    val listState = rememberLazyListState()
    val itemCount = 3 + chat.turns.size + (if (chat.thinking) 1 else 0)
    LaunchedEffect(chat.turns.size, chat.thinking) { if (chat.turns.isNotEmpty()) listState.animateScrollToItem(itemCount - 1) }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize().imePadding()) {
                Row(Modifier.padding(start = 16.dp, end = 8.dp, top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Why: ${chat.topic.title}", Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
                    TextButton(onClick = onDismiss) { Text("Close") }
                }
                LazyColumn(Modifier.weight(1f).padding(horizontal = 16.dp), state = listState, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    item {
                        Card(Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text("What the app used", style = MaterialTheme.typography.titleMedium)
                                chat.topic.facts.forEach { Text("• $it", style = MaterialTheme.typography.bodySmall) }
                            }
                        }
                    }
                    item {
                        Card(Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text("The research behind it", style = MaterialTheme.typography.titleMedium)
                                chat.topic.evidence.forEach { e ->
                                    Column {
                                        Text(e.rule, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodySmall)
                                        Text("${e.finding} (${e.source})", style = MaterialTheme.typography.bodySmall)
                                    }
                                }
                                Text("Full references: docs/RESEARCH.md in the app's code.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                    item {
                        Text(
                            if (hasKey) "Ask the coach about this. It answers from your own training, the rule that fired and the research, and you can push back."
                            else "Add a Claude API key in Settings to ask questions and challenge this. Everything the app used is shown above.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    items(chat.turns) { t ->
                        Card(
                            Modifier.fillMaxWidth().padding(start = if (t.fromLifter) 40.dp else 0.dp, end = if (t.fromLifter) 0.dp else 24.dp),
                            colors = CardDefaults.cardColors(
                                containerColor = if (t.fromLifter) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
                            ),
                        ) { Text(t.text, Modifier.padding(12.dp), style = MaterialTheme.typography.bodyMedium) }
                    }
                    if (chat.thinking) item {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                            Text("Thinking…", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
                chat.error?.let { Text(it, Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                if (hasKey) {
                    if (chat.turns.isEmpty()) {
                        FlowRow(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            starters.forEach { q -> AssistChip(onClick = { onAsk(q) }, label = { Text(q) }, enabled = !chat.thinking) }
                        }
                    }
                    Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = input, onValueChange = { input = it }, modifier = Modifier.weight(1f),
                            placeholder = { Text("Ask or challenge…") }, maxLines = 4,
                        )
                        Button(onClick = { onAsk(input); input = "" }, enabled = input.isNotBlank() && !chat.thinking) { Text("Send") }
                    }
                }
            }
        }
    }
}
