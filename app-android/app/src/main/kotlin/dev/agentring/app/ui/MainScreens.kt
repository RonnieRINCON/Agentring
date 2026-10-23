package dev.agentring.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.agentring.app.BuildConfig
import dev.agentring.app.Graph
import dev.agentring.app.R
import dev.agentring.app.data.Pairing
import dev.agentring.app.notify.Notifications
import dev.agentring.app.push.PushState
import dev.agentring.core.RelaySocket

private enum class Tab { HOME, MESSAGES, SETTINGS }

@Composable
fun MainScaffold(pairing: Pairing, openMessages: MutableState<Boolean>) {
    var tab by rememberSaveable { mutableStateOf(Tab.HOME) }
    LaunchedEffect(openMessages.value) {
        if (openMessages.value) {
            tab = Tab.MESSAGES
            openMessages.value = false
        }
    }
    val messages by Graph.messages.messages.collectAsStateWithLifecycle()
    val unread = messages.count { it.fromAgent && it.readAt == null }

    Scaffold(
        bottomBar = {
            NavigationBar {
                NavigationBarItem(
                    selected = tab == Tab.HOME,
                    onClick = { tab = Tab.HOME },
                    icon = { Icon(Icons.Filled.Home, null) },
                    label = { Text(stringResource(R.string.tab_home)) },
                    modifier = Modifier.testTag("tab_home"),
                )
                NavigationBarItem(
                    selected = tab == Tab.MESSAGES,
                    onClick = { tab = Tab.MESSAGES },
                    icon = {
                        BadgedBox(badge = { if (unread > 0 && tab != Tab.MESSAGES) Badge { Text("$unread") } }) {
                            Icon(Icons.Filled.Email, null)
                        }
                    },
                    label = { Text(stringResource(R.string.tab_messages)) },
                    modifier = Modifier.testTag("tab_messages"),
                )
                NavigationBarItem(
                    selected = tab == Tab.SETTINGS,
                    onClick = { tab = Tab.SETTINGS },
                    icon = { Icon(Icons.Filled.Settings, null) },
                    label = { Text(stringResource(R.string.tab_settings)) },
                    modifier = Modifier.testTag("tab_settings"),
                )
            }
        },
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            when (tab) {
                Tab.HOME -> HomeScreen(pairing)
                Tab.MESSAGES -> MessagesScreen(pairing)
                Tab.SETTINGS -> SettingsScreen(pairing)
            }
        }
    }
}

@Composable
private fun HomeScreen(pairing: Pairing) {
    val relay by Graph.connection.relayState.collectAsStateWithLifecycle()
    val agent by Graph.connection.agentOnline.collectAsStateWithLifecycle()
    val push by Graph.push.state.collectAsStateWithLifecycle()
    val revoked by Graph.pairing.revoked.collectAsStateWithLifecycle()
    val good = Color(0xFF2E7D32)
    val warn = Color(0xFFF9A825)
    val bad = MaterialTheme.colorScheme.error

    Column(
        Modifier.fillMaxSize().padding(20.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(pairing.agentName, style = MaterialTheme.typography.headlineMedium, modifier = Modifier.testTag("agent_name"))

        if (revoked) {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.revoked_title), style = MaterialTheme.typography.titleMedium)
                    Text(stringResource(R.string.revoked_body))
                    Button(onClick = { Graph.unpair() }) { Text(stringResource(R.string.pair_again)) }
                }
            }
        }

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                val (relayText, relayColor) = when (relay) {
                    RelaySocket.State.CONNECTED -> stringResource(R.string.connected) to good
                    RelaySocket.State.CONNECTING -> stringResource(R.string.connecting) to warn
                    RelaySocket.State.DISCONNECTED -> stringResource(R.string.offline) to bad
                }
                StatusRow(stringResource(R.string.status_relay), relayText, relayColor, "relay_status")
                val (agentText, agentColor) = when (agent) {
                    true -> stringResource(R.string.online) to good
                    false -> stringResource(R.string.offline) to bad
                    null -> stringResource(R.string.unknown) to Color.Gray
                }
                StatusRow(stringResource(R.string.status_agent), agentText, agentColor, "agent_status")
                val (pushText, pushColor) = when (val s = push) {
                    PushState.Registered -> stringResource(R.string.push_registered) to good
                    PushState.Registering -> stringResource(R.string.push_registering) to warn
                    PushState.NotConfigured -> stringResource(R.string.push_not_configured) to warn
                    is PushState.Unavailable -> stringResource(R.string.push_unavailable, s.reason) to bad
                    is PushState.Error -> stringResource(R.string.push_error, s.message) to bad
                }
                StatusRow(stringResource(R.string.status_push), pushText, pushColor, "push_status")
            }
        }

        Spacer(Modifier.height(8.dp))
        Button(onClick = {}, enabled = false, modifier = Modifier.fillMaxWidth().height(56.dp)) {
            Icon(Icons.Filled.Call, null)
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.call_agent))
        }
        Text(stringResource(R.string.call_soon), style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun StatusRow(label: String, value: String, color: Color, tag: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Surface(shape = CircleShape, color = color, modifier = Modifier.size(10.dp)) {}
        Spacer(Modifier.width(12.dp))
        Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Text(value, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.testTag(tag))
    }
}

@Composable
private fun SettingsScreen(pairing: Pairing) {
    val ctx = LocalContext.current
    var confirmUnpair by remember { mutableStateOf(false) }
    Column(
        Modifier.fillMaxSize().padding(20.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(stringResource(R.string.tab_settings), style = MaterialTheme.typography.headlineMedium)
        InfoRow(stringResource(R.string.settings_relay), pairing.relayUrl)
        InfoRow(stringResource(R.string.settings_agent), pairing.agentName)
        InfoRow(stringResource(R.string.settings_device), pairing.deviceId)
        InfoRow(stringResource(R.string.settings_version), BuildConfig.VERSION_NAME)
        HorizontalDivider()
        OutlinedButton(
            onClick = { Notifications.showTest(ctx) },
            modifier = Modifier.fillMaxWidth().testTag("test_notification"),
        ) { Text(stringResource(R.string.test_notification)) }
        OutlinedButton(onClick = { confirmUnpair = true }, modifier = Modifier.fillMaxWidth().testTag("unpair")) {
            Text(stringResource(R.string.unpair), color = MaterialTheme.colorScheme.error)
        }
    }
    if (confirmUnpair) {
        AlertDialog(
            onDismissRequest = { confirmUnpair = false },
            text = { Text(stringResource(R.string.unpair_confirm, pairing.deviceId)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmUnpair = false
                    Graph.unpair()
                }, modifier = Modifier.testTag("unpair_confirm")) { Text(stringResource(R.string.unpair)) }
            },
            dismissButton = { TextButton(onClick = { confirmUnpair = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Column {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyLarge)
    }
}
