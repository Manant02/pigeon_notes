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
import ch.antoinette.pigeonnotes.ui.notes.CategoriesScreen
import ch.antoinette.pigeonnotes.ui.notes.HomeScreen
import ch.antoinette.pigeonnotes.ui.notes.NoteListScreen
import ch.antoinette.pigeonnotes.ui.settings.AppSettingsScreen
import ch.antoinette.pigeonnotes.ui.settings.SettingsScreen

object Routes {
    const val HOME = "home"
    const val CATEGORIES = "categories"
    /** `category`: empty = uncategorized, "all" = every note, otherwise the folder. */
    const val LIST = "list/{category}?search={search}"
    const val SYNC_SETTINGS = "settings/sync"
    const val APP_SETTINGS = "settings/app"
    const val EDITOR = "editor/{path}"
    fun editor(path: String) = "editor/" + Uri.encode(path)

    /** [category] is null for every note, "" for uncategorized, otherwise the folder. */
    fun list(category: String?, search: Boolean = false) =
        "list/" + Uri.encode(category ?: ALL) + "?search=$search"

    const val ALL = "\u0000all"
}

@Composable
fun PigeonNavGraph(nav: NavHostController = rememberNavController(), startPath: String? = null) {
    NavHost(navController = nav, startDestination = Routes.HOME) {
        composable(Routes.HOME) {
            HomeScreen(
                onOpenNote = { nav.navigate(Routes.editor(it)) },
                onOpenCategory = { nav.navigate(Routes.list(it)) },
                onSearch = { nav.navigate(Routes.list(null, search = true)) },
                onOpenAllCategories = { nav.navigate(Routes.CATEGORIES) },
                onOpenSyncSettings = { nav.navigate(Routes.SYNC_SETTINGS) },
                onOpenAppSettings = { nav.navigate(Routes.APP_SETTINGS) },
            )
        }
        composable(Routes.CATEGORIES) {
            CategoriesScreen(
                onBack = { nav.popBackStack() },
                onOpenCategory = { nav.navigate(Routes.list(it)) },
            )
        }
        composable(
            Routes.LIST,
            arguments = listOf(
                navArgument("category") { type = NavType.StringType },
                navArgument("search") { type = NavType.BoolType; defaultValue = false },
            ),
        ) { entry ->
            val raw = entry.arguments?.getString("category").orEmpty()
            NoteListScreen(
                category = if (raw == Routes.ALL) null else raw,
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
