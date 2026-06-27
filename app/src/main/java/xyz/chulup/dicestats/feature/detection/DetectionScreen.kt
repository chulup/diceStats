package xyz.chulup.dicestats.feature.detection

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
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
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import xyz.chulup.dicestats.R
import xyz.chulup.dicestats.data.db.DieEntity
import java.io.File
import android.graphics.Color as AndroidColor
import android.graphics.Paint as AndroidPaint

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DetectionScreen(
    photoPath: String,
    onBack: () -> Unit,
    onRetake: () -> Unit,
    onSaved: () -> Unit,
    onReported: () -> Unit,
    viewModel: DetectionViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val reported by viewModel.reported.collectAsStateWithLifecycle()

    val ready = uiState as? DetectionUiState.Ready
    LaunchedEffect(ready?.saved) {
        if (ready?.saved == true) onSaved()
    }
    LaunchedEffect(reported) {
        if (reported) onReported()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.confirm_title)) },
                navigationIcon = {
                    IconButton(onClick = {
                        viewModel.discardPhoto()
                        onBack()
                    }) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.back),
                        )
                    }
                },
            )
        },
        bottomBar = {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                OutlinedButton(
                    onClick = {
                        viewModel.discardPhoto()
                        onRetake()
                    },
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(Icons.Default.Refresh, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.detection_retake))
                }
                OutlinedButton(
                    onClick = viewModel::reportUnrecognized,
                    enabled = ready?.saving != true,
                    modifier = Modifier.weight(1f),
                ) {
                    Text(stringResource(R.string.report_unrecognized))
                }
                Button(
                    onClick = viewModel::save,
                    enabled = ready?.canSave == true && !ready.saving,
                    modifier = Modifier.weight(1f),
                ) {
                    Text(stringResource(R.string.confirm_save))
                }
            }
        },
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentAlignment = Alignment.Center,
        ) {
            when (val state = uiState) {
                is DetectionUiState.Loading -> CircularProgressIndicator()

                is DetectionUiState.Error -> Text(
                    text = stringResource(R.string.detection_error, state.message),
                    modifier = Modifier.padding(16.dp),
                )

                is DetectionUiState.Ready -> ConfirmContent(
                    photoPath = photoPath,
                    state = state,
                    onValueChange = viewModel::setValue,
                    onAssign = viewModel::assignDie,
                    onRegister = viewModel::registerAndAssign,
                    onRemove = viewModel::removeDie,
                    onIdentify = viewModel::identifyAsActive,
                    onSelectActive = viewModel::selectActiveDie,
                    onRegisterActive = viewModel::registerAndSetActive,
                )
            }
        }
    }
}

