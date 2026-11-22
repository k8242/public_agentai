// Copyright (c) 2026 k8242
// SPDX-License-Identifier: MIT

package io.github.k8242.threadlog.app.ui.drawer

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.k8242.threadlog.core.model.AppColorScheme
import io.github.k8242.threadlog.core.model.DrawerOverviewSummary
import io.github.k8242.threadlog.core.model.DrawerTagSummary
import io.github.k8242.threadlog.core.model.TagRootCategory
import io.github.k8242.threadlog.core.model.ThemeMode
import io.github.k8242.threadlog.core.model.contract.EntryRepository
import io.github.k8242.threadlog.core.model.contract.LeafDisambiguator
import io.github.k8242.threadlog.core.model.contract.SettingsRepository
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class DrawerCategoryTab(val label: String) {
    ALL("All"),
    TOPICS("Topics"),
    PEOPLE("People"),
    PROJECTS("Projects"),
    TAGS("Tags"),
}

data class DrawerUiState(
    val overview: DrawerOverviewSummary = DrawerOverviewSummary(),
    val allTagSummaries: List<DrawerTagSummary> = emptyList(),
    val tagDisplayLabels: Map<String, String> = emptyMap(),
    val searchQuery: String = "",
    val selectedCategory: DrawerCategoryTab = DrawerCategoryTab.ALL,
    val pendingAutoTagCount: Int = 0,
    val showHiddenAutoTagsInChat: Boolean = false,
    val colorScheme: AppColorScheme = AppColorScheme.TEAL,
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
) {
    val filteredTagSummaries: List<DrawerTagSummary>
        get() {
            val query = searchQuery.trim().lowercase()
            return allTagSummaries.filter { summary ->
                val matchesCategory = when (selectedCategory) {
                    DrawerCategoryTab.ALL -> true
                    DrawerCategoryTab.TOPICS -> summary.tag.rootCategory == TagRootCategory.TOPICS
                    DrawerCategoryTab.PEOPLE -> summary.tag.rootCategory == TagRootCategory.PEOPLE
                    DrawerCategoryTab.PROJECTS -> summary.tag.rootCategory == TagRootCategory.PROJECTS
                    DrawerCategoryTab.TAGS -> {
                        summary.tag.rootCategory == TagRootCategory.OTHER ||
                            summary.tag.rootCategory == TagRootCategory.AUTO
                    }
                }
                if (!matchesCategory) return@filter false
                if (query.isEmpty()) return@filter true

                val label = tagDisplayLabels[summary.tag.id] ?: summary.tag.leafName
                label.lowercase().contains(query) ||
                    summary.tag.fullPath.lowercase().contains(query) ||
                    summary.tag.aliases.any { it.lowercase().contains(query) }
            }
        }
}

@HiltViewModel
class DrawerViewModel @Inject constructor(
    private val entryRepository: EntryRepository,
    private val settingsRepository: SettingsRepository,
    private val leafDisambiguator: LeafDisambiguator,
) : ViewModel() {

    private val _uiState = MutableStateFlow(DrawerUiState())
    val uiState: StateFlow<DrawerUiState> = _uiState.asStateFlow()

    init {
        observeOverview()
        observeTagSummaries()
        observePendingAutoTags()
        observeSettings()
    }

    private fun observeOverview() {
        viewModelScope.launch {
            entryRepository.observeDrawerOverview().collect { overview ->
                _uiState.update { it.copy(overview = overview) }
            }
        }
    }

    private fun observeTagSummaries() {
        viewModelScope.launch {
            entryRepository.observeDrawerTagSummaries().collect { summaries ->
                val labels = leafDisambiguator.computeDisplayLabels(summaries.map { it.tag })
                _uiState.update {
                    it.copy(
                        allTagSummaries = summaries,
                        tagDisplayLabels = labels,
                    )
                }
            }
        }
    }

    private fun observePendingAutoTags() {
        viewModelScope.launch {
            entryRepository.observeEntriesWithPendingAutoTags().collect { pending ->
                _uiState.update { it.copy(pendingAutoTagCount = pending.size) }
            }
        }
    }

    private fun observeSettings() {
        viewModelScope.launch {
            settingsRepository.settings.collect { settings ->
                _uiState.update {
                    it.copy(
                        showHiddenAutoTagsInChat = settings.showHiddenAutoTagsInChat,
                        colorScheme = settings.colorScheme,
                        themeMode = settings.themeMode,
                    )
                }
            }
        }
    }

    fun onSearchQueryChanged(query: String) {
        _uiState.update { it.copy(searchQuery = query) }
    }

    fun onSelectCategory(tab: DrawerCategoryTab) {
        _uiState.update { it.copy(selectedCategory = tab) }
    }

    fun toggleShowHiddenAutoTags() {
        val next = !_uiState.value.showHiddenAutoTagsInChat
        _uiState.update { it.copy(showHiddenAutoTagsInChat = next) }
        viewModelScope.launch {
            settingsRepository.setShowHiddenAutoTagsInChat(next)
        }
    }

    fun setColorScheme(colorScheme: AppColorScheme) {
        viewModelScope.launch {
            settingsRepository.setColorScheme(colorScheme)
        }
    }

    fun cycleThemeMode() {
        val next = when (_uiState.value.themeMode) {
            ThemeMode.SYSTEM -> ThemeMode.LIGHT
            ThemeMode.LIGHT -> ThemeMode.DARK
            ThemeMode.DARK -> ThemeMode.SYSTEM
        }
        viewModelScope.launch {
            settingsRepository.setThemeMode(next)
        }
    }
}
