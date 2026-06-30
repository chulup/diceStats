package xyz.chulup.dicestats.feature.stats

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import xyz.chulup.dicestats.R

/**
 * A card summarising one [RollTotalGroup]: the dice-count title, a roll-count·mean line, and
 * the per-total distribution chart. Shared by the roll-totals screen and the per-game stats.
 */
@Composable
internal fun RollTotalsCard(group: RollTotalGroup) {
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
            StatsBarChart(
                labels = group.totals.map { it.toString() },
                counts = group.counts,
                maxCount = group.maxCount,
            )
        }
    }
}
