package com.goattidi.mediasync.di

import com.goattidi.mediasync.data.drive.DriveAuthProvider
import com.goattidi.mediasync.data.drive.GoogleDriveAuthProvider
import com.goattidi.mediasync.sync.DriveFolderResolver
import com.goattidi.mediasync.sync.LocalFiles
import com.goattidi.mediasync.sync.MediaStoreLocalFiles
import com.goattidi.mediasync.sync.SettingsDriveFolderResolver
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class BindsModule {

    @Binds
    @Singleton
    abstract fun bindDriveAuthProvider(impl: GoogleDriveAuthProvider): DriveAuthProvider

    @Binds
    abstract fun bindLocalFiles(impl: MediaStoreLocalFiles): LocalFiles

    @Binds
    abstract fun bindDriveFolderResolver(impl: SettingsDriveFolderResolver): DriveFolderResolver
}
