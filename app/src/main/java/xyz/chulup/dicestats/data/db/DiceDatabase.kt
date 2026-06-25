package xyz.chulup.dicestats.data.db

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(
    entities = [DieEntity::class, RollEntity::class, DieResultEntity::class],
    version = 1,
    exportSchema = false,
)
abstract class DiceDatabase : RoomDatabase() {
    abstract fun dieDao(): DieDao
    abstract fun rollDao(): RollDao
}
