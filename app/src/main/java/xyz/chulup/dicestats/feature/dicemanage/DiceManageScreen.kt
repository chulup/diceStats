package xyz.chulup.dicestats.feature.dicemanage

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.Rect
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Casino
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import xyz.chulup.dicestats.R
import xyz.chulup.dicestats.feature.stats.fairnessVerdictStyle
import xyz.chulup.dicestats.ui.dieDisplayName

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DiceManageScreen(
    onBack: () -> Unit,
    onDieSelected: (Long) -> Unit,
    viewModel: DiceManageViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    var pendingDelete by remember { mutableStateOf<ManagedDie?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.dice_manage_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                    }
                },
            )
        },
    ) { padding ->
        if (uiState.dice.isEmpty()) {
            Text(
                text = stringResource(R.string.dice_list_empty),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(padding).padding(16.dp),
            )
        } else {
            LazyColumn(Modifier.fillMaxSize().padding(padding)) {
                items(uiState.dice, key = { it.id }) { die ->
                    val (color, verdictLabel) = fairnessVerdictStyle(die.verdict)
                    ListItem(
                        leadingContent = { DiePictureThumb(die.picture) },
                        headlineContent = { Text(dieDisplayName(die.name, die.dieCount)) },
                        supportingContent = {
                            Column {
                                Text(
                                    stringResource(R.string.die_type_label, die.type.faces) + " · " +
                                        pluralStringResource(R.plurals.dice_list_roll_count, die.rollCount, die.rollCount),
                                )
                                Text(verdictLabel, color = color)
                            }
                        },
                        trailingContent = {
                            IconButton(onClick = { pendingDelete = die }) {
                                Icon(Icons.Default.Delete, contentDescription = stringResource(R.string.dice_manage_delete))
                            }
                        },
                        modifier = Modifier.clickable { onDieSelected(die.id) },
                    )
                }
            }
        }
    }

    pendingDelete?.let { die ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text(stringResource(R.string.dice_manage_delete_title, die.name)) },
            text = { Text(stringResource(R.string.dice_manage_delete_message)) },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.deleteDie(die.id)
                    pendingDelete = null
                }) { Text(stringResource(R.string.delete_confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text(stringResource(R.string.dialog_cancel)) }
            },
        )
    }
}

@Composable
private fun DiePictureThumb(picture: DiePicture?) {
    val shape = RoundedCornerShape(8.dp)
    val bitmap by produceState<Bitmap?>(null, picture) { value = picture?.let { decodeRegion(it) } }
    Box(
        modifier = Modifier.size(64.dp).clip(shape).background(MaterialTheme.colorScheme.secondaryContainer),
        contentAlignment = Alignment.Center,
    ) {
        val bmp = bitmap
        if (bmp != null) {
            Image(
                bitmap = bmp.asImageBitmap(),
                contentDescription = stringResource(R.string.dice_manage_picture_desc),
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Icon(Icons.Default.Casino, contentDescription = null, tint = MaterialTheme.colorScheme.onSecondaryContainer)
        }
    }
}

/** Decodes just the [DiePicture.box] region of the photo, downsampled to thumbnail size. */
private suspend fun decodeRegion(picture: DiePicture): Bitmap? = withContext(Dispatchers.IO) {
    runCatching {
        val decoder = BitmapRegionDecoder.newInstance(picture.photoPath, false) ?: return@runCatching null
        try {
            val w = decoder.width
            val h = decoder.height
            val rect = Rect(
                (picture.box.left * w).toInt().coerceIn(0, w - 1),
                (picture.box.top * h).toInt().coerceIn(0, h - 1),
                (picture.box.right * w).toInt().coerceIn(1, w),
                (picture.box.bottom * h).toInt().coerceIn(1, h),
            )
            if (rect.width() <= 0 || rect.height() <= 0) return@runCatching null
            var sample = 1
            while (rect.width() / (sample * 2) >= THUMB_PX && rect.height() / (sample * 2) >= THUMB_PX) sample *= 2
            decoder.decodeRegion(rect, BitmapFactory.Options().apply { inSampleSize = sample })
        } finally {
            decoder.recycle()
        }
    }.getOrNull()
}

private const val THUMB_PX = 192
