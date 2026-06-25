package xyz.chulup.dicestats.feature.detection

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import xyz.chulup.dicestats.R
import xyz.chulup.dicestats.recognition.BoundingBox
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DetectionScreen(
    photoPath: String,
    onBack: () -> Unit,
    onRetake: () -> Unit,
    viewModel: DetectionViewModel = viewModel(factory = DetectionViewModel.factory(photoPath)),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.detection_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.back),
                        )
                    }
                },
            )
        },
        bottomBar = {
            Button(
                onClick = {
                    viewModel.discardPhoto()
                    onRetake()
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
            ) {
                Icon(Icons.Default.Refresh, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.detection_retake))
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

                is DetectionUiState.Ready -> DetectionResult(
                    photoPath = photoPath,
                    aspectRatio = state.aspectRatio,
                    boxes = state.boxes,
                )
            }
        }
    }
}

@Composable
private fun DetectionResult(
    photoPath: String,
    aspectRatio: Float,
    boxes: List<BoundingBox>,
) {
    val context = LocalContext.current
    Box(contentAlignment = Alignment.TopStart) {
        // The container matches the photo's aspect ratio, so FillBounds shows the
        // image undistorted and normalized boxes map directly onto the canvas.
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(aspectRatio)
                .clipToBounds(),
        ) {
            AsyncImage(
                model = File(photoPath),
                contentDescription = stringResource(R.string.detection_photo_desc),
                contentScale = ContentScale.FillBounds,
                modifier = Modifier.fillMaxSize(),
            )
            BoxOverlay(boxes = boxes, modifier = Modifier.fillMaxSize())
        }

        Text(
            text = pluralStringResource(
                R.plurals.detection_count,
                boxes.size,
                boxes.size,
            ),
            color = Color.White,
            modifier = Modifier
                .padding(12.dp)
                .background(Color(0xAA000000))
                .padding(horizontal = 8.dp, vertical = 4.dp),
        )
    }
}

@Composable
private fun BoxOverlay(boxes: List<BoundingBox>, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier) {
        val strokeWidth = 3.dp.toPx()
        boxes.forEach { box ->
            val left = box.left * size.width
            val top = box.top * size.height
            drawRect(
                color = Color(0xFF00E676),
                topLeft = Offset(left, top),
                size = Size(box.width * size.width, box.height * size.height),
                style = Stroke(width = strokeWidth),
            )
        }
    }
}
