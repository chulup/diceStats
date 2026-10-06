package xyz.chulup.dicestats.feature.manualroll

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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Backspace
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import xyz.chulup.dicestats.R
import xyz.chulup.dicestats.data.DieType
import xyz.chulup.dicestats.data.db.DieEntity
import xyz.chulup.dicestats.ui.RegisterDieDialog
import xyz.chulup.dicestats.ui.displayName

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ManualRollScreen(
    onBack: () -> Unit,
    viewModel: ManualRollViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var showDiscardDialog by remember { mutableStateOf(false) }

    LaunchedEffect(state.saved) {
        if (state.saved) onBack()
    }

    val leave: () -> Unit = {
        if (state.canSave) showDiscardDialog = true else onBack()
    }
    BackHandler(enabled = true, onBack = leave)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.manual_roll_title)) },
                navigationIcon = {
                    IconButton(onClick = leave) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                    }
                },
                actions = {
                    TextButton(onClick = viewModel::save, enabled = state.canSave) {
                        Icon(Icons.Default.Check, contentDescription = null)
                        Spacer(Modifier.width(4.dp))
                        Text(stringResource(R.string.manual_roll_save))
                    }
                },
            )
        },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            DiceCarousel(
                dice = state.dice,
                selectedDieId = state.selectedDieId,
                onSelect = viewModel::selectDie,
                onRegister = viewModel::registerDie,
            )

            RollList(
                state = state,
                onSelectLine = viewModel::selectLine,
                onSelectResult = viewModel::selectResult,
                onDeleteResult = viewModel::deleteResult,
                modifier = Modifier.weight(1f).fillMaxWidth(),
            )

            if (state.selectedDie == null) {
                Text(
                    text = stringResource(R.string.manual_roll_no_die),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                )
            } else {
                KeypadPanel(
                    dieType = state.dieType,
                    onValue = viewModel::tapValue,
                    onNext = viewModel::nextRoll,
                )
            }
        }
    }

    if (showDiscardDialog) {
        AlertDialog(
            onDismissRequest = { showDiscardDialog = false },
            title = { Text(stringResource(R.string.manual_roll_discard_title)) },
            text = { Text(stringResource(R.string.manual_roll_discard_message)) },
            confirmButton = {
                TextButton(onClick = {
                    showDiscardDialog = false
                    onBack()
                }) { Text(stringResource(R.string.manual_roll_discard_confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { showDiscardDialog = false }) {
                    Text(stringResource(R.string.dialog_cancel))
                }
            },
        )
    }
}

/** Horizontal strip of registered dice/pools; the selected one drives the keypad. */
@Composable
private fun DiceCarousel(
    dice: List<DieEntity>,
    selectedDieId: Long?,
    onSelect: (Long) -> Unit,
    onRegister: (String, Int, Int) -> Unit,
) {
    var showRegisterDialog by remember { mutableStateOf(false) }
    LazyRow(
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(dice, key = { it.id }) { die ->
            val selected = die.id == selectedDieId
            val container =
                if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant
            val content =
                if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(16.dp))
                    .background(container)
                    .clickable { onSelect(die.id) }
                    .padding(horizontal = 16.dp, vertical = 10.dp),
            ) {
                Text(text = die.displayName(), color = content)
            }
        }
        item {
            OutlinedButton(onClick = { showRegisterDialog = true }) {
                Icon(Icons.Default.Add, contentDescription = null)
                Spacer(Modifier.width(4.dp))
                Text(stringResource(R.string.manual_roll_register))
            }
        }
    }

    if (showRegisterDialog) {
        RegisterDieDialog(
            onDismiss = { showRegisterDialog = false },
            onConfirm = { name, faces, count ->
                showRegisterDialog = false
                onRegister(name, faces, count)
            },
        )
    }
}

