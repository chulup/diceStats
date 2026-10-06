package xyz.chulup.dicestats.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import xyz.chulup.dicestats.R
import xyz.chulup.dicestats.data.DieType

/**
 * Registers a new die: name, a **die-type** (d4…d100) selector, and a count stepper
 * (`> 1` makes it a pool of interchangeable dice — DESIGN.md "Die Pools"). Shared by the
 * confirm screen and the manual-roll carousel.
 *
 * [onConfirm] reports `(name, faces, count)` where `faces` is the *dN* number stored on
 * the die ([DieType.faces]).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun RegisterDieDialog(
    onDismiss: () -> Unit,
    onConfirm: (name: String, faces: Int, count: Int) -> Unit,
) {
    var name by remember { mutableStateOf("") }
    var faces by remember { mutableIntStateOf(DieType.DEFAULT.faces) }
    var count by remember { mutableIntStateOf(1) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.confirm_register_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    singleLine = true,
                    label = { Text(stringResource(R.string.confirm_die_name)) },
                )
                Text(stringResource(R.string.register_die_type))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    DieType.entries.forEach { type ->
                        FilterChip(
                            selected = faces == type.faces,
                            onClick = { faces = type.faces },
                            label = { Text(stringResource(R.string.die_type_label, type.faces)) },
                        )
                    }
                }
                // >1 registers a pool of interchangeable dice (DESIGN.md "Die Pools").
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = stringResource(R.string.confirm_die_count),
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(onClick = { count-- }, enabled = count > 1) {
                        Icon(
                            Icons.Default.Remove,
                            contentDescription = stringResource(R.string.confirm_die_count_decrement),
                        )
                    }
                    Text(text = count.toString())
                    IconButton(onClick = { count++ }) {
                        Icon(
                            Icons.Default.Add,
                            contentDescription = stringResource(R.string.confirm_die_count_increment),
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(name, faces, count) },
                enabled = name.isNotBlank(),
            ) {
                Text(stringResource(R.string.confirm_register_action))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.dialog_cancel)) }
        },
    )
}
