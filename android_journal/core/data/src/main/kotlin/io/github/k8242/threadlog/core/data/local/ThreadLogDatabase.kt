// Copyright (c) 2026 k8242
// SPDX-License-Identifier: MIT

package io.github.k8242.threadlog.core.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.deferredTransaction
import androidx.room.immediateTransaction
import androidx.room.migration.Migration
import androidx.room.useReaderConnection
import androidx.room.useWriterConnection
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL
import io.github.k8242.threadlog.core.data.local.dao.AttachmentDao
import io.github.k8242.threadlog.core.data.local.dao.AutoTagRuleDao
import io.github.k8242.threadlog.core.data.local.dao.BookmarkDao
import io.github.k8242.threadlog.core.data.local.dao.EntryDao
import io.github.k8242.threadlog.core.data.local.dao.SyncStateDao
import io.github.k8242.threadlog.core.data.local.dao.TagDao
import io.github.k8242.threadlog.core.data.local.entity.AttachmentEntity
import io.github.k8242.threadlog.core.data.local.entity.AutoTagRuleEntity
import io.github.k8242.threadlog.core.data.local.entity.BookmarkEntity
import io.github.k8242.threadlog.core.data.local.entity.EntryEntity
import io.github.k8242.threadlog.core.data.local.entity.EntryTagEntity
import io.github.k8242.threadlog.core.data.local.entity.SyncEntryHashEntity
import io.github.k8242.threadlog.core.data.local.entity.TagEntity

@Database(
    entities = [
        EntryEntity::class,
        TagEntity::class,
        EntryTagEntity::class,
        BookmarkEntity::class,
        AttachmentEntity::class,
        AutoTagRuleEntity::class,
        SyncEntryHashEntity::class,
    ],
    version = 4,
    exportSchema = true,
)
abstract class ThreadLogDatabase : RoomDatabase() {
    abstract fun entryDao(): EntryDao
    abstract fun tagDao(): TagDao
    abstract fun bookmarkDao(): BookmarkDao
    abstract fun attachmentDao(): AttachmentDao
    abstract fun autoTagRuleDao(): AutoTagRuleDao
    abstract fun syncStateDao(): SyncStateDao

    companion object {
        const val DATABASE_NAME: String = "threadlog.db"
        const val FTS_TABLE_NAME: String = "entries_fts"
        const val CREATE_FTS5_TABLE_SQL: String =
            "CREATE VIRTUAL TABLE IF NOT EXISTS entries_fts USING fts5(" +
                "entry_id UNINDEXED, " +
                "body_plain, " +
                "visible_tags, " +
                "hidden_tags, " +
                "tokenize='unicode61'" +
                ")"

        val MIGRATION_1_2: Migration = object : Migration(1, 2) {
            override fun migrate(connection: SQLiteConnection) {
                connection.execSQL(
                    "ALTER TABLE entries ADD COLUMN task_status TEXT NOT NULL DEFAULT 'NONE'",
                )
                connection.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_entries_task_status ON entries (task_status)",
                )
            }
        }

        val MIGRATION_2_3: Migration = object : Migration(2, 3) {
            override fun migrate(connection: SQLiteConnection) {
                connection.execSQL(
                    "ALTER TABLE entries ADD COLUMN reactions_json TEXT NOT NULL DEFAULT '[]'",
                )
            }
        }

        val MIGRATION_3_4: Migration = object : Migration(3, 4) {
            override fun migrate(connection: SQLiteConnection) {
                connection.execSQL(
                    "ALTER TABLE entries ADD COLUMN task_priority TEXT NOT NULL DEFAULT 'NONE'",
                )
                connection.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_entries_task_priority ON entries (task_priority)",
                )
                connection.execSQL(
                    "ALTER TABLE entries ADD COLUMN archived_at_ms INTEGER DEFAULT NULL",
                )
                connection.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_entries_archived_at_ms ON entries (archived_at_ms)",
                )
                connection.execSQL(
                    "ALTER TABLE entries ADD COLUMN is_highlighted INTEGER NOT NULL DEFAULT 0",
                )
            }
        }
    }
}

/**
 * Ensures the SQLite FTS5 virtual table (`entries_fts`) exists whenever [ThreadLogDatabase]
 * is created or opened via `BundledSQLiteDriver`.
 */
class ThreadLogDatabaseCallback : RoomDatabase.Callback() {
    override fun onCreate(connection: SQLiteConnection) {
        super.onCreate(connection)
        ensureFts5Table(connection)
    }

    override fun onOpen(connection: SQLiteConnection) {
        super.onOpen(connection)
        ensureFts5Table(connection)
    }

    private fun ensureFts5Table(connection: SQLiteConnection) {
        connection.execSQL("PRAGMA foreign_keys = ON")
        connection.execSQL(ThreadLogDatabase.CREATE_FTS5_TABLE_SQL)
    }
}

/**
 * Executes [block] inside an IMMEDIATE write transaction compatible with Room 2.8.5 and
 * `BundledSQLiteDriver`, re-entering transparently if already within a write transaction.
 */
internal suspend fun <R> ThreadLogDatabase.inWriteTransaction(block: suspend () -> R): R =
    useWriterConnection { transactor ->
        if (transactor.inTransaction()) {
            block()
        } else {
            transactor.immediateTransaction {
                block()
            }
        }
    }

/**
 * Executes [block] inside a consistent read transaction compatible with Room 2.8.5 and
 * `BundledSQLiteDriver`.
 */
internal suspend fun <R> ThreadLogDatabase.inReadTransaction(block: suspend () -> R): R =
    useReaderConnection { transactor ->
        if (transactor.inTransaction()) {
            block()
        } else {
            transactor.deferredTransaction {
                block()
            }
        }
    }