/** The roll lines entered so far, active line highlighted; empty state when nothing yet. */
@Composable
private fun RollList(
    state: ManualRollUiState,
    onSelectLine: (Int) -> Unit,
    onSelectResult: (Int) -> Unit,
    onDeleteResult: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (!state.canSave) {
        Box(modifier = modifier, contentAlignment = Alignment.Center) {
            Text(
                text = stringResource(R.string.manual_roll_empty),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(24.dp),
            )
        }
        return
    }
    LazyColumn(
        modifier = modifier,
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        itemsIndexed(state.lines, key = { _, line -> line.localId }) { index, line ->
            // The trailing empty line only shows when it is the active one being built.
            if (line.results.isEmpty() && index != state.activeLineIndex) return@itemsIndexed
            RollLineRow(
                groups = groupForDisplay(line.results, state.dice),
                active = index == state.activeLineIndex,
                editingResultIndex = if (index == state.activeLineIndex) state.editingResultIndex else null,
                onClick = { onSelectLine(index) },
                onChipClick = { flatIndex ->
                    onSelectLine(index)
                    onSelectResult(flatIndex)
                },
                onChipLongClick = { flatIndex ->
                    onSelectLine(index)
                    onDeleteResult(flatIndex)
                },
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun RollLineRow(
    groups: List<DisplayGroup>,
    active: Boolean,
    editingResultIndex: Int?,
    onClick: () -> Unit,
    onChipClick: (Int) -> Unit,
    onChipLongClick: (Int) -> Unit,
) {
    val border = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant
    Surface(
        shape = RoundedCornerShape(12.dp),
        tonalElevation = if (active) 3.dp else 0.dp,
        color = if (active) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surface,
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, border, RoundedCornerShape(12.dp))
            .clickable(onClick = onClick),
    ) {
        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
            if (groups.isEmpty()) {
                Text(
                    text = stringResource(R.string.manual_roll_no_die),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                return@Column
            }
            // flatIndex walks the same tap order as the underlying results list.
            var flatIndex = 0
            groups.forEach { group ->
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 2.dp)) {
                    Text(
                        text = group.die.displayName() + ": ",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    group.values.forEach { value ->
                        val chipIndex = flatIndex
                        ValueChip(
                            value = value,
                            selected = chipIndex == editingResultIndex,
                            onClick = { onChipClick(chipIndex) },
                            onLongClick = { onChipLongClick(chipIndex) },
                        )
                        Spacer(Modifier.width(6.dp))
                        flatIndex++
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ValueChip(
    value: Int,
    selected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    val container = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant
    val content = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(container)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(horizontal = 10.dp, vertical = 4.dp),
    ) {
        Text(text = value.toString(), color = content, style = MaterialTheme.typography.bodyLarge)
    }
}

/**
 * The value keypad. For d4–d12 and d100 every face is a button (one tap commits). d20 needs
 * two-digit values, so it uses a 0–9 pad with a backspace and a commit key. A wide
 * "Next roll" key ends the current line.
 */
@Composable
private fun KeypadPanel(
    dieType: DieType,
    onValue: (Int) -> Unit,
    onNext: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (dieType == DieType.D20) {
            NumericPad(minValue = dieType.minValue, maxValue = dieType.maxValue, onValue = onValue)
        } else {
            FaceGrid(faceValues = dieType.faceValues, onValue = onValue)
        }
        Button(onClick = onNext, modifier = Modifier.fillMaxWidth().height(52.dp)) {
            Text(stringResource(R.string.manual_roll_next))
        }
    }
}

/** A grid of face-value buttons; one tap commits that value. */
@Composable
private fun FaceGrid(faceValues: List<Int>, onValue: (Int) -> Unit) {
    val cols = if (faceValues.size <= 4) 2 else if (faceValues.size <= 6) 3 else 5
    faceValues.chunked(cols).forEach { rowValues ->
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            rowValues.forEach { value ->
                KeyButton(label = value.toString(), modifier = Modifier.weight(1f)) { onValue(value) }
            }
            // Pad the final short row so buttons keep a uniform width.
            repeat(cols - rowValues.size) { Spacer(Modifier.weight(1f)) }
        }
    }
}

/** A 0–9 numeric pad with backspace + commit, clamped to [minValue]..[maxValue]. */
@Composable
private fun NumericPad(minValue: Int, maxValue: Int, onValue: (Int) -> Unit) {
    var buffer by remember { mutableStateOf("") }
    val maxLen = maxValue.toString().length

    Text(
        text = buffer.ifEmpty { "—" },
        style = MaterialTheme.typography.headlineMedium,
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        textAlign = TextAlign.Center,
    )
    listOf(listOf("1", "2", "3"), listOf("4", "5", "6"), listOf("7", "8", "9")).forEach { row ->
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            row.forEach { digit ->
                KeyButton(label = digit, modifier = Modifier.weight(1f)) {
                    if (buffer.length < maxLen) buffer += digit
                }
            }
        }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
        KeyButton(
            content = { Icon(Icons.AutoMirrored.Filled.Backspace, contentDescription = stringResource(R.string.manual_roll_backspace)) },
            modifier = Modifier.weight(1f),
        ) { if (buffer.isNotEmpty()) buffer = buffer.dropLast(1) }
        KeyButton(label = "0", modifier = Modifier.weight(1f)) {
            if (buffer.length < maxLen) buffer += "0"
        }
        KeyButton(
            content = { Icon(Icons.Default.Check, contentDescription = stringResource(R.string.manual_roll_enter)) },
            modifier = Modifier.weight(1f),
            enabled = buffer.toIntOrNull()?.let { it in minValue..maxValue } == true,
        ) {
            buffer.toIntOrNull()?.let { onValue(it.coerceIn(minValue, maxValue)) }
            buffer = ""
        }
    }
}

@Composable
private fun KeyButton(
    label: String? = null,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: (@Composable () -> Unit)? = null,
    onClick: () -> Unit,
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        shape = RoundedCornerShape(12.dp),
        contentPadding = PaddingValues(0.dp),
        modifier = modifier.height(56.dp),
    ) {
        if (content != null) content() else Text(label.orEmpty(), style = MaterialTheme.typography.titleLarge)
    }
}
