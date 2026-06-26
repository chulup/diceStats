package xyz.chulup.dicestats.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * A registered physical die (DESIGN.md data model).
 *
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
    val referencePhotoPath: String? = null,
    val createdAt: Long,
    val colorSignature: String? = null,
    val colorSamples: Int = 0,
)
