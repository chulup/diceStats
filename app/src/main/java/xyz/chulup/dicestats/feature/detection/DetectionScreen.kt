package xyz.chulup.dicestats.feature.detection

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
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
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import xyz.chulup.dicestats.R
import xyz.chulup.dicestats.data.db.DieEntity
import xyz.chulup.dicestats.recognition.BoundingBox
import xyz.chulup.dicestats.ui.displayName
import java.io.File
import kotlin.math.roundToInt
import android.graphics.Color as AndroidColor
import android.graphics.Paint as AndroidPaint

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DetectionScreen(
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
                    .padding(horizontal = 8.dp, vertical = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                val buttonPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp)
                OutlinedButton(
                    onClick = {
                        viewModel.discardPhoto()
                        onRetake()
                    },
                    contentPadding = buttonPadding,
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(Icons.Default.Refresh, contentDescription = null)
                    Spacer(Modifier.width(4.dp))
                    Text(stringResource(R.string.detection_retake))
                }
                OutlinedButton(
                    onClick = viewModel::reportUnrecognized,
                    enabled = ready?.saving != true,
                    contentPadding = buttonPadding,
                    modifier = Modifier.weight(1f),
                ) {
                    Text(stringResource(R.string.report_unrecognized))
                }
                Button(
                    onClick = viewModel::save,
                    enabled = ready?.canSave == true && !ready.saving,
                    contentPadding = buttonPadding,
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
                    state = state,
                    onValueChange = viewModel::setValue,
                    onAssign = viewModel::assignDie,
                    onRegister = viewModel::registerAndAssign,
                    onRemove = viewModel::removeDie,
                    onIdentify = viewModel::identifyAsActive,
                    onSelectActive = viewModel::selectActiveDie,
                    onRegisterActive = viewModel::registerAndSetActive,
                    onDetectRegion = viewModel::detectInRegion,
                    onAddDieAt = viewModel::addDieAt,
                    onMoveDie = viewModel::moveDie,
                )
            }
        }
    }
}

