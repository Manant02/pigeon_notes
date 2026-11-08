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
import ch.antoinette.pigeonnotes.domain.NoteText
import ch.antoinette.pigeonnotes.ui.nav.PigeonNavGraph
import ch.antoinette.pigeonnotes.ui.theme.PigeonTheme
import ch.antoinette.pigeonnotes.ui.update.UpdatePrompt
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private var sharedNotePath by mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        handleShare(intent)
        // Not in PigeonApp: background sync starts the process too, and nobody would see a prompt.
        if (savedInstanceState == null) AppGraph.get(this).updater.checkOnStartup()
        setContent {
            PigeonTheme {
                PigeonNavGraph(startPath = sharedNotePath)
                UpdatePrompt()
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleShare(intent)
    }

    /**
     * "Share to Pigeon Notes": text (or a URL) becomes a new quick note. Its H1 is the subject, or a
     * placeholder, and opens selected so it can be renamed right away.
     */
    private fun handleShare(intent: Intent?) {
        if (intent?.action != Intent.ACTION_SEND || intent.type != "text/plain") return
        val text = intent.getStringExtra(Intent.EXTRA_TEXT) ?: return
        val subject = intent.getStringExtra(Intent.EXTRA_SUBJECT)?.trim()?.takeIf { it.isNotEmpty() && !text.startsWith(it) }
        val body = "# ${subject?.lines()?.joinToString(" ") ?: NoteText.SHARED_NOTE_TITLE}\n\n${text.trimEnd()}\n"
        intent.action = null // consume, so rotation does not re-share
        lifecycleScope.launch {
            sharedNotePath = AppGraph.get(this@MainActivity).notes.create("", initialText = body, selectTitle = true)
        }
    }
}
