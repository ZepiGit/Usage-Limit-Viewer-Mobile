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
import com.usagelimits.core.sync.NotificationPublisher
import com.usagelimits.core.sync.OutOfAppPublication
import com.usagelimits.core.sync.Publication
import com.usagelimits.providers.credits.CreditRegistry
import com.usagelimits.providers.ProviderRegistry
import com.usagelimits.statusicon.StatusIconChanges
import com.usagelimits.statusicon.StatusIconPublisher
import kotlinx.coroutines.flow.first
import com.usagelimits.ui.text.ResourceLoopbackPageTexts

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

    open val credentialStore: CredentialStore by lazy { KeystoreCredentialStore(appContext) }

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

    // The browser page after a redirect sign-in is built far from any Context, so its words are
    // resolved here, from the application's resources, and handed down.
    open val providerRegistry: ProviderRegistry by lazy {
        ProviderRegistry(httpClient, ResourceLoopbackPageTexts(appContext.resources))
    }

    val settingsStore: SettingsStore by lazy { SettingsStore(appContext) }

    val syncEngine: SyncEngine by lazy {
        SyncEngine(repository, credentialStore, providerRegistry)
    }

    val creditRepository: CreditRepository by lazy {
        CreditRepository(database.creditDao(), transactions = transactions)
    }

    open val creditRegistry: CreditRegistry by lazy { CreditRegistry(httpClient) }

    val creditSyncEngine: CreditSyncEngine by lazy {
        CreditSyncEngine(creditRepository, credentialStore, creditRegistry)
    }

    val creditAccounts: CreditAccountService by lazy {
        CreditAccountService(creditRepository, credentialStore, creditRegistry)
    }

    val notificationPublisher by lazy {
        NotificationPublisher(appContext, settingsStore, notificationDao, transactions = transactions)
    }

    val statusIconPublisher by lazy {
        StatusIconPublisher(appContext, settingsStore, repository::accountUsageOnce)
    }

    /** Every switch and pin change of the status icons goes through here, in turn. */
    val statusIconChanges by lazy {
        StatusIconChanges(
            readPinned = { settingsStore.settings.first().pinnedAccountIds },
            writePinned = settingsStore::setPinnedAccounts,
            writeEnabled = settingsStore::setStatusIconsEnabled,
            usageAccountIds = { repository.accounts().map { it.localId } },
            // The property, not a captured value, so a container that replaces it is used.
            publish = { publication.presentationChanged() },
        )
    }

    open val publication: Publication by lazy {
        OutOfAppPublication(appContext, repository, creditRepository, settingsStore, notificationPublisher, statusIconPublisher)
    }
}
