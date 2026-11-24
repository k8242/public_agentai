// Copyright (c) 2026 k8242
// SPDX-License-Identifier: MIT

package io.github.k8242.threadlog.app.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Chat
import androidx.compose.material.icons.outlined.Backup
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.ViewAgenda
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import io.github.k8242.threadlog.app.ui.drawer.DrawerNavDestination
import io.github.k8242.threadlog.app.ui.drawer.DrawerViewModel
import io.github.k8242.threadlog.app.ui.drawer.ThreadLogDrawerSheet
import io.github.k8242.threadlog.app.ui.settings.SettingsScreen
import io.github.k8242.threadlog.core.model.ChatFeedFilter
import io.github.k8242.threadlog.core.model.navigation.ThreadLogRoute
import io.github.k8242.threadlog.feature.autotag.ui.AutoTagReviewScreen
import io.github.k8242.threadlog.feature.backup.ui.BackupScreen
import io.github.k8242.threadlog.feature.chat.ui.ChatScreen
import io.github.k8242.threadlog.feature.search.context.ContextWindowScreen
import io.github.k8242.threadlog.feature.search.ui.SearchScreen
import io.github.k8242.threadlog.feature.views.calendar.CalendarScreen
import io.github.k8242.threadlog.feature.views.weekly.CompactWeeklyScreen
import kotlinx.coroutines.launch

