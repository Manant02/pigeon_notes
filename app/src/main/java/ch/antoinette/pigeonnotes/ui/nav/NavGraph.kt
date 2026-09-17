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
import ch.antoinette.pigeonnotes.ui.settings.SettingsScreen

object Routes {
    const val LIST = "list"
    const val SETTINGS = "settings"
    const val EDITOR = "editor/{path}"
    fun editor(path: String) = "editor/" + Uri.encode(path)
}

@Composable
fun PigeonNavGraph(nav: NavHostController = rememberNavController(), startPath: String? = null) {
    NavHost(navController = nav, startDestination = Routes.LIST) {
        composable(Routes.LIST) {
            NoteListScreen(
                onOpenNote = { nav.navigate(Routes.editor(it)) },
                onOpenSettings = { nav.navigate(Routes.SETTINGS) },
            )
        }
        composable(
            Routes.EDITOR,
            arguments = listOf(navArgument("path") { type = NavType.StringType }),
        ) { entry ->
            val path = entry.arguments?.getString("path").orEmpty()
            EditorScreen(path = path, onBack = { nav.popBackStack() })
        }
        composable(Routes.SETTINGS) {
            SettingsScreen(onBack = { nav.popBackStack() })
        }
    }
    if (startPath != null) {
        androidx.compose.runtime.LaunchedEffect(startPath) { nav.navigate(Routes.editor(startPath)) }
    }
}
