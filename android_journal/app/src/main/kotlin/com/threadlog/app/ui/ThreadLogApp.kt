package com.threadlog.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Chat
import androidx.compose.material.icons.outlined.Backup
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.ViewAgenda
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import com.threadlog.app.ui.settings.SettingsScreen
import com.threadlog.core.model.navigation.ThreadLogRoute
import com.threadlog.feature.autotag.ui.AutoTagReviewScreen
import com.threadlog.feature.chat.ui.ChatScreen
import com.threadlog.feature.views.calendar.CalendarScreen
import com.threadlog.feature.views.weekly.CompactWeeklyScreen

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

            composable<ThreadLogRoute.Search> {
                PlaceholderFeatureScreen(
                    title = "Search",
                    description = "Two-tier FTS5 + hidden auto-tag hybrid search will be wired in Batch 4.",
                )
            }

            composable<ThreadLogRoute.ContextWindow> { entry ->
                val args = entry.toRoute<ThreadLogRoute.ContextWindow>()
                PlaceholderFeatureScreen(
                    title = "±12h Context Window",
                    description = "Context window for entry ${args.anchorEntryId} will be wired in Batch 4.",
                )
            }

            composable<ThreadLogRoute.Backup> {
                PlaceholderFeatureScreen(
                    title = "Backup & Restore",
                    description = "Google Sheets + Drive sync and offline CSV/XLSX/ZIP backup will be wired in Batch 4.",
                )
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

@Composable
private fun PlaceholderFeatureScreen(
    title: String,
    description: String,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(text = title, style = MaterialTheme.typography.headlineSmall)
            Text(
                text = description,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
    }
}
