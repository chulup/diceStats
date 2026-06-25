package xyz.chulup.dicestats.di

import android.content.Context
import androidx.room.Room
import xyz.chulup.dicestats.data.db.DiceDatabase
import xyz.chulup.dicestats.data.db.DieDao
import xyz.chulup.dicestats.data.db.RollDao
import xyz.chulup.dicestats.data.photo.PhotoStorage
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DataModule {

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): DiceDatabase =
        Room.databaseBuilder(context, DiceDatabase::class.java, "dicestats.db").build()

    @Provides
    fun provideDieDao(db: DiceDatabase): DieDao = db.dieDao()

    @Provides
    fun provideRollDao(db: DiceDatabase): RollDao = db.rollDao()

    @Provides
    @Singleton
    fun providePhotoStorage(@ApplicationContext context: Context): PhotoStorage =
        PhotoStorage(context)
}
