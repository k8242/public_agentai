package io.github.k8242.threadlog.core.data

import android.content.Context
import android.content.ContextWrapper
import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import io.github.k8242.threadlog.core.data.local.ThreadLogDatabase
import io.github.k8242.threadlog.core.data.local.ThreadLogDatabaseCallback
import io.github.k8242.threadlog.core.data.local.fts.FtsIndexManager
import io.github.k8242.threadlog.core.data.repository.DefaultAutoTagRuleRepository
import io.github.k8242.threadlog.core.data.repository.DefaultBackupDataGateway
import io.github.k8242.threadlog.core.data.repository.DefaultBookmarkRepository
import io.github.k8242.threadlog.core.data.repository.DefaultEntryRepository
import io.github.k8242.threadlog.core.data.repository.DefaultSearchRepository
import io.github.k8242.threadlog.core.data.repository.DefaultTagRepository
import io.github.k8242.threadlog.core.data.repository.EntryHydrator
import io.github.k8242.threadlog.core.parser.DefaultDateWeekCalculator
import io.github.k8242.threadlog.core.parser.DefaultMessageParser
import kotlinx.coroutines.Dispatchers

internal class TestDataGraph(
    val database: ThreadLogDatabase,
    val ftsIndexManager: FtsIndexManager,
    val hydrator: EntryHydrator,
    val entryRepository: DefaultEntryRepository,
    val tagRepository: DefaultTagRepository,
    val bookmarkRepository: DefaultBookmarkRepository,
    val searchRepository: DefaultSearchRepository,
    val autoTagRuleRepository: DefaultAutoTagRuleRepository,
    val backupDataGateway: DefaultBackupDataGateway,
) {
    fun close() {
        database.close()
    }
}

internal object TestDatabaseFactory {
    private val stubContext: Context = object : ContextWrapper(null) {
        override fun getApplicationContext(): Context = this
    }

    fun createGraph(): TestDataGraph {
        val db = Room.inMemoryDatabaseBuilder(
            context = stubContext,
            klass = ThreadLogDatabase::class.java,
        )
            .setDriver(BundledSQLiteDriver())
            .setQueryCoroutineContext(Dispatchers.IO)
            .addCallback(ThreadLogDatabaseCallback())
            .build()

        val messageParser = DefaultMessageParser()
        val dateWeekCalculator = DefaultDateWeekCalculator()

        val ftsIndexManager = FtsIndexManager(
            database = db,
            entryDao = db.entryDao(),
            tagDao = db.tagDao(),
        )
        val hydrator = EntryHydrator(
            entryDao = db.entryDao(),
            tagDao = db.tagDao(),
            bookmarkDao = db.bookmarkDao(),
            attachmentDao = db.attachmentDao(),
        )
        val entryRepository = DefaultEntryRepository(
            database = db,
            entryDao = db.entryDao(),
            tagDao = db.tagDao(),
            bookmarkDao = db.bookmarkDao(),
            attachmentDao = db.attachmentDao(),
            ftsIndexManager = ftsIndexManager,
            hydrator = hydrator,
            messageParser = messageParser,
            dateWeekCalculator = dateWeekCalculator,
        )
        val tagRepository = DefaultTagRepository(
            database = db,
            entryDao = db.entryDao(),
            tagDao = db.tagDao(),
            ftsIndexManager = ftsIndexManager,
            hydrator = hydrator,
        )
        val bookmarkRepository = DefaultBookmarkRepository(
            database = db,
            bookmarkDao = db.bookmarkDao(),
            hydrator = hydrator,
        )
        val searchRepository = DefaultSearchRepository(
            database = db,
            entryDao = db.entryDao(),
            tagDao = db.tagDao(),
            ftsIndexManager = ftsIndexManager,
        )
        val autoTagRuleRepository = DefaultAutoTagRuleRepository(
            autoTagRuleDao = db.autoTagRuleDao(),
        )
        val backupDataGateway = DefaultBackupDataGateway(
            database = db,
            entryDao = db.entryDao(),
            tagDao = db.tagDao(),
            bookmarkDao = db.bookmarkDao(),
            attachmentDao = db.attachmentDao(),
            syncStateDao = db.syncStateDao(),
            ftsIndexManager = ftsIndexManager,
            hydrator = hydrator,
            messageParser = messageParser,
            dateWeekCalculator = dateWeekCalculator,
        )

        return TestDataGraph(
            database = db,
            ftsIndexManager = ftsIndexManager,
            hydrator = hydrator,
            entryRepository = entryRepository,
            tagRepository = tagRepository,
            bookmarkRepository = bookmarkRepository,
            searchRepository = searchRepository,
            autoTagRuleRepository = autoTagRuleRepository,
            backupDataGateway = backupDataGateway,
        )
    }
}
