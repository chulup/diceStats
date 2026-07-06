package xyz.chulup.dicestats.feature.stats

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import xyz.chulup.dicestats.R
import xyz.chulup.dicestats.ui.dieDisplayName

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
                title = {
                    Text(
                        uiState.dieName?.let { dieDisplayName(it, uiState.dieCount) }
                            ?: stringResource(R.string.die_stats_title),
                    )
                },
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
            SummaryRow(stats, isPool = uiState.isPool)
            if (uiState.isPool) {
                // A pool's sample counts diverge: each photo contributes several throws.
                Text(
                    text = stringResource(
                        R.string.die_stats_throws_rolls,
                        stats.total,
                        uiState.rollCount,
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            DistributionChart(stats)
            FairnessCard(stats, poolSize = uiState.dieCount.takeIf { uiState.isPool })
        }
    }
}

@Composable
private fun SummaryRow(stats: DieStatistics, isPool: Boolean) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        SummaryStat(
            label = stringResource(
                if (isPool) R.string.die_stats_throws else R.string.die_stats_total,
            ),
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
            value = String.format("%.2f", stats.expectedMean),
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
            StatsBarChart(
                labels = stats.dieType.faceValues.map { it.toString() },
                counts = stats.counts,
                maxCount = stats.maxCount,
                barSpacing = 8.dp,
                showZeroCount = true,
                labelStyle = MaterialTheme.typography.labelMedium,
            )
        }
    }
}

@Composable
private fun FairnessCard(stats: DieStatistics, poolSize: Int? = null) {
    val (color, headline) = fairnessVerdictStyle(stats.verdict)
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
            if (poolSize != null) {
                Spacer(Modifier.height(4.dp))
                Text(
                    text = stringResource(R.string.die_stats_pooled_caption, poolSize),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
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
