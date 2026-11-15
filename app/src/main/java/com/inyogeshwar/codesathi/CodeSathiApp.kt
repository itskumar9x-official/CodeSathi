package com.inyogeshwar.codesathi

import android.app.Application
import androidx.room.Room
import com.inyogeshwar.codesathi.agent.TermuxBridge
import com.inyogeshwar.codesathi.core.CrashReporter
import com.inyogeshwar.codesathi.data.AppDatabase
import com.inyogeshwar.codesathi.data.KeyStore
import com.inyogeshwar.codesathi.data.PreferencesStore
import com.inyogeshwar.codesathi.data.MIGRATION_1_2
import com.inyogeshwar.codesathi.data.MIGRATION_2_3
import com.inyogeshwar.codesathi.data.MIGRATION_3_4
import com.inyogeshwar.codesathi.data.MIGRATION_4_5
import com.inyogeshwar.codesathi.data.MIGRATION_5_6
import com.inyogeshwar.codesathi.data.ProviderRepository
import com.inyogeshwar.codesathi.ui.AppViewModel

/** Application object: builds the singletons the screens share. */
class CodeSathiApp : Application() {

    override fun onCreate() {
        super.onCreate()
        // Local crash reporter (Phase 7, Step B3): an uncaught
        // exception is written to getFilesDir()/last_crash.txt and
        // shown on the next launch from Home. No upload, no analytics.
        CrashReporter.install(this)
        // Termux bridge (Phase 7, Step B4): the agent's `run` tool and
        // termux: MCP servers run commands in the user's Termux app.
        // Nothing runs in Termux unless the user turns it on.
        TermuxBridge.init(this)
    }

    /**
     * The one AppViewModel, set by MainActivity once it is created.
     * RemoteService reads it so a remote turn reuses the same ViewModel
     * (same agent loop, same pending diff approval) instead of a second
     * copy with its own LLM client and voice speaker.
     */
    var appViewModel: AppViewModel? = null

    val database: AppDatabase by lazy {
        Room.databaseBuilder(this, AppDatabase::class.java, "codesathi.db")
            .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6)
            .build()
    }

    val preferencesStore: PreferencesStore by lazy { PreferencesStore(this) }

    /** API keys live only here (EncryptedSharedPreferences). */
    val keyStore: KeyStore by lazy { KeyStore(this) }

    val providerRepository: ProviderRepository by lazy { ProviderRepository(this, database) }
}
