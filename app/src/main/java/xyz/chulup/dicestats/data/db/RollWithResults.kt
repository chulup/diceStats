package xyz.chulup.dicestats.data.db

import androidx.room.Embedded
import androidx.room.Relation

/** A roll together with its per-die results. */
data class RollWithResults(
    @Embedded val roll: RollEntity,
    @Relation(parentColumn = "id", entityColumn = "rollId")
    val results: List<DieResultEntity>,
)
