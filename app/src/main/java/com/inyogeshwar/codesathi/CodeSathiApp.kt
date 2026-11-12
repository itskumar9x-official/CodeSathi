package com.inyogeshwar.codesathi

import android.app.Application
import androidx.room.Room
import com.inyogeshwar.codesathi.data.AppDatabase
import com.inyogeshwar.codesathi.data.KeyStore
import com.inyogeshwar.codesathi.data.PreferencesStore
import com.inyogeshwar.codesathi.data.MIGRATION_1_2
import com.inyogeshwar.codesathi.data.ProviderRepository

/** Application object: builds the singletons the screens share. */
class CodeSathiApp : Application() {

    val database: AppDatabase by lazy {
        Room.databaseBuilder(this, AppDatabase::class.java, "codesathi.db")
            .addMigrations(MIGRATION_1_2)
            .build()
    }

    val preferencesStore: PreferencesStore by lazy { PreferencesStore(this) }

    /** API keys live only here (EncryptedSharedPreferences). */
    val keyStore: KeyStore by lazy { KeyStore(this) }

    val providerRepository: ProviderRepository by lazy { ProviderRepository(this, database) }
}
