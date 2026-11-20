package com.threadlog.core.data.repository

import com.threadlog.core.data.local.ThreadLogDatabase
import com.threadlog.core.data.local.dao.BookmarkDao
import com.threadlog.core.data.local.inWriteTransaction
import com.threadlog.core.data.mapper.toDomain
import com.threadlog.core.model.Bookmark
import com.threadlog.core.model.contract.BookmarkRepository
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DefaultBookmarkRepository @Inject constructor(
    private val database: ThreadLogDatabase,
    private val bookmarkDao: BookmarkDao,
    private val hydrator: EntryHydrator,
) : BookmarkRepository {

    override suspend fun updateBookmarkTitle(bookmarkId: String, title: String) {
        database.inWriteTransaction {
            val existing = bookmarkDao.getById(bookmarkId) ?: return@inWriteTransaction
            bookmarkDao.updateTitle(bookmarkId, title)
            hydrator.recomputeAndPersistContentHash(existing.entryId)
        }
    }

    override suspend fun setCollapsed(bookmarkId: String, collapsed: Boolean) {
        bookmarkDao.setCollapsed(bookmarkId, collapsed)
    }

    override suspend fun getBookmarksMissingTitle(entryId: String): List<Bookmark> =
        bookmarkDao.getMissingTitleForEntry(entryId).map { it.toDomain() }
}