@Composable
private fun ConfirmContent(
    state: DetectionUiState.Ready,
    onValueChange: (Int, Int) -> Unit,
    onAssign: (Int, Long) -> Unit,
    onRegister: (Int, String, Int) -> Unit,
    onRemove: (Int) -> Unit,
    onIdentify: (Int) -> Unit,
    onSelectActive: (Long) -> Unit,
    onRegisterActive: (String, Int) -> Unit,
    onDetectRegion: (BoundingBox) -> Unit,
    onAddDieAt: (Float, Float) -> Unit,
    onMoveDie: (Int, Float, Float) -> Unit,
) {
    val nameFor: (Long?) -> String? = { id ->
        id?.let { dieId -> state.registeredDice.firstOrNull { it.id == dieId }?.name }
    }
    // Pinch-zoom / pan into the photo to frame a region for re-detection.
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    // A re-detected crop is a new photo — start it un-zoomed.
    LaunchedEffect(state.photoPath) {
        scale = 1f
        offset = Offset.Zero
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
                .clipToBounds()
                .pointerInput(state.photoPath) {
                    detectTransformGestures { _, pan, zoom, _ ->
                        scale = (scale * zoom).coerceIn(1f, MAX_SCALE)
                        val maxX = (scale - 1f) * size.width / 2f
                        val maxY = (scale - 1f) * size.height / 2f
                        offset = if (scale <= 1f) {
                            Offset.Zero
                        } else {
                            Offset(
                                (offset.x + pan.x).coerceIn(-maxX, maxX),
                                (offset.y + pan.y).coerceIn(-maxY, maxY),
                            )
                        }
                    }
                }
                .pointerInput(Unit) {
                    detectTapGestures(
                        // Long-press a spot the detector missed to re-detect there and add
                        // the die. Invert the graphicsLayer transform (scale about centre,
                        // then pan) to recover the normalized image point under the finger.
                        onLongPress = { pos ->
                            val vw = size.width.toFloat()
                            val vh = size.height.toFloat()
                            val ix = (0.5f + (pos.x - offset.x - vw / 2f) / (scale * vw)).coerceIn(0f, 1f)
                            val iy = (0.5f + (pos.y - offset.y - vh / 2f) / (scale * vh)).coerceIn(0f, 1f)
                            onAddDieAt(ix, iy)
                        },
                        onDoubleTap = {
                            scale = 1f
                            offset = Offset.Zero
                        },
                    )
                },
        ) {
            val areaWidth = maxWidth
            val areaHeight = maxHeight
            val viewportW = constraints.maxWidth.toFloat()
            val viewportH = constraints.maxHeight.toFloat()
            // Maps a normalized image coord to a screen pixel with the same transform the
            // photo's graphicsLayer uses (scale about centre, then pan) — for the on-photo UI
            // (outlines, labels, remove badges) drawn outside that layer at a constant size.
            val screenX = { nx: Float -> viewportW / 2f + scale * (nx * viewportW - viewportW / 2f) + offset.x }
            val screenY = { ny: Float -> viewportH / 2f + scale * (ny * viewportH - viewportH / 2f) + offset.y }

            // The photo and the invisible per-die tap/drag targets transform together, so
            // hits stay aligned at any zoom without per-box math.
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        scaleX = scale
                        scaleY = scale
                        translationX = offset.x
                        translationY = offset.y
                    },
            ) {
                AsyncImage(
                    model = File(state.photoPath),
                    contentDescription = stringResource(R.string.detection_photo_desc),
                    contentScale = ContentScale.FillBounds,
                    modifier = Modifier.fillMaxSize(),
                )

                // Tapping a die identifies it as the active palette die; dragging it
                // repositions the box (deltas are in content px → normalized by viewport).
                state.dice.forEachIndexed { index, die ->
                    val box = die.boundingBox
                    Box(
                        modifier = Modifier
                            .offset(x = areaWidth * box.left, y = areaHeight * box.top)
                            .size(width = areaWidth * box.width, height = areaHeight * box.height)
                            .pointerInput(index) {
                                detectTapGestures { onIdentify(index) }
                            }
                            .pointerInput(index) {
                                detectDragGestures { change, dragAmount ->
                                    change.consume()
                                    onMoveDie(index, dragAmount.x / viewportW, dragAmount.y / viewportH)
                                }
                            },
                    )
                }
            }

            // On-photo UI drawn OUTSIDE the zoom layer so line thickness, text and badges keep
            // a constant screen size; each is placed by running its box through screenX/screenY
            // so it stays pinned to its die at any zoom.
            DiceOverlay(
                dice = state.dice,
                nameFor = nameFor,
                highlightIndex = state.recentlyAddedIndex,
                zoom = scale,
                pan = offset,
                modifier = Modifier.fillMaxSize(),
            )

            // A tappable remove badge just to the right of each box (kept clear of the die).
            val badgePx = with(LocalDensity.current) { BADGE_SIZE.toPx() }
            val gapPx = with(LocalDensity.current) { 4.dp.toPx() }
            state.dice.forEachIndexed { index, die ->
                val box = die.boundingBox
                val x = (screenX(box.right) + gapPx).coerceIn(0f, viewportW - badgePx)
                val yCenter = (screenY(box.top) + screenY(box.bottom)) / 2f
                val y = (yCenter - badgePx / 2f).coerceIn(0f, viewportH - badgePx)
                RemoveBadge(
                    onClick = { onRemove(index) },
                    modifier = Modifier.offset { IntOffset(x.roundToInt(), y.roundToInt()) },
                )
            }

            // Re-detect on the framed region (overlaid, not transformed).
            if (state.detecting) {
                CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
            } else if (scale > 1f) {
                Button(
                    onClick = { onDetectRegion(visibleRegion(scale, offset, viewportW, viewportH)) },
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(12.dp),
                ) {
                    Text(stringResource(R.string.detect_region))
                }
            }
        }

        DicePalette(
            recentDice = state.recentDice,
            activeDieId = state.activeDieId,
            fullDieIds = state.atCapacityDieIds,
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
                onRegister = { name, count -> onRegister(index, name, count) },
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
    onRegister: (String, Int) -> Unit,
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
                            text = { Text(registered.displayName()) },
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
            onConfirm = { name, count ->
                showRegisterDialog = false
                onRegister(name, count)
            },
        )
    }
}

