package xyz.chulup.dicestats.feature.games

import android.text.format.DateUtils
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import xyz.chulup.dicestats.R

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GamesScreen(
    onBack: () -> Unit,
    onGameSelected: (Long) -> Unit,
    viewModel: GamesViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    var showStartDialog by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.games_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                    }
                },
            )
        },
        floatingActionButton = {
            // A new game can't start while one is already open.
            if (!uiState.hasActiveGame) {
                ExtendedFloatingActionButton(
                    onClick = { showStartDialog = true },
                    icon = { Icon(Icons.Default.Add, contentDescription = null) },
                    text = { Text(stringResource(R.string.game_start)) },
                )
            }
        },
    ) { padding ->
        if (uiState.games.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentAlignment = Alignment.Center,
            ) {
                Text(stringResource(R.string.games_empty))
            }
        } else {
            LazyColumn(modifier = Modifier.fillMaxSize().padding(padding)) {
                items(uiState.games, key = { it.id }) { game ->
                    GameItem(
                        game = game,
                        onClick = { onGameSelected(game.id) },
                        onFinish = { viewModel.finishGame(game.id) },
                    )
                }
            }
        }
    }

    if (showStartDialog) {
        StartGameDialog(
            onConfirm = { name ->
                showStartDialog = false
                viewModel.startGame(name)
            },
            onDismiss = { showStartDialog = false },
        )
    }
}

@Composable
private fun GameItem(game: GameRow, onClick: () -> Unit, onFinish: () -> Unit) {
    val timing = if (game.isActive) {
        stringResource(
            R.string.game_started_at,
            DateUtils.getRelativeTimeSpanString(game.startedAt).toString(),
        )
    } else {
        stringResource(
            R.string.game_ended_at,
            DateUtils.getRelativeTimeSpanString(game.endedAt ?: game.startedAt).toString(),
        )
    }
    val rolls = pluralStringResource(R.plurals.game_roll_count, game.rollCount, game.rollCount)

    ListItem(
        leadingContent = {
            if (game.isActive) {
                AssistChip(
                    onClick = {},
                    enabled = false,
                    label = { Text(stringResource(R.string.game_open_badge)) },
                    colors = AssistChipDefaults.assistChipColors(
                        disabledLabelColor = MaterialTheme.colorScheme.primary,
                    ),
                )
            }
        },
        headlineContent = { Text(game.name) },
        supportingContent = { Text("$timing · $rolls") },
        trailingContent = {
            // Active games still expose Finish; closed games just show a drill-in chevron.
            if (game.isActive) {
                TextButton(onClick = onFinish) { Text(stringResource(R.string.game_finish)) }
            } else {
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null)
            }
        },
        modifier = Modifier.clickable(onClick = onClick),
    )
}

/** Name-entry dialog for starting a new game. */
@Composable
private fun StartGameDialog(
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.game_start)) },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                singleLine = true,
                label = { Text(stringResource(R.string.game_name_hint)) },
            )
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(name) },
                enabled = name.isNotBlank(),
            ) { Text(stringResource(R.string.game_start)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.dialog_cancel)) }
        },
    )
}
