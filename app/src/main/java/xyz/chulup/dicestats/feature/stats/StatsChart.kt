package xyz.chulup.dicestats.feature.stats

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * A column-of-bars chart shared by the stats screens (per-die, roll totals, per-game).
 * One bar per [labels]/[counts] entry, each scaled to [maxCount].
 *
 * @param showZeroCount when true the count text is drawn even for empty bars (the per-die
 *   distribution always labels every face); otherwise only non-zero bars show their count.
 */
@Composable
internal fun StatsBarChart(
    labels: List<String>,
    counts: List<Int>,
    maxCount: Int,
    modifier: Modifier = Modifier,
    chartHeight: Dp = 180.dp,
    barSpacing: Dp = 4.dp,
    showZeroCount: Boolean = false,
    labelStyle: TextStyle = MaterialTheme.typography.labelSmall,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(chartHeight),
        horizontalArrangement = Arrangement.spacedBy(barSpacing),
        verticalAlignment = Alignment.Bottom,
    ) {
        counts.forEachIndexed { index, count ->
            StatsBar(
                label = labels.getOrElse(index) { "" },
                count = count,
                fraction = count.toFloat() / maxCount,
                showZeroCount = showZeroCount,
                labelStyle = labelStyle,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun StatsBar(
    label: String,
    count: Int,
    fraction: Float,
    showZeroCount: Boolean,
    labelStyle: TextStyle,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxHeight(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Bottom,
    ) {
        if (count > 0 || showZeroCount) {
            Text(count.toString(), style = MaterialTheme.typography.labelSmall)
            Spacer(Modifier.height(2.dp))
        }
        // Reserve the column height so bars share a common baseline; the bar fills
        // the fraction of the available track above the label.
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
        Text(label, style = labelStyle, fontWeight = FontWeight.Bold)
    }
}
