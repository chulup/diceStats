package xyz.chulup.dicestats.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

/** A registered physical die (DESIGN.md data model). */
@Entity(tableName = "dice")
data class DieEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val faces: Int = 6,
    val kind: String = "PIPPED",
    val referencePhotoPath: String? = null,
    val createdAt: Long,
)
