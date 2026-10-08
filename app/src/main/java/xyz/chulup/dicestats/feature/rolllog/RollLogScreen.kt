package xyz.chulup.dicestats.feature.rolllog

import android.text.format.DateUtils
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddAPhoto
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.Casino
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.SportsEsports
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import xyz.chulup.dicestats.R
import xyz.chulup.dicestats.data.db.GameEntity
import xyz.chulup.dicestats.data.db.RollWithResults
import xyz.chulup.dicestats.feature.games.StartGameDialog
import java.io.File

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun RollLogScreen(
    onCapture: () -> Unit,
    onManualRoll: () -> Unit,
    onDiceStats: () -> Unit,
    onManageDice: () -> Unit,
    viewModel: RollLogViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    var showDeleteDialog by remember { mutableStateOf(false) }
    var showStartGameDialog by remember { mutableStateOf(false) }
    var showAddToGameDialog by remember { mutableStateOf(false) }

    BackHandler(enabled = uiState.inSelectionMode) { viewModel.clearSelection() }

    Scaffold(
        topBar = {
            if (uiState.inSelectionMode) {
                val count = uiState.selectedIds.size
                TopAppBar(
                    title = { Text(pluralStringResource(R.plurals.selection_count, count, count)) },
                    navigationIcon = {
                        IconButton(onClick = { viewModel.clearSelection() }) {
                            Icon(Icons.Default.Close, contentDescription = stringResource(R.string.selection_clear))
                        }
                    },
                    actions = {
                        IconButton(onClick = { showAddToGameDialog = true }) {
                            Icon(Icons.Default.SportsEsports, contentDescription = stringResource(R.string.selection_add_to_game))
                        }
                        IconButton(onClick = { showDeleteDialog = true }) {
                            Icon(Icons.Default.Delete, contentDescription = stringResource(R.string.selection_delete))
                        }
                    },
                )
            } else {
                TopAppBar(
                    title = { Text(stringResource(R.string.app_name)) },
                    actions = {
                        IconButton(onClick = onManageDice) {
                            Icon(Icons.Default.Casino, contentDescription = stringResource(R.string.dice_manage_title))
                        }
                        IconButton(onClick = onDiceStats) {
                            Icon(Icons.Default.BarChart, contentDescription = stringResource(R.string.dice_list_title))
                        }
                    },
                )
            }
        },
        floatingActionButton = {
            if (!uiState.inSelectionMode) {
                Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    FloatingActionButton(onClick = onManualRoll) {
                        Icon(Icons.Default.Edit, contentDescription = stringResource(R.string.roll_log_manual))
                    }
                    ExtendedFloatingActionButton(
                        onClick = onCapture,
                        icon = { Icon(Icons.Default.AddAPhoto, contentDescription = null) },
                        text = { Text(stringResource(R.string.roll_log_capture)) },
                    )
                }
            }
        },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            // Active-game banner / start control, hidden while selecting rolls.
            if (!uiState.inSelectionMode) {
                GameBanner(
                    activeGame = uiState.activeGame,
                    onStart = { showStartGameDialog = true },
                    onFinish = { viewModel.finishGame() },
                )
            }

            if (uiState.rolls.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(stringResource(R.string.roll_log_empty))
                }
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(120.dp),
                    contentPadding = PaddingValues(8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    items(uiState.rolls, key = { it.roll.id }) { roll ->
                        val selected = roll.roll.id in uiState.selectedIds
                        RollCell(
                            roll = roll,
                            selected = selected,
                            onClick = { if (uiState.inSelectionMode) viewModel.toggleSelection(roll.roll.id) },
                            onLongClick = { viewModel.toggleSelection(roll.roll.id) },
                        )
                    }
                }
            }
        }
    }

    if (showStartGameDialog) {
        StartGameDialog(
            onConfirm = { name, usesDicePools ->
                showStartGameDialog = false
                viewModel.startGame(name, usesDicePools)
            },
            onDismiss = { showStartGameDialog = false },
        )
    }

    if (showAddToGameDialog) {
        val count = uiState.selectedIds.size
        AddToGameDialog(
            games = uiState.games,
            rollCount = count,
            onPick = { gameId ->
                showAddToGameDialog = false
                viewModel.assignSelectedToGame(gameId)
            },
            onDismiss = { showAddToGameDialog = false },
        )
    }

    if (showDeleteDialog) {
        val count = uiState.selectedIds.size
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = { Text(stringResource(R.string.delete_dialog_title)) },
            text = { Text(pluralStringResource(R.plurals.delete_dialog_message, count, count)) },
            confirmButton = {
                TextButton(onClick = {
                    showDeleteDialog = false
                    viewModel.deleteSelected()
                }) { Text(stringResource(R.string.delete_confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = false }) {
                    Text(stringResource(R.string.dialog_cancel))
                }
            },
        )
    }
}

