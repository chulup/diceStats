package xyz.chulup.dicestats.data.db

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [DieEntity::class, RollEntity::class, DieResultEntity::class, GameEntity::class],
    version = 5,
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

        /**
         * v5: manually-entered rolls have no photo, so `rolls.photoPath` becomes nullable.
         * SQLite can't drop a NOT NULL constraint in place, so recreate the table. Ids are
         * preserved, so the `die_results.rollId` foreign key stays valid (Room runs
         * migrations with foreign keys disabled and re-checks them afterwards).
         */
        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE rolls_new (" +
                        "id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "photoPath TEXT, " +
                        "capturedAt INTEGER NOT NULL, " +
                        "notes TEXT, " +
                        "gameId INTEGER, " +
                        "dieGroupId INTEGER, " +
                        "playerId INTEGER)",
                )
                db.execSQL(
                    "INSERT INTO rolls_new (id, photoPath, capturedAt, notes, gameId, dieGroupId, playerId) " +
                        "SELECT id, photoPath, capturedAt, notes, gameId, dieGroupId, playerId FROM rolls",
                )
                db.execSQL("DROP TABLE rolls")
                db.execSQL("ALTER TABLE rolls_new RENAME TO rolls")
            }
        }
    }
}
