package dev.agentring.app.ui

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import dev.agentring.app.R
import dev.agentring.app.call.CallPermissions
import dev.agentring.app.call.CallPermissions.Item

private fun Item.label() = when (this) {
    Item.NOTIFICATIONS -> R.string.perm_notifications
    Item.MICROPHONE -> R.string.perm_microphone
    Item.FULL_SCREEN -> R.string.perm_full_screen
    Item.BATTERY -> R.string.perm_battery
    Item.BACKGROUND -> R.string.perm_background
    Item.DND -> R.string.perm_dnd
}

/** True when every required item is in place; re-checked every time the screen resumes. */
@Composable
fun rememberCallsReady(): Boolean {
    val ctx = LocalContext.current
    var tick by remember { mutableIntStateOf(0) }
    LifecycleResumeEffect(Unit) {
        tick++
        onPauseOrDispose {}
    }
    return remember(tick) { CallPermissions.required.all { CallPermissions.isOk(ctx, it) } }
}

/** Checklist of what calls need, each with a "Fix" button. */
@Composable
fun CallReadinessCard(modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    var tick by remember { mutableIntStateOf(0) }
    LifecycleResumeEffect(Unit) {
        tick++
        onPauseOrDispose {}
    }
    val mic = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { tick++ }
    val notif = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { tick++ }
    val status = remember(tick) { Item.entries.associateWith { CallPermissions.isOk(ctx, it) } }

    Card(modifier.fillMaxWidth().testTag("call_readiness")) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(stringResource(R.string.perm_title), style = MaterialTheme.typography.titleMedium)
            Text(stringResource(R.string.perm_body), style = MaterialTheme.typography.bodySmall)
            for (item in Item.entries) {
                val ok = status[item] == true
                val required = item in CallPermissions.required || item == Item.BATTERY
                Row(verticalAlignment = Alignment.CenterVertically) {
                    val color = when {
                        ok -> Color(0xFF2E7D32)
                        required -> MaterialTheme.colorScheme.error
                        else -> Color.Gray
                    }
                    Surface(shape = CircleShape, color = color, modifier = Modifier.size(10.dp)) {}
                    Spacer(Modifier.width(12.dp))
                    Text(stringResource(item.label()), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                    if (ok) {
                        Text(stringResource(R.string.perm_ok), style = MaterialTheme.typography.labelLarge, color = color)
                    } else {
                        TextButton(
                            onClick = {
                                when (item) {
                                    Item.MICROPHONE -> mic.launch(Manifest.permission.RECORD_AUDIO)
                                    Item.NOTIFICATIONS -> if (android.os.Build.VERSION.SDK_INT >= 33) {
                                        notif.launch(Manifest.permission.POST_NOTIFICATIONS)
                                    } else {
                                        ctx.startActivity(CallPermissions.settingsIntent(ctx, item))
                                    }
                                    else -> runCatching { ctx.startActivity(CallPermissions.settingsIntent(ctx, item)) }
                                }
                            },
                            modifier = Modifier.testTag("fix_${item.name.lowercase()}"),
                        ) { Text(stringResource(R.string.perm_fix)) }
                    }
                }
            }
            Text(stringResource(R.string.perm_oem_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