@Composable
private fun ConfirmContent(
    photoPath: String,
    state: DetectionUiState.Ready,
    onValueChange: (Int, Int) -> Unit,
    onAssign: (Int, Long) -> Unit,
    onRegister: (Int, String) -> Unit,
    onRemove: (Int) -> Unit,
    onIdentify: (Int) -> Unit,
    onSelectActive: (Long) -> Unit,
    onRegisterActive: (String) -> Unit,
) {
    val nameFor: (Long?) -> String? = { id ->
        id?.let { dieId -> state.registeredDice.firstOrNull { it.id == dieId }?.name }
    }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
    ) {
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(state.aspectRatio)
                .clipToBounds(),
        ) {
            AsyncImage(
                model = File(photoPath),
                contentDescription = stringResource(R.string.detection_photo_desc),
                contentScale = ContentScale.FillBounds,
                modifier = Modifier.fillMaxSize(),
            )
            DiceOverlay(dice = state.dice, nameFor = nameFor, modifier = Modifier.fillMaxSize())

            val areaWidth = maxWidth
            val areaHeight = maxHeight

            // Tapping a die identifies it as the active palette die.
            state.dice.forEachIndexed { index, die ->
                val box = die.boundingBox
                Box(
                    modifier = Modifier
                        .offset(x = areaWidth * box.left, y = areaHeight * box.top)
                        .size(width = areaWidth * box.width, height = areaHeight * box.height)
                        .clickable { onIdentify(index) },
                )
            }

            // A tappable remove badge just to the right of each box (kept clear of
            // the die so it doesn't hide it).
            state.dice.forEachIndexed { index, die ->
                val box = die.boundingBox
                val rawX = areaWidth * box.right + 4.dp
                val badgeX = if (rawX > areaWidth - BADGE_SIZE) areaWidth - BADGE_SIZE else rawX
                val rawY = areaHeight * ((box.top + box.bottom) / 2f) - BADGE_SIZE / 2
                val badgeY = if (rawY < 0.dp) 0.dp else rawY
                RemoveBadge(
                    onClick = { onRemove(index) },
                    modifier = Modifier.offset(x = badgeX, y = badgeY),
                )
            }
        }

        DicePalette(
            recentDice = state.recentDice,
            activeDieId = state.activeDieId,
            onSelect = onSelectActive,
            onRegister = onRegisterActive,
            modifier = Modifier.fillMaxWidth(),
        )

        Text(
            text = pluralStringResource(R.plurals.detection_count, state.dice.size, state.dice.size),
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        )

        state.dice.forEachIndexed { index, die ->
            DieRow(
                index = index,
                die = die,
                registeredDice = state.registeredDice,
                onValueChange = { onValueChange(index, it) },
                onAssign = { onAssign(index, it) },
                onRegister = { onRegister(index, it) },
                onRemove = { onRemove(index) },
            )
        }

        if (state.saving) {
            Box(modifier = Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DieRow(
    index: Int,
    die: DieAssignment,
    registeredDice: List<DieEntity>,
    onValueChange: (Int) -> Unit,
    onAssign: (Long) -> Unit,
    onRegister: (String) -> Unit,
    onRemove: () -> Unit,
) {
    var showRegisterDialog by remember { mutableStateOf(false) }
    var dropdownExpanded by remember { mutableStateOf(false) }

    Card(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = stringResource(R.string.confirm_die_number, index + 1),
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = { onValueChange(die.value - 1) }) {
                    Icon(Icons.Default.Remove, contentDescription = stringResource(R.string.confirm_decrement))
                }
                Text(text = die.value.toString())
                IconButton(onClick = { onValueChange(die.value + 1) }) {
                    Icon(Icons.Default.Add, contentDescription = stringResource(R.string.confirm_increment))
                }
                IconButton(onClick = onRemove) {
                    Icon(Icons.Default.Close, contentDescription = stringResource(R.string.confirm_remove_die))
                }
            }

            val selectedName = registeredDice.firstOrNull { it.id == die.dieId }?.name
                ?: stringResource(R.string.confirm_choose_die)
            ExposedDropdownMenuBox(
                expanded = dropdownExpanded,
                onExpandedChange = { dropdownExpanded = !dropdownExpanded },
            ) {
                OutlinedTextField(
                    value = selectedName,
                    onValueChange = {},
                    readOnly = true,
                    label = { Text(stringResource(R.string.confirm_die_label)) },
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(dropdownExpanded) },
                    modifier = Modifier
                        .menuAnchor(MenuAnchorType.PrimaryNotEditable)
                        .fillMaxWidth(),
                )
                ExposedDropdownMenu(
                    expanded = dropdownExpanded,
                    onDismissRequest = { dropdownExpanded = false },
                ) {
                    registeredDice.forEach { registered ->
                        DropdownMenuItem(
                            text = { Text(registered.name) },
                            onClick = {
                                onAssign(registered.id)
                                dropdownExpanded = false
                            },
                        )
                    }
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.confirm_register_new)) },
                        onClick = {
                            dropdownExpanded = false
                            showRegisterDialog = true
                        },
                    )
                }
            }
        }
    }

    if (showRegisterDialog) {
        RegisterDieDialog(
            onDismiss = { showRegisterDialog = false },
            onConfirm = { name ->
                showRegisterDialog = false
                onRegister(name)
            },
        )
    }
}

@Composable
private fun RegisterDieDialog(onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var name by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.confirm_register_title)) },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                singleLine = true,
                label = { Text(stringResource(R.string.confirm_die_name)) },
            )
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(name) }, enabled = name.isNotBlank()) {
                Text(stringResource(R.string.confirm_register_action))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.dialog_cancel)) }
        },
    )
}

