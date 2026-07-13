package com.goattidi.mediasync.data.db

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

class Converters {
    @TypeConverter
    fun fromSyncStatus(value: SyncStatus): String = value.name

    @TypeConverter
    fun toSyncStatus(value: String): SyncStatus = SyncStatus.valueOf(value)

    @TypeConverter
    fun fromMediaType(value: MediaType): String = value.name

    @TypeConverter
    fun toMediaType(value: String): MediaType = MediaType.valueOf(value)
}

@Database(
    entities = [SyncRecord::class, UploadedContent::class],
    version = 4,
    exportSchema = false
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun syncRecordDao(): SyncRecordDao
    abstract fun uploadedContentDao(): UploadedContentDao

    companion object {
        /** v2: adds the upload ledger (content dedup). */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `uploaded_content` (" +
                        "`md5` TEXT NOT NULL, " +
                        "`driveFileId` TEXT NOT NULL, " +
                        "`fileName` TEXT NOT NULL, " +
                        "`sizeBytes` INTEGER NOT NULL, " +
                        "`uploadedAt` INTEGER NOT NULL, " +
                        "PRIMARY KEY(`md5`))"
                )
            }
        }

        /** v3: media duration for the gallery's video overlay. */
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE sync_records ADD COLUMN durationMs INTEGER NOT NULL DEFAULT 0")
            }
        }

        /** v4: live upload progress + rate for the queue screen. */
        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE sync_records ADD COLUMN bytesUploaded INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE sync_records ADD COLUMN uploadRateBps INTEGER NOT NULL DEFAULT 0")
            }
        }
    }
}
