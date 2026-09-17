package ch.antoinette.pigeonnotes.ui.editor

import android.graphics.Bitmap
import android.graphics.Canvas
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ru.noties.jlatexmath.JLatexMathDrawable

/** Renders TeX to bitmaps with JLaTeXMath; results are cached because formulas re-render on every recomposition. */
object Latex {
    class Rendered(val bitmap: ImageBitmap, val widthPx: Int, val heightPx: Int)

    private val cache = LruCache<String, Rendered>(200)

    /** Returns null when the formula does not parse (the UI then falls back to the source). */
    fun render(latex: String, textSizePx: Float, color: Color): Rendered? {
        val key = "$latex|${textSizePx.toInt()}|${color.toArgb()}"
        cache.get(key)?.let { return it }
        return runCatching {
            val drawable = JLatexMathDrawable.builder(latex)
                .textSize(textSizePx)
                .color(color.toArgb())
                .build()
            val w = drawable.intrinsicWidth.coerceAtLeast(1)
            val h = drawable.intrinsicHeight.coerceAtLeast(1)
            val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            drawable.setBounds(0, 0, w, h)
            drawable.draw(Canvas(bmp))
            Rendered(bmp.asImageBitmap(), w, h)
        }.getOrNull()?.also { cache.put(key, it) }
    }
}

/** A `$$…$$` block rendered centred; falls back to the monospace source if TeX rejects it. */
@Composable
fun DisplayMath(source: String, modifier: Modifier = Modifier) {
    val latex = source.trim().removePrefix("$$").removeSuffix("$$").trim()
    val color = MaterialTheme.colorScheme.onSurface
    val density = LocalDensity.current
    val sizePx = with(density) { 20.sp.toPx() }
    val rendered = remember(latex, color, sizePx) { Latex.render(latex, sizePx, color) }
    Box(modifier.fillMaxWidth().padding(vertical = 8.dp), contentAlignment = Alignment.Center) {
        if (rendered != null) {
            Image(
                bitmap = rendered.bitmap,
                contentDescription = latex,
                modifier = with(density) { Modifier.padding(4.dp).size(rendered.widthPx.toDp(), rendered.heightPx.toDp()) },
            )
        } else {
            Text(source, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.error)
        }
    }
}
