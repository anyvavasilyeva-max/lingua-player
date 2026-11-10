package io.github.linguaplayer.ui

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.QueueMusic
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.automirrored.outlined.LibraryBooks
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import io.github.linguaplayer.R
import io.github.linguaplayer.ui.home.HomeScreen
import io.github.linguaplayer.ui.importer.ImportScreen
import io.github.linguaplayer.ui.library.EntryEditScreen
import io.github.linguaplayer.ui.library.LibraryScreen
import io.github.linguaplayer.ui.playlists.PlaylistDetailScreen
import io.github.linguaplayer.ui.playlists.PlaylistsScreen
import io.github.linguaplayer.ui.settings.SettingsScreen
import io.github.linguaplayer.ui.settings.TtsSettingsScreen
import io.github.linguaplayer.ui.player.MiniPlayer
import io.github.linguaplayer.ui.player.PlayerScreen
import io.github.linguaplayer.ui.components.appContainer
import androidx.compose.foundation.layout.Column
import io.github.linguaplayer.ui.tags.TagsScreen

object Routes {
    const val HOME = "home"
    const val LIBRARY = "library"
    const val PLAYLISTS = "playlists"
    const val SETTINGS = "settings"
    const val TAGS = "tags"
    const val IMPORT = "import"
    const val PLAYER = "player"
    const val VOICES = "voices"
    const val ENTRY = "entry?id={id}"
    const val PLAYLIST = "playlist/{id}"

    fun entry(id: Long = 0) = "entry?id=$id"
    fun playlist(id: Long) = "playlist/$id"
}

private data class TopLevel(val route: String, val label: Int, val icon: ImageVector)

private val topLevel = listOf(
    TopLevel(Routes.HOME, R.string.nav_home, Icons.Outlined.Home),
    TopLevel(Routes.LIBRARY, R.string.nav_library, Icons.AutoMirrored.Outlined.LibraryBooks),
    TopLevel(Routes.PLAYLISTS, R.string.nav_playlists, Icons.AutoMirrored.Outlined.QueueMusic),
    TopLevel(Routes.SETTINGS, R.string.nav_settings, Icons.Outlined.Settings),
)

@Composable
fun LinguaNavHost(nav: NavHostController = rememberNavController(), openPlayerRequest: Int = 0) {
    val backStack by nav.currentBackStackEntryAsState()
    val currentRoute = backStack?.destination?.route
    val showBar = topLevel.any { it.route == currentRoute }
    val showMini = currentRoute !in setOf(Routes.PLAYER, Routes.ENTRY, Routes.IMPORT, Routes.VOICES)
    val player = appContainer().player
    // Нажатие на медиа-уведомление открывает плеер.
    LaunchedEffect(openPlayerRequest) {
        if (openPlayerRequest > 0) nav.navigate(Routes.PLAYER) { launchSingleTop = true }
    }

    Scaffold(
        bottomBar = {
            Column {
            if (showMini) MiniPlayer(player, onOpen = { nav.navigate(Routes.PLAYER) { launchSingleTop = true } }, aboveNavigationBar = showBar)
            if (showBar) {
                NavigationBar {
                    topLevel.forEach { item ->
                        NavigationBarItem(
                            selected = currentRoute == item.route,
                            onClick = { nav.navigateTopLevel(item.route) },
                            icon = { Icon(item.icon, contentDescription = null) },
                            label = { Text(stringResource(item.label)) },
                        )
                    }
                }
            }
            }
        },
    ) { padding ->
        NavHost(nav, startDestination = Routes.HOME, modifier = Modifier.padding(bottom = padding.calculateBottomPadding())) {
            composable(Routes.HOME) {
                HomeScreen(
                    onOpenLibrary = { nav.navigateTopLevel(Routes.LIBRARY) },
                    onOpenPlaylists = { nav.navigateTopLevel(Routes.PLAYLISTS) },
                    onOpenPlaylist = { nav.navigate(Routes.playlist(it)) },
                    onImport = { nav.navigate(Routes.IMPORT) },
                    onOpenPlayer = { nav.navigate(Routes.PLAYER) { launchSingleTop = true } },
                )
            }
            composable(Routes.PLAYER) {
                PlayerScreen(onBack = { nav.popBackStack() }, onOpenVoices = { nav.navigate(Routes.VOICES) })
            }
            composable(Routes.VOICES) {
                TtsSettingsScreen(onBack = { nav.popBackStack() })
            }
            composable(Routes.IMPORT) {
                ImportScreen(
                    onBack = { nav.popBackStack() },
                    onFinished = { nav.popBackStack(); nav.navigateTopLevel(Routes.LIBRARY) },
                )
            }
            composable(Routes.LIBRARY) {
                LibraryScreen(
                    onOpenEntry = { nav.navigate(Routes.entry(it)) },
                    onAddEntry = { nav.navigate(Routes.entry()) },
                    onManageTags = { nav.navigate(Routes.TAGS) },
                    onImport = { nav.navigate(Routes.IMPORT) },
                    onOpenPlayer = { nav.navigate(Routes.PLAYER) { launchSingleTop = true } },
                )
            }
            composable(Routes.PLAYLISTS) {
                PlaylistsScreen(onOpenPlaylist = { nav.navigate(Routes.playlist(it)) })
            }
            composable(Routes.SETTINGS) {
                SettingsScreen(onManageTags = { nav.navigate(Routes.TAGS) }, onOpenVoices = { nav.navigate(Routes.VOICES) })
            }
            composable(Routes.TAGS) {
                TagsScreen(onBack = { nav.popBackStack() })
            }
            composable(
                Routes.ENTRY,
                arguments = listOf(navArgument("id") { type = NavType.LongType; defaultValue = 0L }),
            ) { entry ->
                EntryEditScreen(
                    entryId = entry.arguments?.getLong("id") ?: 0L,
                    onDone = { nav.popBackStack() },
                )
            }
            composable(
                Routes.PLAYLIST,
                arguments = listOf(navArgument("id") { type = NavType.LongType }),
            ) { entry ->
                PlaylistDetailScreen(
                    playlistId = entry.arguments?.getLong("id") ?: 0L,
                    onBack = { nav.popBackStack() },
                    onOpenEntry = { nav.navigate(Routes.entry(it)) },
                    onOpenPlayer = { nav.navigate(Routes.PLAYER) { launchSingleTop = true } },
                )
            }
        }
    }
}

private fun NavHostController.navigateTopLevel(route: String) {
    navigate(route) {
        popUpTo(graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}