/**
 * Recent registered dice as a horizontal carousel below the photo. The highlighted
 * die is the one the next tapped box will be identified as; the strip scrolls to
 * keep it in view as the selection advances. Tapping a chip makes it active, and
 * "New die" registers one and makes it active.
 */
@Composable
private fun DicePalette(
    recentDice: List<DieEntity>,
    activeDieId: Long?,
    onSelect: (Long) -> Unit,
    onRegister: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var showRegisterDialog by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()

    // Slide the carousel so the active die stays visible as the highlight advances.
    val activeIndex = recentDice.indexOfFirst { it.id == activeDieId }
    LaunchedEffect(activeIndex) {
        if (activeIndex >= 0) listState.animateScrollToItem(activeIndex)
    }

    Column(modifier = modifier.padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            text = stringResource(R.string.palette_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp),
        )
        LazyRow(
            state = listState,
            contentPadding = PaddingValues(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(recentDice, key = { it.id }) { die ->
                val active = die.id == activeDieId
                val container =
                    if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant
                val content =
                    if (active) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(16.dp))
                        .background(container)
                        .clickable { onSelect(die.id) }
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                ) {
                    Text(text = die.name, color = content)
                }
            }
            item {
                OutlinedButton(onClick = { showRegisterDialog = true }) {
                    Icon(Icons.Default.Add, contentDescription = null)
                    Spacer(Modifier.width(4.dp))
                    Text(stringResource(R.string.palette_new_die))
                }
            }
        }
    }

    if (showRegisterDialog) {
        RegisterDieDialog(
            onDismiss = { showRegisterDialog = false },
            onConfirm = { name ->
                showRegisterDialog = false
                onRegister(name)
            },
        )
    }
}

private val BADGE_SIZE = 28.dp

/** Outline/label colour for a die whose pip value was recognized. */
private val recognizedDieColor = Color(0xFF00E676)

/** Outline/label colour for a proposed region with no readable value. */
private val unknownDieColor = Color(0xFFFFC107)

@Composable
private fun RemoveBadge(onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .size(BADGE_SIZE)
            .clip(CircleShape)
            .background(Color(0xFFD32F2F))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = Icons.Default.Close,
            contentDescription = stringResource(R.string.confirm_remove_die),
            tint = Color.White,
            modifier = Modifier.size(18.dp),
        )
    }
}

@Composable
private fun DiceOverlay(
    dice: List<DieAssignment>,
    nameFor: (Long?) -> String?,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    Canvas(modifier = modifier) {
        val strokeWidth = 3.dp.toPx()
        val labelSize = with(density) { 15.sp.toPx() }
        dice.forEachIndexed { index, die ->
            val box = die.boundingBox
            val left = box.left * size.width
            val top = box.top * size.height
            val w = box.width * size.width
            val h = box.height * size.height
            // Green once a value is established (read or user-set); amber flags an
            // unresolved region to review.
            val color = if (die.hasValue) recognizedDieColor else unknownDieColor

            drawRect(
                color = color,
                topLeft = Offset(left, top),
                size = Size(w, h),
                style = Stroke(width = strokeWidth),
            )

            // Chip above the box shows the assigned die name (or the index until
            // identified), plus the value. Never overlaps the die itself.
            val who = nameFor(die.dieId) ?: "${index + 1}"
            val valueText = if (die.hasValue) die.value.toString() else "?"
            val label = "$who: $valueText"
            val paint = AndroidPaint().apply {
                this.color = AndroidColor.BLACK
                this.textSize = labelSize
                isFakeBoldText = true
                isAntiAlias = true
            }
            val pad = labelSize * 0.25f
            val chipW = paint.measureText(label) + pad * 2
            val chipH = labelSize + pad * 2
            val chipTop = (top - chipH).coerceAtLeast(0f)
            drawRect(color = color, topLeft = Offset(left, chipTop), size = Size(chipW, chipH))
            drawContext.canvas.nativeCanvas.drawText(label, left + pad, chipTop + labelSize + pad * 0.6f, paint)
        }
    }
}
