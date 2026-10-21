package app.gymquest

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import app.gymquest.notify.Notifier
import app.gymquest.ui.AppRoot
import app.gymquest.ui.theme.GymQuestTheme

class MainActivity : ComponentActivity() {
    private val repo get() = (application as GymQuestApp).repo

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            GymQuestTheme {
                AppRoot(repo)
            }
        }
    }

    override fun onStop() {
        super.onStop()
        repo.flush()
        // Re-plan today's reminders from what has been done so far.
        Notifier.replan(this, repo.state.value)
    }
}
