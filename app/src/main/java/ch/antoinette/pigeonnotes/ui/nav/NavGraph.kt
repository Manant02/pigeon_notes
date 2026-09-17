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
import ch.antoinette.pigeonnotes.ui.notes.NoteListScreen
import ch.antoinette.pigeonnotes.ui.settings.AppSettingsScreen
import ch.antoinette.pigeonnotes.ui.settings.SettingsScreen

object Routes {
    const val LIST = "list"
    const val SYNC_SETTINGS = "settings/sync"
    const val APP_SETTINGS = "settings/app"
    const val EDITOR = "editor/{path}"
    fun editor(path: String) = "editor/" + Uri.encode(path)
}

@Composable
fun PigeonNavGraph(nav: NavHostController = rememberNavController(), startPath: String? = null) {
    NavHost(navController = nav, startDestination = Routes.LIST) {
        composable(Routes.LIST) {
            NoteListScreen(
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
