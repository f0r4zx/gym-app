package app.gymquest.ui.screens

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.gymquest.core.avatar.Stat
import app.gymquest.core.game.Progression
import app.gymquest.core.platform.Feature
import app.gymquest.core.platform.FeatureGate
import app.gymquest.core.platform.NetworkNeed
import app.gymquest.core.social.PrivacySettings
import app.gymquest.core.social.Privacy
import app.gymquest.core.social.ProfileData
import app.gymquest.core.social.Relation
import app.gymquest.core.social.Visibility
import app.gymquest.data.AppState
import app.gymquest.data.Derived
import app.gymquest.data.enumOr
import app.gymquest.ui.Controller
import app.gymquest.ui.components.ChoiceChips
import app.gymquest.ui.components.GqCard
import app.gymquest.ui.components.Muted
import app.gymquest.ui.components.Pill
import app.gymquest.ui.components.SectionTitle
import app.gymquest.ui.theme.Danger
import app.gymquest.ui.theme.Mint

/** True while the phone has a working internet connection. */
@Composable
fun rememberIsOnline(): Boolean {
    val context = LocalContext.current
    var online by remember { mutableStateOf(isOnline(context)) }
    DisposableEffect(context) {
        val cm = context.getSystemService(ConnectivityManager::class.java)
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                online = true
            }

            override fun onLost(network: Network) {
                online = isOnline(context)
            }
        }
        runCatching { cm?.registerDefaultNetworkCallback(callback) }
        onDispose { runCatching { cm?.unregisterNetworkCallback(callback) } }
    }
    return online
}

private fun isOnline(context: Context): Boolean {
    val cm = context.getSystemService(ConnectivityManager::class.java) ?: return false
    val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
    return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
}

@Composable
fun CommunityScreen(state: AppState, c: Controller) {
    val online = rememberIsOnline()
    val profile = Derived.profile(state) ?: return
    var viewer by rememberSaveable { mutableStateOf(Relation.STRANGER.name) }
    val relation = enumOr(viewer, Relation.STRANGER)

    val streak = Derived.streak(state, c.today)
    val now = System.currentTimeMillis()
    val power = Derived.stats(state).power(now)
    val data = ProfileData(
        userId = state.settings.userId, name = profile.name, title = Progression.titleFor(Derived.level(state)),
        level = Derived.level(state), currentStreak = streak.current, longestStreak = streak.longest, power = power,
        weeklyPoints = 0.0, guildTag = null, weightKg = profile.weightKg, lastActiveMs = now,
        bossesDefeated = state.game.bosses.size,
    )
    val privacy = PrivacySettings(
        streak = enumOr(state.settings.streakVisibility, Visibility.PUBLIC),
        level = Visibility.PUBLIC,
        stats = enumOr(state.settings.statsVisibility, Visibility.FRIENDS),
    )
    val view = Privacy.view(data, privacy, relation)

    Column(
        Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("👥 Comunidad", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.ExtraBold)
        GqCard {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(if (online) "🟢 Con conexión" else "🔴 Sin conexión", fontWeight = FontWeight.Bold)
                Pill("Servidor: no conectado")
            }
            Muted("Las funciones sociales (amigos, gremios, rankings, mercado entre jugadores y chat) necesitan el servidor de GymQuest, que todavía no está activo. Todo lo demás funciona sin internet.")
        }

        SectionTitle("Así te ven los demás")
        GqCard {
            Muted("Vista previa de tu perfil público según tu privacidad (cámbiala en Ajustes).")
            ChoiceChips(listOf(Relation.STRANGER, Relation.FRIEND, Relation.GUILDMATE), relation, {
                when (it) {
                    Relation.STRANGER -> "Desconocido"
                    Relation.FRIEND -> "Amigo"
                    else -> "Compañero de gremio"
                }
            }, { viewer = it.name })
            if (view != null) {
                Text(view.name, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Text(listOfNotNull(view.title, view.level?.let { "Nivel $it" }).joinToString(" · ").ifBlank { "Perfil privado" })
                Text(view.currentStreak?.let { "🔥 Racha de $it días (récord ${view.longestStreak})" } ?: "🔒 Racha oculta")
                Text(view.power?.let { "⚡ Poder $it · ${view.bossesDefeated} jefes" } ?: "🔒 Estadísticas ocultas")
                Text(if (view.weightKg == null) "🔒 Peso y medidas ocultos" else "⚠ Tu peso es visible", color = if (view.weightKg == null) Mint else Danger)
            }
        }

        SectionTitle("Qué funciona sin internet")
        val groups = listOf(
            "✅ Siempre disponible" to NetworkNeed.NONE,
            "🟡 Con límites sin conexión" to NetworkNeed.OPTIONAL,
            "🔌 Necesita internet y servidor" to NetworkNeed.REQUIRED,
        )
        groups.forEach { (title, need) ->
            val features = Feature.entries.filter { it.need == need }
            GqCard {
                Text("$title (${features.size})", fontWeight = FontWeight.Bold)
                features.forEach { f ->
                    val a = FeatureGate.check(f, online)
                    Text("• ${f.label}${if (!a.enabled) " — no disponible ahora" else ""}", style = MaterialTheme.typography.bodyMedium)
                    a.message?.let { note -> if (!online && a.limited) Muted(note) }
                }
            }
        }
    }
}

@Suppress("unused")
private val statsRef = Stat.entries
