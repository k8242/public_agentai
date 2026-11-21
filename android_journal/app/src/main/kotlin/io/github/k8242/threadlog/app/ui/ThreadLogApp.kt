package io.github.k8242.threadlog.app.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Chat
import androidx.compose.material.icons.outlined.Backup
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.ViewAgenda
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import io.github.k8242.threadlog.app.ui.settings.SettingsScreen
import io.github.k8242.threadlog.core.model.navigation.ThreadLogRoute
import io.github.k8242.threadlog.feature.autotag.ui.AutoTagReviewScreen
import io.github.k8242.threadlog.feature.backup.ui.BackupScreen
import io.github.k8242.threadlog.feature.chat.ui.ChatScreen
import io.github.k8242.threadlog.feature.search.context.ContextWindowScreen
import io.github.k8242.threadlog.feature.search.ui.SearchScreen
import io.github.k8242.threadlog.feature.views.calendar.CalendarScreen
import io.github.k8242.threadlog.feature.views.weekly.CompactWeeklyScreen

private data class TopLevelDestination(
    val label: String,
    val icon: ImageVector,
    val route: ThreadLogRoute,
    val matches: (androidx.navigation.NavDestination?) -> Boolean,
)

@Composable
fun ThreadLogApp(modifier: Modifier = Modifier) {
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = backStackEntry?.destination

    val topLevelDestinations = listOf(
        TopLevelDestination(
            label = "Chat",
            icon = Icons.AutoMirrored.Outlined.Chat,
            route = ThreadLogRoute.Chat(),
            matches = { it?.hasRoute<ThreadLogRoute.Chat>() == true },
        ),
        TopLevelDestination(
            label = "Weekly",
            icon = Icons.Outlined.ViewAgenda,
            route = ThreadLogRoute.CompactWeekly,
            matches = { it?.hasRoute<ThreadLogRoute.CompactWeekly>() == true },
        ),
        TopLevelDestination(
            label = "Calendar",
            icon = Icons.Outlined.CalendarMonth,
            route = ThreadLogRoute.Calendar,
            matches = { it?.hasRoute<ThreadLogRoute.Calendar>() == true },
        ),
        TopLevelDestination(
            label = "Search",
            icon = Icons.Outlined.Search,
            route = ThreadLogRoute.Search(),
            matches = { it?.hasRoute<ThreadLogRoute.Search>() == true },
        ),
        TopLevelDestination(
            label = "Backup",
            icon = Icons.Outlined.Backup,
            route = ThreadLogRoute.Backup,
            matches = { it?.hasRoute<ThreadLogRoute.Backup>() == true },
        ),
    )

    val showBottomBar = topLevelDestinations.any { it.matches(currentDestination) }

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
        navController.navigate(
            ThreadLogRoute.Chat(
                jumpToEntryId = entryId,
                jumpToLocalDate = localDate,
            ),
        ) {
            popUpTo(navController.graph.findStartDestination().id) {
                inclusive = false
            }
            launchSingleTop = true
        }
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        bottomBar = {
            if (showBottomBar) {
                NavigationBar {
                    topLevelDestinations.forEach { dest ->
                        NavigationBarItem(
                            selected = dest.matches(currentDestination),
                            onClick = { navigateToTab(dest.route) },
                            icon = {
                                Icon(
                                    imageVector = dest.icon,
                                    contentDescription = dest.label,
                                )
                            },
                            label = { Text(dest.label) },
                        )
                    }
                }
            }
        },
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = ThreadLogRoute.Chat(),
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            composable<ThreadLogRoute.Chat> { entry ->
                val args = entry.toRoute<ThreadLogRoute.Chat>()
                ChatScreen(
                    jumpToEntryId = args.jumpToEntryId,
                    jumpToLocalDate = args.jumpToLocalDate,
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
                BackupScreen()
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