/** Shows the open game (with a Finish action) or a control to start one. */
@Composable
private fun GameBanner(
    activeGame: GameEntity?,
    onStart: () -> Unit,
    onFinish: () -> Unit,
) {
    if (activeGame == null) {
        TextButton(
            onClick = onStart,
            modifier = Modifier.padding(horizontal = 8.dp),
        ) {
            Icon(Icons.Default.SportsEsports, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.game_start))
        }
        return
    }

    val started = remember(activeGame.startedAt) {
        DateUtils.getRelativeTimeSpanString(activeGame.startedAt).toString()
    }
    Surface(
        tonalElevation = 3.dp,
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.secondaryContainer,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 4.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
        ) {
            Icon(Icons.Default.SportsEsports, contentDescription = null)
            Spacer(Modifier.width(12.dp))
            Text(
                text = stringResource(R.string.game_active_banner, activeGame.name, started),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onFinish) { Text(stringResource(R.string.game_finish)) }
        }
    }
}

/** Lets the user pick an existing game to add the selected rolls to. */
@Composable
private fun AddToGameDialog(
    games: List<GameEntity>,
    rollCount: Int,
    onPick: (Long) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.selection_add_to_game)) },
        text = {
            if (games.isEmpty()) {
                Text(stringResource(R.string.add_to_game_no_games))
            } else {
                Column(
                    modifier = Modifier
                        .heightIn(max = 320.dp)
                        .verticalScroll(rememberScrollState()),
                ) {
                    Text(
                        text = pluralStringResource(R.plurals.add_to_game_prompt, rollCount, rollCount),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 8.dp),
                    )
                    games.forEach { game ->
                        val started = remember(game.startedAt) {
                            DateUtils.getRelativeTimeSpanString(game.startedAt).toString()
                        }
                        ListItem(
                            headlineContent = { Text(game.name) },
                            supportingContent = {
                                Text(stringResource(R.string.game_started_at, started))
                            },
                            trailingContent = if (game.isActive) {
                                { Text(stringResource(R.string.game_open_badge), color = MaterialTheme.colorScheme.primary) }
                            } else null,
                            modifier = Modifier.clickable { onPick(game.id) },
                        )
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.dialog_cancel)) }
        },
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun RollCell(
    roll: RollWithResults,
    selected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    val shape = RoundedCornerShape(8.dp)
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(120.dp)
            .clip(shape)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick),
    ) {
        val photoPath = roll.roll.photoPath
        if (photoPath != null) {
            AsyncImage(
                model = File(photoPath),
                contentDescription = stringResource(R.string.roll_photo_desc),
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            // Manually-entered rolls have no photo: show a tinted dice-icon placeholder.
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.secondaryContainer),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Default.Casino,
                    contentDescription = stringResource(R.string.roll_manual_desc),
                    tint = MaterialTheme.colorScheme.onSecondaryContainer,
                    modifier = Modifier.size(48.dp),
                )
            }
        }

        // Summary of recognized values: the individual dice, prefixed by their sum
        // when there's more than one, e.g. "(sum: 7), 3, 4".
        val values = roll.results.map { it.value }
        if (values.isNotEmpty()) {
            val list = values.joinToString(", ")
            val summary = if (values.size > 1) {
                stringResource(R.string.roll_summary_with_sum, values.sum(), list)
            } else {
                list
            }
            Text(
                text = summary,
                color = Color.White,
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .fillMaxWidth()
                    .background(Color(0xAA000000))
                    .padding(horizontal = 6.dp, vertical = 2.dp),
            )
        }

        if (selected) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color(0x553F51B5))
                    .border(3.dp, MaterialTheme.colorScheme.primary, shape),
            )
            Icon(
                imageVector = Icons.Default.CheckCircle,
                contentDescription = stringResource(R.string.selected_indicator),
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(6.dp)
                    .clip(RoundedCornerShape(50))
                    .background(Color.White),
            )
        }
    }
}
