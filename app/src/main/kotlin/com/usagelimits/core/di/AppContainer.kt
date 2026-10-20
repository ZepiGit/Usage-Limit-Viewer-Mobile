package com.usagelimits.core.di

import android.content.Context
import com.usagelimits.core.auth.CredentialStore
import com.usagelimits.core.auth.KeystoreCredentialStore
import com.usagelimits.core.database.UsageLimitsDatabase
import com.usagelimits.core.database.RoomTransactionRunner
import com.usagelimits.core.database.UsageRepository
import com.usagelimits.core.network.HttpClient
import com.usagelimits.core.settings.SettingsStore
import com.usagelimits.core.credits.CreditAccountService
import com.usagelimits.core.database.CreditRepository
import com.usagelimits.core.sync.CreditSyncEngine
import com.usagelimits.core.sync.SyncEngine
import com.usagelimits.providers.credits.CreditRegistry
import com.usagelimits.providers.ProviderRegistry

/**
 * Manual dependency graph.
 *
 * A DI framework (Hilt) would be the conventional choice, but this graph is small, entirely
 * singleton-scoped, and has no build-variant branching — so annotation processing would add
 * build time and a failure mode without buying anything. Everything is constructed lazily and
 * wired explicitly, which also makes the dependencies obvious in one screenful and keeps tests
 * free to build their own graph.
 *
 * Revisit this if the graph grows scoped or variant-specific bindings.
 */
open class AppContainer(context: Context) {

    private val appContext = context.applicationContext

    val httpClient: HttpClient by lazy { HttpClient() }

    val credentialStore: CredentialStore by lazy { KeystoreCredentialStore(appContext) }

    private val database: UsageLimitsDatabase by lazy { UsageLimitsDatabase.build(appContext) }

    /** One runner for everything that must serialise against the database's other writers. */
    val transactions by lazy { RoomTransactionRunner(database) }

    val repository: UsageRepository by lazy {
        UsageRepository(
            database.accountDao(),
            database.usageSnapshotDao(),
            transactions = transactions,
        )
    }

    val widgetConfigDao by lazy { database.widgetConfigDao() }

    val notificationDao by lazy { database.notificationDao() }

    open val providerRegistry: ProviderRegistry by lazy { ProviderRegistry(httpClient) }

    val settingsStore: SettingsStore by lazy { SettingsStore(appContext) }

    val syncEngine: SyncEngine by lazy {
        SyncEngine(repository, credentialStore, providerRegistry)
    }

    val creditRepository: CreditRepository by lazy {
        CreditRepository(database.creditDao(), transactions = transactions)
    }

    val creditRegistry: CreditRegistry by lazy { CreditRegistry(httpClient) }

    val creditSyncEngine: CreditSyncEngine by lazy {
        CreditSyncEngine(creditRepository, credentialStore, creditRegistry)
    }

    val creditAccounts: CreditAccountService by lazy {
        CreditAccountService(creditRepository, credentialStore, creditRegistry)
    }
}
