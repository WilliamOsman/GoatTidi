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
    version = 2,
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
    }
}
