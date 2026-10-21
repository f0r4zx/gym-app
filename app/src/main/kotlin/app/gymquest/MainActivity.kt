package app.gymquest

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import app.gymquest.core.sharing.DecodeResult
import app.gymquest.core.sharing.RoutineShare
import app.gymquest.data.Actions
import app.gymquest.notify.Notifier
import app.gymquest.ui.AppRoot
import app.gymquest.ui.theme.GymQuestTheme

class MainActivity : ComponentActivity() {
    private val repo get() = (application as GymQuestApp).repo

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        handleLink(intent)
        setContent {
            GymQuestTheme {
                AppRoot(repo)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleLink(intent)
    }

    /** A gymquest://routine/<code> link keeps the routine a friend sent. */
    private fun handleLink(intent: Intent?) {
        val uri = intent?.data ?: return
        if (uri.scheme != "gymquest" || uri.host != "routine") return
        val code = uri.lastPathSegment ?: return
        when (val r = RoutineShare.decode(code)) {
            is DecodeResult.Ok -> {
                repo.update { Actions.saveRoutine(it, r.routine) }
                Toast.makeText(this, "Rutina guardada: ${r.routine.name}", Toast.LENGTH_LONG).show()
            }
            is DecodeResult.Invalid -> Toast.makeText(this, r.reason, Toast.LENGTH_LONG).show()
        }
    }

    override fun onStop() {
        super.onStop()
        repo.flush()
        // Re-plan today's reminders from what has been done so far.
        Notifier.replan(this, repo.state.value)
    }
}
