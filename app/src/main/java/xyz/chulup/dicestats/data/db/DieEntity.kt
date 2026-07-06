package xyz.chulup.dicestats.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * A registered physical die — or a pool of identical dice (DESIGN.md data model,
 * "Die Pools").
 *
 * @param count upper bound of interchangeable physical dice this entry stands for;
 *   `> 1` makes it a pool. An upper bound, not an exact size — a roll may contain
 *   fewer (Risk attacker rolls 1–3 red dice), never more.
 * @param colorSignature learned colour fingerprint for identity matching
 *   ([xyz.chulup.dicestats.recognition.DieColorSignature], serialized), or null
 *   until the die has been confirmed in at least one roll.
 * @param colorSamples number of confirmed crops the fingerprint is averaged over.
 */
@Entity(tableName = "dice")
data class DieEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val faces: Int = 6,
    val kind: String = "PIPPED",
    val count: Int = 1,
    val referencePhotoPath: String? = null,
    val createdAt: Long,
    val colorSignature: String? = null,
    val colorSamples: Int = 0,
) {
    /** A pool stands for several interchangeable dice; results only share its id. */
    val isPool: Boolean get() = count > 1
}
