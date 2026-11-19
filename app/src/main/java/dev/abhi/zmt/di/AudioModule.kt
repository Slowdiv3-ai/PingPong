package dev.abhi.zmt.di

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dev.abhi.zmt.audio.RawAudioPlayer
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AudioModule {
    @Provides
    @Singleton
    fun provideRawAudioPlayer(): RawAudioPlayer {
        return RawAudioPlayer()
    }
}
