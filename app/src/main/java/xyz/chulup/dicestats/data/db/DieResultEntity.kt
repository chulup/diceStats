package xyz.chulup.dicestats.data.db

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * One die within a roll. Deleting a roll cascades to its results; deleting a die
 * nulls the back-reference rather than dropping the recorded result.
 *
 * @param boundingBox normalized "left,top,right,bottom".
 */
@Entity(
    tableName = "die_results",
    foreignKeys = [
        ForeignKey(
            entity = RollEntity::class,
            parentColumns = ["id"],
            childColumns = ["rollId"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = DieEntity::class,
            parentColumns = ["id"],
            childColumns = ["dieId"],
            onDelete = ForeignKey.SET_NULL,
        ),
    ],
    indices = [Index("rollId"), Index("dieId")],
)
data class DieResultEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val rollId: Long,
    val dieId: Long?,
    val value: Int,
    val confidence: Float,
    val boundingBox: String,
    val wasCorrected: Boolean,
)
