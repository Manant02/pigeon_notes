package ch.antoinette.pigeonnotes.ui.notes

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver

/**
 * The accent a category is shown in. [DEFAULT] follows the wallpaper like the rest of the app;
 * the named ones are fixed hues with one tone per theme, so they stay readable in light and dark.
 */
enum class CategoryAccent(val label: String, val light: Long, val dark: Long) {
    DEFAULT("Default", 0L, 0L),
    BLUE("Blue", 0xFF0B57D0, 0xFFA8C7FA),
    TEAL("Teal", 0xFF00696E, 0xFF80D4DA),
    GREEN("Green", 0xFF146C2E, 0xFF9BD79E),
    LIME("Lime", 0xFF5B6300, 0xFFC6D26A),
    AMBER("Amber", 0xFF7D5700, 0xFFEFC14C),
    ORANGE("Orange", 0xFF8B4A1F, 0xFFFFB68A),
    RED("Red", 0xFFB3261E, 0xFFF2B8B5),
    PINK("Pink", 0xFF8E4457, 0xFFFFB1C4),
    PURPLE("Purple", 0xFF6750A4, 0xFFD0BCFF);

    companion object {
        fun of(name: String?): CategoryAccent = entries.firstOrNull { it.name == name } ?: DEFAULT
    }
}

/** The accent itself: for titles, icons and the bar down the side of a section. */
@Composable
fun CategoryAccent.color(): Color =
    if (this == CategoryAccent.DEFAULT) MaterialTheme.colorScheme.primary
    else Color(if (isSystemInDarkTheme()) dark else light)

/** A tinted surface to sit behind a section, kept subtle enough for body text to stay legible. */
@Composable
fun CategoryAccent.container(): Color =
    if (this == CategoryAccent.DEFAULT) MaterialTheme.colorScheme.surfaceContainer
    else color().copy(alpha = 0.14f).compositeOver(MaterialTheme.colorScheme.surfaceContainerLow)
