package xyz.chulup.dicestats.feature.stats

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import xyz.chulup.dicestats.R

private val barColor = Color(0xFF3F51B5)
private val fairColor = Color(0xFF00C853)
private val biasedColor = Color(0xFFFF6D00)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DieStatsScreen(
    onBack: () -> Unit,
    viewModel: DieStatsViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val stats = uiState.stats

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(uiState.dieName ?: stringResource(R.string.die_stats_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                    }
                },
            )
        },
    ) { padding ->
        if (stats.total == 0) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentAlignment = Alignment.Center,
            ) {
                Text(stringResource(R.string.die_stats_empty))
            }
            return@Scaffold
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            SummaryRow(stats)
            DistributionChart(stats)
            FairnessCard(stats)
        }
    }
}

@Composable
private fun SummaryRow(stats: DieStatistics) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        SummaryStat(
            label = stringResource(R.string.die_stats_total),
            value = stats.total.toString(),
            modifier = Modifier.weight(1f),
        )
        SummaryStat(
            label = stringResource(R.string.die_stats_mean),
            value = String.format("%.2f", stats.mean),
            modifier = Modifier.weight(1f),
        )
        SummaryStat(
            label = stringResource(R.string.die_stats_expected_mean),
            value = String.format("%.2f", DieStatistics.EXPECTED_MEAN),
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun SummaryStat(label: String, value: String, modifier: Modifier = Modifier) {
    Card(modifier = modifier) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(value, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Text(label, style = MaterialTheme.typography.labelMedium, textAlign = TextAlign.Center)
        }
    }
}

@Composable
private fun DistributionChart(stats: DieStatistics) {
    Card {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                stringResource(R.string.die_stats_distribution),
                style = MaterialTheme.typography.titleMedium,
            )
            Spacer(Modifier.height(12.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(180.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.Bottom,
            ) {
                stats.counts.forEachIndexed { index, count ->
                    FaceBar(
                        face = index + 1,
                        count = count,
                        fraction = count.toFloat() / stats.maxCount,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}

@Composable
private fun FaceBar(face: Int, count: Int, fraction: Float, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxHeight(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Bottom,
    ) {
        Text(count.toString(), style = MaterialTheme.typography.labelSmall)
        Spacer(Modifier.height(2.dp))
        // Reserve the column height so bars share a common baseline; the bar fills
        // the fraction of the available track above the face label.
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            contentAlignment = Alignment.BottomCenter,
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .fillMaxHeight(fraction.coerceAtLeast(if (count > 0) 0.02f else 0f))
                    .clip(RoundedCornerShape(topStart = 4.dp, topEnd = 4.dp))
                    .background(barColor),
            )
        }
        Spacer(Modifier.height(4.dp))
        Text(face.toString(), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun FairnessCard(stats: DieStatistics) {
    val (color, headline) = when (stats.verdict) {
        FairnessVerdict.LOOKS_FAIR -> fairColor to stringResource(R.string.die_stats_fair)
        FairnessVerdict.POSSIBLY_BIASED -> biasedColor to stringResource(R.string.die_stats_biased)
        FairnessVerdict.INSUFFICIENT_DATA ->
            MaterialTheme.colorScheme.onSurfaceVariant to stringResource(R.string.die_stats_insufficient)
    }
    Card {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .width(12.dp)
                        .height(12.dp)
                        .clip(RoundedCornerShape(50))
                        .background(color),
                )
                Spacer(Modifier.width(8.dp))
                Text(headline, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.height(8.dp))
            Text(
                stringResource(R.string.die_stats_chi_square, String.format("%.2f", stats.chiSquare)),
                style = MaterialTheme.typography.bodyMedium,
            )
            stats.pValue?.let { p ->
                Text(
                    stringResource(R.string.die_stats_p_value, String.format("%.3f", p)),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
    }
}
