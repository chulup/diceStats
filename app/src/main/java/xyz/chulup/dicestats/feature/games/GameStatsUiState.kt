package xyz.chulup.dicestats.feature.games

import xyz.chulup.dicestats.data.DieType
import xyz.chulup.dicestats.data.db.DieEntity
import xyz.chulup.dicestats.data.db.RollWithResults
import xyz.chulup.dicestats.feature.stats.DieStatistics
import xyz.chulup.dicestats.feature.stats.RollTotalGroup
import xyz.chulup.dicestats.feature.stats.RollTotalStatistics
import java.util.TreeMap

/** One die's performance within a single game: its name and stats over that game's rolls. */
data class DiePerformance(
    val dieId: Long,
    val name: String,
    val stats: DieStatistics,
)

/** Stats for every die in a game at once: the aggregate roll totals plus a per-die breakdown. */
data class GameStatsUiState(
    val gameName: String? = null,
    val rollCount: Int = 0,
    val totals: List<RollTotalGroup> = emptyList(),
    val dice: List<DiePerformance> = emptyList(),
) {
    val isEmpty: Boolean get() = rollCount == 0
}

/**
 * Builds the per-game stats view — the aggregate roll-total distribution plus a per-die
 * fairness breakdown — all scoped to one game's [rolls]. Pure so it stays JVM-testable.
 *
 * @param dice all registered dice, used to resolve names. A die with no recorded result
 *   in this game is omitted; a result whose die was deleted (null [DieEntity] id / dieId)
 *   is left out of the per-die breakdown but still counts toward the roll totals.
 */
fun buildGameStatsUiState(
    gameName: String?,
    rolls: List<RollWithResults>,
    dice: List<DieEntity>,
): GameStatsUiState {
    val totals = RollTotalStatistics.from(rolls.map { rwr -> rwr.results.map { it.value } })

    val diceById = dice.associateBy { it.id }
    // Group every result's face value by its die; the TreeMap keeps dice ordered by id.
    val valuesByDie = TreeMap<Long, MutableList<Int>>()
    for (roll in rolls) {
        for (result in roll.results) {
            val dieId = result.dieId ?: continue
            valuesByDie.getOrPut(dieId) { mutableListOf() }.add(result.value)
        }
    }
    val perDie = valuesByDie.entries
        .mapNotNull { (dieId, values) ->
            val die = diceById[dieId] ?: return@mapNotNull null
            DiePerformance(dieId, die.name, DieStatistics.from(values, DieType.fromFaces(die.faces)))
        }

    return GameStatsUiState(
        gameName = gameName,
        rollCount = rolls.size,
        totals = totals,
        dice = perDie,
    )
}
