package xyz.chulup.dicestats.data.db

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [DieEntity::class, RollEntity::class, DieResultEntity::class, GameEntity::class],
    version = 4,
    exportSchema = false,
)
abstract class DiceDatabase : RoomDatabase() {
    abstract fun dieDao(): DieDao
    abstract fun rollDao(): RollDao
    abstract fun gameDao(): GameDao

    companion object {
        /** v2: per-die colour fingerprint columns for identity matching. */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE dice ADD COLUMN colorSignature TEXT")
                db.execSQL("ALTER TABLE dice ADD COLUMN colorSamples INTEGER NOT NULL DEFAULT 0")
            }
        }

        /** v3: Games (timed sessions). The rolls.gameId FK column already exists. */
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS games (" +
                        "id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "name TEXT NOT NULL, " +
                        "startedAt INTEGER NOT NULL, " +
                        "endedAt INTEGER)",
                )
            }
        }

        /** v4: Die pools — dice.count (pool upper bound) + games.usesDicePools. */
        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE dice ADD COLUMN count INTEGER NOT NULL DEFAULT 1")
                db.execSQL("ALTER TABLE games ADD COLUMN usesDicePools INTEGER NOT NULL DEFAULT 0")
            }
        }
    }
}
