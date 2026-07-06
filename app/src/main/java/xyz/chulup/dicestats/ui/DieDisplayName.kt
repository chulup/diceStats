package xyz.chulup.dicestats.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import xyz.chulup.dicestats.R
import xyz.chulup.dicestats.data.db.DieEntity

/** The die's display name — pools carry their multiplicity, e.g. "Red ×3". */
@Composable
fun DieEntity.displayName(): String =
    if (isPool) stringResource(R.string.die_name_with_count, name, count) else name

/** [displayName] for screens that carry (name, count) instead of the entity. */
@Composable
fun dieDisplayName(name: String, count: Int): String =
    if (count > 1) stringResource(R.string.die_name_with_count, name, count) else name
