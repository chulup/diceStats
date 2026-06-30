package xyz.chulup.dicestats.feature.stats

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import xyz.chulup.dicestats.R

/** The chart colour and headline label that present a [FairnessVerdict] to the user. */
@Composable
internal fun fairnessVerdictStyle(verdict: FairnessVerdict): Pair<Color, String> = when (verdict) {
    FairnessVerdict.LOOKS_FAIR -> statsFairColor to stringResource(R.string.die_stats_fair)
    FairnessVerdict.POSSIBLY_BIASED -> statsBiasedColor to stringResource(R.string.die_stats_biased)
    FairnessVerdict.INSUFFICIENT_DATA ->
        MaterialTheme.colorScheme.onSurfaceVariant to stringResource(R.string.die_stats_insufficient)
}
