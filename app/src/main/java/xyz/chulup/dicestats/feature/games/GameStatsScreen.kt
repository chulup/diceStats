package xyz.chulup.dicestats.feature.games

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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import xyz.chulup.dicestats.R
import xyz.chulup.dicestats.feature.stats.DieStatistics
import xyz.chulup.dicestats.feature.stats.FairnessVerdict
import xyz.chulup.dicestats.feature.stats.RollTotalGroup
import xyz.chulup.dicestats.feature.stats.statsBarColor
import xyz.chulup.dicestats.feature.stats.statsBiasedColor
import xyz.chulup.dicestats.feature.stats.statsFairColor

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GameStatsScreen(
    onBack: () -> Unit,
    viewModel: GameStatsViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(uiState.gameName ?: stringResource(R.string.game_stats_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                    }
                },
            )
        },
    ) { padding ->
        if (uiState.isEmpty) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentAlignment = Alignment.Center,
            ) {
                Text(stringResource(R.string.game_stats_empty))
            }
            return@Scaffold
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                pluralStringResource(R.plurals.game_roll_count, uiState.rollCount, uiState.rollCount),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            SectionHeader(stringResource(R.string.game_stats_section_totals))
            uiState.totals.forEach { group -> TotalsCard(group) }

            SectionHeader(stringResource(R.string.game_stats_section_dice))
            if (uiState.dice.isEmpty()) {
                Text(
                    stringResource(R.string.game_stats_dice_removed),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                uiState.dice.forEach { performance -> DiePerformanceCard(performance) }
            }
        }
    }
}

@Composable
private fun SectionHeader(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
    )
}

@Composable
private fun TotalsCard(group: RollTotalGroup) {
    Card {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                pluralStringResource(R.plurals.roll_totals_dice_count, group.diceCount, group.diceCount),
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                pluralStringResource(R.plurals.dice_list_roll_count, group.rollCount, group.rollCount) +
                    "  ·  " + stringResource(
                        R.string.roll_totals_mean,
                        String.format("%.1f", group.mean),
                        String.format("%.1f", group.expectedMean),
                    ),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(12.dp))
            BarChart(
                labels = (group.minTotal..group.maxTotal).map { it.toString() },
                counts = group.counts,
                maxCount = group.maxCount,
            )
        }
    }
}

@Composable
private fun DiePerformanceCard(performance: DiePerformance) {
    val stats = performance.stats
    val (color, verdict) = when (stats.verdict) {
        FairnessVerdict.LOOKS_FAIR -> statsFairColor to stringResource(R.string.die_stats_fair)
        FairnessVerdict.POSSIBLY_BIASED -> statsBiasedColor to stringResource(R.string.die_stats_biased)
        FairnessVerdict.INSUFFICIENT_DATA ->
            MaterialTheme.colorScheme.onSurfaceVariant to stringResource(R.string.die_stats_insufficient)
    }
    Card {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    performance.name,
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                )
                Box(
                    modifier = Modifier
                        .width(10.dp)
                        .height(10.dp)
                        .clip(RoundedCornerShape(50))
                        .background(color),
                )
                Spacer(Modifier.width(6.dp))
                Text(verdict, style = MaterialTheme.typography.labelMedium)
            }
            Text(
                pluralStringResource(R.plurals.dice_list_roll_count, stats.total, stats.total) +
                    "  ·  " + stringResource(
                        R.string.roll_totals_mean,
                        String.format("%.1f", stats.mean),
                        String.format("%.1f", DieStatistics.EXPECTED_MEAN),
                    ),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(12.dp))
            BarChart(
                labels = (1..DieStatistics.FACES).map { it.toString() },
                counts = stats.counts,
                maxCount = stats.maxCount,
            )
        }
    }
}

/** A column-of-bars chart: one bar per [labels]/[counts] entry, scaled to [maxCount]. */
@Composable
private fun BarChart(labels: List<String>, counts: List<Int>, maxCount: Int) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(160.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        counts.forEachIndexed { index, count ->
            Bar(
                label = labels.getOrElse(index) { "" },
                count = count,
                fraction = count.toFloat() / maxCount,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun Bar(label: String, count: Int, fraction: Float, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxHeight(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Bottom,
    ) {
        if (count > 0) {
            Text(count.toString(), style = MaterialTheme.typography.labelSmall)
            Spacer(Modifier.height(2.dp))
        }
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
                    .background(statsBarColor),
            )
        }
        Spacer(Modifier.height(4.dp))
        Text(label, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
    }
}
