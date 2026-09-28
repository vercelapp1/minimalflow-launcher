package com.minimalflow.launcher.di

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import javax.inject.Qualifier
import javax.inject.Singleton

/**
 * Application-wide bindings that are not Room or Hilt singletons.
 *
 * The dispatchers are bound as interfaces rather than taken directly so unit
 * tests can replace them with a deterministic one without needing a coroutine
 * mocking library.
 */
@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    fun provideApplicationScope(@Dispatcher(DispatcherKind.IO) dispatcher: CoroutineDispatcher): CoroutineScope =
        CoroutineScope(SupervisorJob() + dispatcher)

    @Provides
    @Singleton
    @Dispatcher(DispatcherKind.IO)
    fun provideIoDispatcher(): CoroutineDispatcher = Dispatchers.IO

    @Provides
    @Singleton
    @Dispatcher(DispatcherKind.Default)
    fun provideDefaultDispatcher(): CoroutineDispatcher = Dispatchers.Default

    @Provides
    @Singleton
    @Dispatcher(DispatcherKind.Main)
    fun provideMainDispatcher(): CoroutineDispatcher = Dispatchers.Main.immediate
}

/** Which dispatcher a binding is for. */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class Dispatcher(val kind: DispatcherKind)

enum class DispatcherKind { IO, Default, Main }