@Composable
fun ThreadLogApp(
    modifier: Modifier = Modifier,
    drawerViewModel: DrawerViewModel = hiltViewModel(),
) {
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = backStackEntry?.destination
    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    val drawerUiState by drawerViewModel.uiState.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()

    var activeChatFilter by remember { mutableStateOf<ChatFeedFilter>(ChatFeedFilter.All) }
    var jumpNonce by remember { mutableLongStateOf(0L) }

    val isOnChatScreen = currentDestination == null || currentDestination.hasRoute<ThreadLogRoute.Chat>()

    val drawerDestinations = listOf(
        DrawerNavDestination(
            label = "Chat",
            icon = Icons.AutoMirrored.Outlined.Chat,
            route = ThreadLogRoute.Chat(),
            selected = currentDestination?.hasRoute<ThreadLogRoute.Chat>() == true,
        ),
        DrawerNavDestination(
            label = "Weekly",
            icon = Icons.Outlined.ViewAgenda,
            route = ThreadLogRoute.CompactWeekly,
            selected = currentDestination?.hasRoute<ThreadLogRoute.CompactWeekly>() == true,
        ),
        DrawerNavDestination(
            label = "Calendar",
            icon = Icons.Outlined.CalendarMonth,
            route = ThreadLogRoute.Calendar,
            selected = currentDestination?.hasRoute<ThreadLogRoute.Calendar>() == true,
        ),
        DrawerNavDestination(
            label = "Search",
            icon = Icons.Outlined.Search,
            route = ThreadLogRoute.Search(),
            selected = currentDestination?.hasRoute<ThreadLogRoute.Search>() == true,
        ),
        DrawerNavDestination(
            label = "Backup",
            icon = Icons.Outlined.Backup,
            route = ThreadLogRoute.Backup,
            selected = currentDestination?.hasRoute<ThreadLogRoute.Backup>() == true,
        ),
    )

    fun openDrawer() {
        scope.launch { drawerState.open() }
    }

    fun closeDrawer() {
        scope.launch { drawerState.close() }
    }

    fun navigateToTab(route: ThreadLogRoute) {
        navController.navigate(route) {
            popUpTo(navController.graph.findStartDestination().id) {
                saveState = true
            }
            launchSingleTop = true
            restoreState = true
        }
    }

    fun jumpToChat(entryId: String?, localDate: String?) {
        jumpNonce += 1L
        activeChatFilter = ChatFeedFilter.All
        navController.navigate(
            ThreadLogRoute.Chat(
                jumpToEntryId = entryId,
                jumpToLocalDate = localDate,
                jumpNonce = jumpNonce,
            ),
        ) {
            popUpTo(navController.graph.findStartDestination().id) {
                inclusive = false
            }
            launchSingleTop = true
        }
    }

    ModalNavigationDrawer(
        modifier = modifier.fillMaxSize(),
        drawerState = drawerState,
        // Keep swipe-to-close always enabled; on Chat, use the dedicated left-edge strip + menu
        // button to open so horizontal drags on message bubbles stay dedicated to swipe-to-reply.
        gesturesEnabled = drawerState.isOpen || !isOnChatScreen,
        drawerContent = {
            ThreadLogDrawerSheet(
                uiState = drawerUiState,
                destinations = drawerDestinations,
                activeChatFilter = activeChatFilter,
                isChatActive = isOnChatScreen,
                onNavigateToDestination = { route ->
                    closeDrawer()
                    navigateToTab(route)
                },
                onSelectChatFilter = { filter ->
                    activeChatFilter = filter
                    closeDrawer()
                    if (!isOnChatScreen) {
                        navigateToTab(ThreadLogRoute.Chat())
                    }
                },
                onSearchQueryChanged = drawerViewModel::onSearchQueryChanged,
                onSelectCategory = drawerViewModel::onSelectCategory,
                onToggleShowHiddenAutoTags = drawerViewModel::toggleShowHiddenAutoTags,
                onToggleShowHiddenPlaceholderMessages = drawerViewModel::toggleShowHiddenPlaceholderMessages,
                onOpenAutoTagReview = {
                    closeDrawer()
                    navController.navigate(ThreadLogRoute.AutoTagReview)
                },
                onOpenSettings = {
                    closeDrawer()
                    navController.navigate(ThreadLogRoute.Settings)
                },
                onSelectColorScheme = drawerViewModel::setColorScheme,
                onCycleThemeMode = drawerViewModel::cycleThemeMode,
                onDeleteTag = { summary ->
                    val current = activeChatFilter
                    if (current is ChatFeedFilter.TagSubtree) {
                        val prefix = "${summary.tag.fullPath}/"
                        if (
                            current.tagId == summary.tag.id ||
                            current.fullPath.equals(summary.tag.fullPath, ignoreCase = true) ||
                            current.fullPath.startsWith(prefix, ignoreCase = true)
                        ) {
                            activeChatFilter = ChatFeedFilter.All
                        }
                    }
                    drawerViewModel.deleteTag(summary.tag.id)
                },
            )
        },
    ) {
        NavHost(
            navController = navController,
            startDestination = ThreadLogRoute.Chat(),
            modifier = Modifier.fillMaxSize(),
        ) {
            composable<ThreadLogRoute.Chat> { entry ->
                val args = entry.toRoute<ThreadLogRoute.Chat>()
                ChatScreen(
                    activeFilter = activeChatFilter,
                    onFilterChanged = { activeChatFilter = it },
                    onOpenDrawer = ::openDrawer,
                    jumpToEntryId = args.jumpToEntryId,
                    jumpToLocalDate = args.jumpToLocalDate,
                    jumpNonce = args.jumpNonce,
                    onOpenAutoTagReview = { navController.navigate(ThreadLogRoute.AutoTagReview) },
                    onOpenSettings = { navController.navigate(ThreadLogRoute.Settings) },
                    onOpenSearch = { query -> navController.navigate(ThreadLogRoute.Search(query)) },
                )
            }

            composable<ThreadLogRoute.CompactWeekly> {
                CompactWeeklyScreen(
                    onOpenEntryInChat = { entryId, localDate ->
                        jumpToChat(entryId = entryId, localDate = localDate)
                    },
                    onOpenContextWindow = { entryId ->
                        navController.navigate(ThreadLogRoute.ContextWindow(anchorEntryId = entryId))
                    },
                    onOpenDrawer = ::openDrawer,
                )
            }

            composable<ThreadLogRoute.Calendar> {
                CalendarScreen(
                    onOpenEntryInChat = { entryId, localDate ->
                        jumpToChat(entryId = entryId, localDate = localDate)
                    },
                    onOpenContextWindow = { entryId ->
                        navController.navigate(ThreadLogRoute.ContextWindow(anchorEntryId = entryId))
                    },
                    onOpenDrawer = ::openDrawer,
                )
            }

            composable<ThreadLogRoute.Search> { entry ->
                val args = entry.toRoute<ThreadLogRoute.Search>()
                SearchScreen(
                    initialQuery = args.initialQuery,
                    onJumpToEntryInChat = { entryId, localDate ->
                        jumpToChat(entryId = entryId, localDate = localDate)
                    },
                    onOpenContextWindow = { anchorEntryId, highlightQuery ->
                        navController.navigate(
                            ThreadLogRoute.ContextWindow(
                                anchorEntryId = anchorEntryId,
                                highlightQuery = highlightQuery,
                            ),
                        )
                    },
                    onOpenDrawer = ::openDrawer,
                )
            }

            composable<ThreadLogRoute.ContextWindow> { entry ->
                val args = entry.toRoute<ThreadLogRoute.ContextWindow>()
                ContextWindowScreen(
                    anchorEntryId = args.anchorEntryId,
                    highlightQuery = args.highlightQuery,
                    onNavigateBack = { navController.popBackStack() },
                    onOpenInChat = { entryId, localDate ->
                        jumpToChat(entryId = entryId, localDate = localDate)
                    },
                )
            }

            composable<ThreadLogRoute.Backup> {
                BackupScreen(onOpenDrawer = ::openDrawer)
            }

            composable<ThreadLogRoute.AutoTagReview> {
                AutoTagReviewScreen(
                    onNavigateBack = { navController.popBackStack() },
                    onEntryClick = { entryId ->
                        jumpToChat(entryId = entryId, localDate = null)
                    },
                )
            }

            composable<ThreadLogRoute.Settings> {
                SettingsScreen(
                    onNavigateBack = { navController.popBackStack() },
                )
            }
        }
    }
}
