package app.gymquest

import android.app.Application
import app.gymquest.data.GymRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

class GymQuestApp : Application() {
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** Created on first use so the app starts drawing before the saved file is read. */
    val repo: GymRepository by lazy { GymRepository(this, appScope) }
}