@Composable
private fun RegisterDieDialog(onDismiss: () -> Unit, onConfirm: (String, Int) -> Unit) {
    var name by remember { mutableStateOf("") }
    var count by remember { mutableStateOf(1) }
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
                // >1 registers a pool of interchangeable dice (DESIGN.md "Die Pools").
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = stringResource(R.string.confirm_die_count),
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(
                        onClick = { count-- },
                        enabled = count > 1,
                    ) {
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
            TextButton(onClick = { onConfirm(name, count) }, enabled = name.isNotBlank()) {
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
 * "New die" registers one and makes it active. Dice already assigned to as many
 * boxes as they have physical dice ([fullDieIds]) are grayed out and unselectable.
 */
@Composable
private fun DicePalette(
    recentDice: List<DieEntity>,
    activeDieId: Long?,
    fullDieIds: Set<Long>,
    onSelect: (Long) -> Unit,
    onRegister: (String, Int) -> Unit,
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
                val full = die.id in fullDieIds
                val container =
                    if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant
                val content =
                    if (active) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
                Box(
                    modifier = Modifier
                        .alpha(if (full) 0.4f else 1f)
                        .clip(RoundedCornerShape(16.dp))
                        .background(container)
                        .clickable(enabled = !full) { onSelect(die.id) }
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                ) {
                    Text(text = die.displayName(), color = content)
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
            onConfirm = { name, count ->
                showRegisterDialog = false
                onRegister(name, count)
            },
        )
    }
}

private const val MAX_SCALE = 6f

/**
 * The portion of the photo currently visible in the viewport, as a normalized
 * [BoundingBox] (0..1). With the image centered and scaled by [scale] then translated by
 * [offset] px, the visible width/height fraction is `1/scale` centered on the panned
 * point. This is the region re-detection runs on.
 */
private fun visibleRegion(scale: Float, offset: Offset, viewportW: Float, viewportH: Float): BoundingBox {
    val visible = 1f / scale
    val centerX = 0.5f - offset.x / (viewportW * scale)
    val centerY = 0.5f - offset.y / (viewportH * scale)
    return BoundingBox(
        left = (centerX - visible / 2f).coerceIn(0f, 1f),
        top = (centerY - visible / 2f).coerceIn(0f, 1f),
        right = (centerX + visible / 2f).coerceIn(0f, 1f),
        bottom = (centerY + visible / 2f).coerceIn(0f, 1f),
    )
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
    highlightIndex: Int? = null,
    zoom: Float = 1f,
    pan: Offset = Offset.Zero,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    Canvas(modifier = modifier) {
        // Drawn outside the photo's zoom layer: stroke and text are plain screen pixels
        // (no counter-scaling). Each normalized coordinate is mapped to the screen with the
        // same transform the graphicsLayer applies to the photo — scale about the centre,
        // then pan — so the outline stays pinned to its die at any zoom.
        val strokeWidth = 3.dp.toPx()
        val labelSize = with(density) { 15.sp.toPx() }
        val cx = size.width / 2f
        val cy = size.height / 2f
        fun screenX(nx: Float) = cx + zoom * (nx * size.width - cx) + pan.x
        fun screenY(ny: Float) = cy + zoom * (ny * size.height - cy) + pan.y

        dice.forEachIndexed { index, die ->
            val box = die.boundingBox
            val left = screenX(box.left)
            val top = screenY(box.top)
            val w = screenX(box.right) - left
            val h = screenY(box.bottom) - top
            // Green once a value is established (read or user-set); amber flags an
            // unresolved region to review.
            val color = if (die.hasValue) recognizedDieColor else unknownDieColor

            drawRect(
                color = color,
                topLeft = Offset(left, top),
                size = Size(w, h),
                // Thicken the box just added via long-press so the user sees the tap landed.
                style = Stroke(width = if (index == highlightIndex) strokeWidth * 2f else strokeWidth),
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
