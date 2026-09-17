package ch.antoinette.pigeonnotes.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

val PigeonTypography = Typography(
    bodyLarge = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Normal,
        fontSize = 17.sp,
        lineHeight = 26.sp,
    ),
)

/** Styles used by the editor for headings and the base paragraph, indexed by heading level (0 = body). */
object EditorType {
    val body = TextStyle(fontSize = 17.sp, lineHeight = 26.sp)

    /**
     * Lists are set tighter than paragraphs: the wrapped lines inside an item must sit closer
     * together than two separate items do (the gap between items comes from the layout).
     */
    val list = TextStyle(fontSize = 17.sp, lineHeight = 22.sp)
    val code = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 15.sp, lineHeight = 22.sp)
    private val headings = listOf(
        body,
        TextStyle(fontSize = 30.sp, lineHeight = 38.sp, fontWeight = FontWeight.Bold),
        TextStyle(fontSize = 25.sp, lineHeight = 33.sp, fontWeight = FontWeight.Bold),
        TextStyle(fontSize = 21.sp, lineHeight = 29.sp, fontWeight = FontWeight.SemiBold),
        TextStyle(fontSize = 19.sp, lineHeight = 27.sp, fontWeight = FontWeight.SemiBold),
        TextStyle(fontSize = 17.sp, lineHeight = 26.sp, fontWeight = FontWeight.SemiBold),
        TextStyle(fontSize = 17.sp, lineHeight = 26.sp, fontWeight = FontWeight.Medium),
    )

    fun heading(level: Int): TextStyle = headings[level.coerceIn(0, 6)]
}
