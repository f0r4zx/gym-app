package app.gymquest.data

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import java.io.File

/**
 * The single source of truth for everything the user does. It lives on the phone (so the app works
 * with no signal), is saved as one small JSON file with atomic writes, and is written at most once
 * every half second so rapid taps never hammer the storage.
 */
class GymRepository(context: Context, private val scope: CoroutineScope) {
    private val file = File(context.filesDir, "state.json")
    private val tmp = File(context.filesDir, "state.json.tmp")
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; coerceInputValues = true }

    private val _state = MutableStateFlow(load())
    val state: StateFlow<AppState> = _state.asStateFlow()

    private var saveJob: Job? = null

    fun update(transform: (AppState) -> AppState) {
        _state.update(transform)
        scheduleSave()
    }

    /** Writes immediately; call when the app goes to the background. */
    fun flush() {
        saveJob?.cancel()
        write(_state.value)
    }

    private fun scheduleSave() {
        saveJob?.cancel()
        saveJob = scope.launch(Dispatchers.IO) {
            delay(SAVE_DEBOUNCE_MS)
            write(_state.value)
        }
    }

    private fun write(state: AppState) {
        runCatching {
            tmp.writeText(json.encodeToString(AppState.serializer(), state))
            if (!tmp.renameTo(file)) {
                file.delete()
                tmp.renameTo(file)
            }
        }.onFailure { Log.e(TAG, "Could not save state", it) }
    }

    private fun load(): AppState = runCatching {
        if (file.exists()) json.decodeFromString(AppState.serializer(), file.readText()) else AppState()
    }.getOrElse {
        Log.e(TAG, "Could not read saved state; starting fresh", it)
        // Keep the unreadable file around for debugging instead of silently overwriting it.
        runCatching { file.renameTo(File(file.parentFile, "state.corrupt.json")) }
        AppState()
    }

    private companion object {
        const val TAG = "GymRepository"
        const val SAVE_DEBOUNCE_MS = 500L
    }
}
