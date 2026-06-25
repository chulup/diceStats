package xyz.chulup.dicestats.feature.detection

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
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
    viewModel: DetectionViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    val ready = uiState as? DetectionUiState.Ready
    LaunchedEffect(ready?.saved) {
        if (ready?.saved == true) onSaved()
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
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
    ) {
        Box(
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
            DiceOverlay(dice = state.dice, modifier = Modifier.fillMaxSize())
        }

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

@Composable
private fun DiceOverlay(dice: List<DieAssignment>, modifier: Modifier = Modifier) {
    val density = LocalDensity.current
    Canvas(modifier = modifier) {
        val strokeWidth = 3.dp.toPx()
        val minLabel = with(density) { 16.dp.toPx() }
        dice.forEachIndexed { index, die ->
            val box = die.boundingBox
            val left = box.left * size.width
            val top = box.top * size.height
            val w = box.width * size.width
            val h = box.height * size.height
            val color = Color(0xFF00E676)

            drawRect(
                color = color,
                topLeft = Offset(left, top),
                size = Size(w, h),
                style = Stroke(width = strokeWidth),
            )

            val label = "${index + 1}: ${die.value}"
            val textSize = (h * 0.35f).coerceAtLeast(minLabel)
            val paint = AndroidPaint().apply {
                this.color = AndroidColor.BLACK
                this.textSize = textSize
                isFakeBoldText = true
                isAntiAlias = true
            }
            val pad = textSize * 0.2f
            val chipW = paint.measureText(label) + pad * 2
            val chipH = textSize + pad * 2
            drawRect(color = color, topLeft = Offset(left, top), size = Size(chipW, chipH))
            drawContext.canvas.nativeCanvas.drawText(label, left + pad, top + textSize + pad * 0.6f, paint)
        }
    }
}
