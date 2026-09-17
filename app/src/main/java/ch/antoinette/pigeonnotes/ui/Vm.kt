package ch.antoinette.pigeonnotes.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import ch.antoinette.pigeonnotes.AppGraph

/** `viewModel` with access to the app graph, without a DI framework. */
@Composable
inline fun <reified VM : ViewModel> graphViewModel(
    key: String? = null,
    crossinline create: (AppGraph) -> VM,
): VM {
    val graph = AppGraph.get(LocalContext.current)
    return viewModel(key = key, factory = viewModelFactory { initializer { create(graph) } })
}
