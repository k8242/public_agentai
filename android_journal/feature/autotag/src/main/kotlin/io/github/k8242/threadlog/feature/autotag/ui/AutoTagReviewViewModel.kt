package io.github.k8242.threadlog.feature.autotag.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.k8242.threadlog.core.model.contract.EntryRepository
import io.github.k8242.threadlog.core.model.contract.LeafDisambiguator
import io.github.k8242.threadlog.core.model.contract.TagRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * ViewModel powering the Auto-Tag Review screen and review drawer.
 *
 * Streams entries that have pending [io.github.k8242.threadlog.core.model.TagState.AUTO_HIDDEN] tags alongside
 * disambiguated tag display labels computed via [LeafDisambiguator], and exposes promote/reject
 * actions for individual suggestions or all suggestions on a given entry.
 */
@HiltViewModel
class AutoTagReviewViewModel @Inject constructor(
    private val entryRepository: EntryRepository,
    private val tagRepository: TagRepository,
    private val leafDisambiguator: LeafDisambiguator,
) : ViewModel() {

    private val statusMessage = MutableStateFlow<String?>(null)

    val uiState: StateFlow<AutoTagReviewUiState> = combine(
        entryRepository.observeEntriesWithPendingAutoTags(),
        tagRepository.observeAllTags(),
        statusMessage,
    ) { entries, allTags, message ->
        val pendingEntries = entries.filter { !it.entry.isDeleted && it.hiddenAutoTags.isNotEmpty() }
        val displayLabels = leafDisambiguator.computeDisplayLabels(allTags)
        AutoTagReviewUiState(
            pendingEntries = pendingEntries,
            displayLabels = displayLabels,
            isLoading = false,
            statusMessage = message,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000L),
        initialValue = AutoTagReviewUiState(isLoading = true),
    )

    fun promoteTag(entryId: String, tagId: String) {
        viewModelScope.launch {
            runAction(successMessage = "Auto-tag promoted") {
                tagRepository.promoteAutoTag(entryId, tagId)
            }
        }
    }

    fun rejectTag(entryId: String, tagId: String) {
        viewModelScope.launch {
            runAction(successMessage = "Auto-tag dismissed") {
                tagRepository.rejectAutoTag(entryId, tagId)
            }
        }
    }

    fun promoteAllForEntry(entryId: String) {
        viewModelScope.launch {
            val hiddenTags = resolveHiddenTagsForEntry(entryId)
            if (hiddenTags.isEmpty()) return@launch
            val label = if (hiddenTags.size == 1) "1 auto-tag promoted" else "${hiddenTags.size} auto-tags promoted"
            runAction(successMessage = label) {
                for (entryTag in hiddenTags) {
                    tagRepository.promoteAutoTag(entryId, entryTag.tagId)
                }
            }
        }
    }

    fun rejectAllForEntry(entryId: String) {
        viewModelScope.launch {
            val hiddenTags = resolveHiddenTagsForEntry(entryId)
            if (hiddenTags.isEmpty()) return@launch
            val label = if (hiddenTags.size == 1) "1 auto-tag dismissed" else "${hiddenTags.size} auto-tags dismissed"
            runAction(successMessage = label) {
                for (entryTag in hiddenTags) {
                    tagRepository.rejectAutoTag(entryId, entryTag.tagId)
                }
            }
        }
    }

    fun clearStatusMessage() {
        statusMessage.value = null
    }

    private suspend fun resolveHiddenTagsForEntry(entryId: String) =
        uiState.value.pendingEntries
            .firstOrNull { it.entry.id == entryId }
            ?.hiddenAutoTags
            ?: entryRepository.getEntry(entryId)
                ?.takeIf { !it.entry.isDeleted }
                ?.hiddenAutoTags
                .orEmpty()

    private suspend fun runAction(
        successMessage: String,
        block: suspend () -> Unit,
    ) {
        try {
            block()
            statusMessage.value = successMessage
        } catch (ce: CancellationException) {
            throw ce
        } catch (_: Exception) {
            statusMessage.value = "Could not update auto-tag"
        }
    }
}
