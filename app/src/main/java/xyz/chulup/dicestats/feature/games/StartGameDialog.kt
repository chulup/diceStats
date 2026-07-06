package xyz.chulup.dicestats.feature.games

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import xyz.chulup.dicestats.R

/**
 * Name-entry dialog for starting a new game, with the dice-pools opt-in — the flag
 * that gates pool auto-assignment on the confirm screen (DESIGN.md "Die Pools").
 * Shared by every place a game can be started (Games screen, home banner).
 */
@Composable
fun StartGameDialog(
    onConfirm: (name: String, usesDicePools: Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by remember { mutableStateOf("") }
    var usesDicePools by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.game_start)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    singleLine = true,
                    label = { Text(stringResource(R.string.game_name_hint)) },
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.clickable { usesDicePools = !usesDicePools },
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(stringResource(R.string.game_uses_pools))
                        Text(
                            text = stringResource(R.string.game_uses_pools_desc),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(
                        checked = usesDicePools,
                        onCheckedChange = { usesDicePools = it },
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(name, usesDicePools) },
                enabled = name.isNotBlank(),
            ) { Text(stringResource(R.string.game_start)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.dialog_cancel)) }
        },
    )
}
