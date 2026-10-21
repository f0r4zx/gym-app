package app.gymquest.ui

import android.Manifest
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.ui.platform.LocalContext
import app.gymquest.notify.Notifier
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.gymquest.data.Actions
import app.gymquest.data.AppState
import app.gymquest.data.GymRepository
import app.gymquest.data.Reward
import app.gymquest.data.Result
import app.gymquest.ui.screens.CharacterScreen
import app.gymquest.ui.screens.CommunityScreen
import app.gymquest.ui.screens.DiaryScreen
import app.gymquest.ui.screens.HomeScreen
import app.gymquest.ui.screens.LifeScreen
import app.gymquest.ui.screens.MapScreen
import app.gymquest.ui.screens.MissionsScreen
import app.gymquest.ui.screens.OnboardingScreen
import app.gymquest.ui.screens.SettingsScreen
import app.gymquest.ui.screens.TrainScreen
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import java.time.LocalDate

/** What a screen needs to change the game: apply an action and (if it earned something) show a message. */
class Controller(
    val repo: GymRepository,
    val today: LocalDate,
    private val messages: Channel<String>,
) {
    fun act(block: (AppState) -> Result): Reward {
        var reward = Reward.NONE
        repo.update { s ->
            val r = block(s)
            reward = r.reward
            r.state
        }
        announce(reward)
        return reward
    }

    fun change(block: (AppState) -> AppState) = repo.update(block)

    fun say(text: String) {
        messages.trySend(text)
    }

    fun announce(reward: Reward) {
        if (reward.isEmpty) return
        val parts = buildList {
            if (reward.xp > 0) add("+${reward.xp} XP")
            if (reward.coins > 0) add("+${reward.coins} 🪙")
            reward.newLevel?.let { add("¡Nivel $it!") }
            reward.achievements.forEach { add("🏆 $it") }
            reward.items.forEach { add("🎁 $it") }
        }
        if (parts.isNotEmpty()) messages.trySend(parts.joinToString("  ·  "))
    }
}

enum class Tab(val label: String, val icon: String) {
    HOME("Inicio", "🏠"), TRAIN("Entrenar", "💪"), MAP("Mapa", "🗺️"), LIFE("Vida", "🌿"), COMMUNITY("Comunidad", "👥")
}

enum class Sub { CHARACTER, MISSIONS, SETTINGS, DIARY }

@Composable
fun AppRoot(repo: GymRepository) {
    val state by repo.state.collectAsStateWithLifecycle()
    val today by produceState(LocalDate.now()) {
        while (true) {
            delay(60_000)
            value = LocalDate.now()
        }
    }
    if (!state.settings.onboardingDone || state.profile == null || state.settings.editProfile) {
        if (state.settings.editProfile) BackHandler { repo.update { Actions.cancelProfileEdit(it) } }
        OnboardingScreen(today = today, initial = if (state.settings.editProfile) state.profile else null) { profile ->
            repo.update { Actions.completeOnboarding(it, profile, today) }
        }
    } else {
        MainShell(repo, state, today)
    }
}

@Composable
private fun MainShell(repo: GymRepository, state: AppState, today: LocalDate) {
    var tabName by rememberSaveable { mutableStateOf(Tab.HOME.name) }
    var subName by rememberSaveable { mutableStateOf<String?>(null) }
    val tab = Tab.valueOf(tabName)
    val sub = subName?.let { Sub.valueOf(it) }

    val messages = remember { Channel<String>(Channel.BUFFERED) }
    val host = remember { SnackbarHostState() }
    LaunchedEffect(messages) {
        for (m in messages) {
            host.showSnackbar(m)
        }
    }
    val controller = remember(repo, today, messages) { Controller(repo, today, messages) }

    // Ask once for permission to show reminders (Android 13+).
    val context = LocalContext.current
    val askNotifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) Notifier.replan(context, repo.state.value, today)
    }
    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= 33 && !Notifier.canPost(context) && !state.settings.notificationsAsked) {
            repo.update { it.copy(settings = it.settings.copy(notificationsAsked = true)) }
            askNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    BackHandler(enabled = sub != null) { subName = null }
    BackHandler(enabled = sub == null && tab != Tab.HOME) { tabName = Tab.HOME.name }

    Scaffold(
        snackbarHost = { SnackbarHost(host) },
        bottomBar = {
            if (sub == null) {
                NavigationBar {
                    Tab.entries.forEach { t ->
                        NavigationBarItem(
                            selected = t == tab,
                            onClick = { tabName = t.name },
                            icon = { Text(t.icon, fontSize = 22.sp) },
                            label = { Text(t.label) },
                        )
                    }
                }
            }
        },
    ) { padding ->
        Box(Modifier.padding(padding)) {
            when (sub) {
                Sub.CHARACTER -> CharacterScreen(state, controller) { subName = null }
                Sub.MISSIONS -> MissionsScreen(state, controller) { subName = null }
                Sub.SETTINGS -> SettingsScreen(state, controller) { subName = null }
                Sub.DIARY -> DiaryScreen(state, controller) { subName = null }
                null -> when (tab) {
                    Tab.HOME -> HomeScreen(state, controller, open = { subName = it.name }, goTo = { tabName = it.name })
                    Tab.TRAIN -> TrainScreen(state, controller)
                    Tab.MAP -> MapScreen(state, controller)
                    Tab.LIFE -> LifeScreen(state, controller)
                    Tab.COMMUNITY -> CommunityScreen(state, controller)
                }
            }
        }
    }
}
