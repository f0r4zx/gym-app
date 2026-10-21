package app.gymquest.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.gymquest.data.AppState
import app.gymquest.ui.Controller
import app.gymquest.ui.components.ChoiceChips
import app.gymquest.ui.components.GqCard
import app.gymquest.ui.components.Muted
import app.gymquest.ui.components.SectionTitle

private val VOICE = listOf("SILENT" to "Silencio", "MINIMAL" to "Poca", "NORMAL" to "Normal", "CHATTY" to "Animador")
private val STRICT = listOf("RELAXED" to "Relajado", "NORMAL" to "Normal", "STRICT" to "Estricto")
private val VISIBILITY = listOf("PUBLIC" to "Todos", "FRIENDS" to "Amigos", "PRIVATE" to "Solo yo")

@Composable
fun SettingsScreen(state: AppState, c: Controller, onBack: () -> Unit) {
    val s = state.settings
    Column(
        Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        BackHeader("Ajustes", onBack)

        SectionTitle("Entrenamiento con cámara")
        GqCard {
            Text("Voz del entrenador", fontWeight = FontWeight.SemiBold)
            ChoiceChips(VOICE, VOICE.firstOrNull { it.first == s.voice } ?: VOICE[2], { it.second }, { v -> c.change { it.copy(settings = it.settings.copy(voice = v.first)) } })
            Text("Exigencia con la técnica", fontWeight = FontWeight.SemiBold)
            ChoiceChips(STRICT, STRICT.firstOrNull { it.first == s.strictness } ?: STRICT[1], { it.second }, { v -> c.change { it.copy(settings = it.settings.copy(strictness = v.first)) } })
            ToggleRow("Mostrar el esqueleto en pantalla", s.showSkeleton) { v -> c.change { it.copy(settings = it.settings.copy(showSkeleton = v)) } }
            ToggleRow("Usar cámara frontal", s.useFrontCamera) { v -> c.change { it.copy(settings = it.settings.copy(useFrontCamera = v)) } }
        }

        SectionTitle("Música")
        GqCard {
            ToggleRow("Música entre series (DJ entrenador)", s.restMusic) { v -> c.change { it.copy(settings = it.settings.copy(restMusic = v)) } }
            ToggleRow("Sincronizar la música con mi cadencia (BPM)", s.bpmSync) { v -> c.change { it.copy(settings = it.settings.copy(bpmSync = v)) } }
            Muted("Es opcional: si lo activas, la canción se elige según tus pasos o repeticiones por minuto.")
        }

        SectionTitle("Privacidad")
        GqCard {
            Text("¿Quién ve mi racha?", fontWeight = FontWeight.SemiBold)
            ChoiceChips(VISIBILITY, VISIBILITY.firstOrNull { it.first == s.streakVisibility } ?: VISIBILITY[0], { it.second }, { v -> c.change { it.copy(settings = it.settings.copy(streakVisibility = v.first)) } })
            Text("¿Quién ve mis estadísticas?", fontWeight = FontWeight.SemiBold)
            ChoiceChips(VISIBILITY, VISIBILITY.firstOrNull { it.first == s.statsVisibility } ?: VISIBILITY[1], { it.second }, { v -> c.change { it.copy(settings = it.settings.copy(statsVisibility = v.first)) } })
        }

        SectionTitle("Acerca de")
        GqCard {
            Text("GymQuest 0.1", fontWeight = FontWeight.Bold)
            Muted("Todo se guarda en tu teléfono y funciona sin internet. Las funciones sociales necesitan conexión y servidor.")
            Muted("Id local: ${s.userId.take(8)}")
        }
    }
}

@Composable
private fun ToggleRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}
