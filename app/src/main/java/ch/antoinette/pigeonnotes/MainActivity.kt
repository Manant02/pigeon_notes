package ch.antoinette.pigeonnotes

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.lifecycleScope
import ch.antoinette.pigeonnotes.ui.nav.PigeonNavGraph
import ch.antoinette.pigeonnotes.ui.theme.PigeonTheme
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private var sharedNotePath by mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        handleShare(intent)
        setContent {
            PigeonTheme {
                PigeonNavGraph(startPath = sharedNotePath)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleShare(intent)
    }

    /** "Share to Pigeon Notes": text (or a URL) becomes a new quick note; a subject becomes its first line. */
    private fun handleShare(intent: Intent?) {
        if (intent?.action != Intent.ACTION_SEND || intent.type != "text/plain") return
        val text = intent.getStringExtra(Intent.EXTRA_TEXT) ?: return
        val subject = intent.getStringExtra(Intent.EXTRA_SUBJECT)?.takeIf { it.isNotBlank() }
        val body = if (subject != null && !text.startsWith(subject)) "$subject\n\n$text\n" else text + "\n"
        intent.action = null // consume, so rotation does not re-share
        lifecycleScope.launch {
            sharedNotePath = AppGraph.get(this@MainActivity).notes.create("", initialText = body)
        }
    }
}
