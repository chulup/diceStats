package xyz.chulup.dicestats.feature.dicelist

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Casino
import androidx.compose.material.icons.filled.Functions
import androidx.compose.material.icons.filled.SportsEsports
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import xyz.chulup.dicestats.R
import xyz.chulup.dicestats.ui.dieDisplayName

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DiceListScreen(
    onBack: () -> Unit,
    onDieSelected: (Long) -> Unit,
    onRollTotals: () -> Unit,
    onGames: () -> Unit,
    viewModel: DiceListViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.stats_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            item {
                CategoryRow(
                    icon = Icons.Default.Functions,
                    title = stringResource(R.string.stats_category_sum),
                    subtitle = stringResource(R.string.stats_category_sum_desc),
                    onClick = onRollTotals,
                )
            }
            item {
                CategoryRow(
                    icon = Icons.Default.SportsEsports,
                    title = stringResource(R.string.stats_category_games),
                    subtitle = stringResource(R.string.stats_category_games_desc),
                    onClick = onGames,
                )
            }

            item { SectionHeader(stringResource(R.string.stats_section_dice)) }

            if (uiState.dice.isEmpty()) {
                item {
                    Text(
                        text = stringResource(R.string.dice_list_empty),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(16.dp),
                    )
                }
            } else {
                items(uiState.dice, key = { it.id }) { die ->
                    ListItem(
                        leadingContent = { Icon(Icons.Default.Casino, contentDescription = null) },
                        headlineContent = { Text(dieDisplayName(die.name, die.dieCount)) },
                        supportingContent = {
                            Text(pluralStringResource(R.plurals.dice_list_roll_count, die.rollCount, die.rollCount))
                        },
                        trailingContent = {
                            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null)
                        },
                        modifier = Modifier.clickable { onDieSelected(die.id) },
                    )
                }
            }
        }
    }
}

/** A top-level stats category; a null [onClick] renders it dimmed and non-navigable. */
@Composable
private fun CategoryRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    onClick: (() -> Unit)?,
) {
    ListItem(
        leadingContent = { Icon(icon, contentDescription = null) },
        headlineContent = { Text(title) },
        supportingContent = { Text(subtitle) },
        trailingContent = {
            if (onClick != null) {
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null)
            }
        },
        modifier = if (onClick != null) {
            Modifier.clickable(onClick = onClick)
        } else {
            Modifier.alpha(0.5f)
        },
    )
}

@Composable
private fun SectionHeader(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, top = 16.dp, end = 16.dp, bottom = 4.dp),
    )
}
