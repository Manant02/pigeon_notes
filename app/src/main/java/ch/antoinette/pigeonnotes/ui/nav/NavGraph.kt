package ch.antoinette.pigeonnotes.ui.nav

import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import ch.antoinette.pigeonnotes.ui.editor.EditorScreen
import ch.antoinette.pigeonnotes.ui.notes.HomeScreen
import ch.antoinette.pigeonnotes.ui.notes.ListScope
import ch.antoinette.pigeonnotes.ui.notes.NoteListScreen
import ch.antoinette.pigeonnotes.ui.settings.AppSettingsScreen
import ch.antoinette.pigeonnotes.ui.settings.SettingsScreen

object Routes {
    const val HOME = "home"
    /** `scope`: [ALL], [ARCHIVE] or a folder path. */
    const val LIST = "list/{scope}?search={search}"
    const val SYNC_SETTINGS = "settings/sync"
    const val APP_SETTINGS = "settings/app"
    const val EDITOR = "editor/{path}"
    fun editor(path: String) = "editor/" + Uri.encode(path)

    fun list(scope: ListScope, search: Boolean = false): String {
        val raw = when (scope) {
            ListScope.All -> ALL
            ListScope.Archive -> ARCHIVE
            is ListScope.Folder -> scope.path
        }
        return "list/" + Uri.encode(raw) + "?search=$search"
    }

    fun scopeOf(raw: String): ListScope = when (raw) {
        ALL -> ListScope.All
        ARCHIVE -> ListScope.Archive
        else -> ListScope.Folder(raw)
    }

    const val ALL = "\u0000all"
    const val ARCHIVE = "\u0000archive"
}

@Composable
fun PigeonNavGraph(nav: NavHostController = rememberNavController(), startPath: String? = null) {
    NavHost(navController = nav, startDestination = Routes.HOME) {
        composable(Routes.HOME) {
            HomeScreen(
                onOpenNote = { nav.navigate(Routes.editor(it)) },
                onOpenFolder = { nav.navigate(Routes.list(ListScope.Folder(it))) },
                onSearch = { nav.navigate(Routes.list(ListScope.All, search = true)) },
                onOpenArchive = { nav.navigate(Routes.list(ListScope.Archive)) },
                onOpenSyncSettings = { nav.navigate(Routes.SYNC_SETTINGS) },
                onOpenAppSettings = { nav.navigate(Routes.APP_SETTINGS) },
            )
        }
        composable(
            Routes.LIST,
            arguments = listOf(
                navArgument("scope") { type = NavType.StringType },
                navArgument("search") { type = NavType.BoolType; defaultValue = false },
            ),
        ) { entry ->
            NoteListScreen(
                scope = Routes.scopeOf(entry.arguments?.getString("scope").orEmpty()),
                startSearching = entry.arguments?.getBoolean("search") == true,
                onBack = { nav.popBackStack() },
                onOpenNote = { nav.navigate(Routes.editor(it)) },
                onOpenSyncSettings = { nav.navigate(Routes.SYNC_SETTINGS) },
                onOpenAppSettings = { nav.navigate(Routes.APP_SETTINGS) },
            )
        }
        composable(
            Routes.EDITOR,
            arguments = listOf(navArgument("path") { type = NavType.StringType }),
        ) { entry ->
            val path = entry.arguments?.getString("path").orEmpty()
            EditorScreen(path = path, onBack = { nav.popBackStack() })
        }
        composable(Routes.SYNC_SETTINGS) {
            SettingsScreen(onBack = { nav.popBackStack() })
        }
        composable(Routes.APP_SETTINGS) {
            AppSettingsScreen(onBack = { nav.popBackStack() })
        }
    }
    if (startPath != null) {
        androidx.compose.runtime.LaunchedEffect(startPath) { nav.navigate(Routes.editor(startPath)) }
    }
}
