package xyz.chulup.dicestats.data.db

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [DieEntity::class, RollEntity::class, DieResultEntity::class],
    version = 2,
    exportSchema = false,
)
abstract class DiceDatabase : RoomDatabase() {
    abstract fun dieDao(): DieDao
    abstract fun rollDao(): RollDao

    companion object {
        /** v2: per-die colour fingerprint columns for identity matching. */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE dice ADD COLUMN colorSignature TEXT")
                db.execSQL("ALTER TABLE dice ADD COLUMN colorSamples INTEGER NOT NULL DEFAULT 0")
            }
        }
    }
}
