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
import app.gymquest.core.notifications.NotificationType
import app.gymquest.data.Actions
import app.gymquest.data.AppState
import app.gymquest.ui.Controller
import app.gymquest.ui.components.ChoiceChips
import app.gymquest.ui.components.GqCard
import app.gymquest.ui.components.Muted
import app.gymquest.ui.components.SectionTitle

private val VOICE = listOf("SILENT" to "Silencio", "MINIMAL" to "Poca", "NORMAL" to "Normal", "CHATTY" to "Animador")
private val STRICT = listOf("RELAXED" to "Relajado", "NORMAL" to "Normal", "STRICT" to "Estricto")
private val SOCIAL = setOf("FRIEND_ACTIVITY", "RANKING", "CHALLENGE")
private val MAP_STYLES = listOf(
    "3D claro" to app.gymquest.data.DEFAULT_MAP_STYLE,
    "Calles" to "https://tiles.openfreemap.org/styles/bright",
)
private val VISIBILITY = listOf("PUBLIC" to "Todos", "FRIENDS" to "Amigos", "PRIVATE" to "Solo yo")

@Composable
fun SettingsScreen(state: AppState, c: Controller, onBack: () -> Unit) {
    val s = state.settings
    Column(
        Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        BackHeader("Ajustes", onBack)

        SectionTitle("Mi perfil")
        GqCard(onClick = { c.change { Actions.startProfileEdit(it) } }) {
            Text("✏️ Editar mis datos y mi objetivo", fontWeight = FontWeight.Bold)
            Muted("Edad, estatura, peso, objetivo, equipo, dieta, piel y horario de sueño.")
        }

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

        SectionTitle("Recordatorios")
        GqCard {
            val effective = if (s.notificationTypes.isEmpty()) NotificationType.entries.filter { it.defaultOn }.map { it.name } else s.notificationTypes
            NotificationType.entries.filter { it.name !in SOCIAL }.forEach { type ->
                ToggleRow(type.label, type.name in effective) { on ->
                    val next = if (on) effective + type.name else effective - type.name
                    c.change { it.copy(settings = it.settings.copy(notificationTypes = next.distinct())) }
                }
            }
            Muted("Los recordatorios se calculan en tu teléfono y llegan aunque no haya internet. Los avisos de amigos y rankings necesitan conexión.")
        }

        SectionTitle("Mapa")
        GqCard {
            Text("Estilo de mapa", fontWeight = FontWeight.SemiBold)
            ChoiceChips(
                MAP_STYLES, MAP_STYLES.firstOrNull { it.second == s.mapStyle } ?: MAP_STYLES[0], { it.first },
                { v -> c.change { it.copy(settings = it.settings.copy(mapStyle = v.second)) } },
            )
            Muted("Las zonas descargadas solo se ven con el estilo con el que se descargaron.")
        }

        SectionTitle("Privacidad")
        GqCard {
            Text("¿Quién ve mi racha?", fontWeight = FontWeight.SemiBold)
            ChoiceChips(VISIBILITY, VISIBILITY.firstOrNull { it.first == s.streakVisibility } ?: VISIBILITY[0], { it.second }, { v -> c.change { it.copy(settings = it.settings.copy(streakVisibility = v.first)) } })
            Text("¿Quién ve mis estadísticas?", fontWeight = FontWeight.SemiBold)
            ChoiceChips(VISIBILITY, VISIBILITY.firstOrNull { it.first == s.statsVisibility } ?: VISIBILITY[1], { it.second }, { v -> c.change { it.copy(settings = it.settings.copy(statsVisibility = v.first)) } })
        }

        SectionTitle("Ayuda rápida")
        GqCard {
            Text("📷 Para que cuente bien", fontWeight = FontWeight.Bold)
            Muted("Apoya el teléfono a 2–3 metros, a la altura de tu cintura, y deja que se te vea el cuerpo completo. Con buena luz y sin nada tapando las articulaciones. Puede estar inclinado: se corrige solo. Si no cuenta, la pantalla te dice qué mover.")
            Text("🎧 Entrenador de voz", fontWeight = FontWeight.Bold)
            Muted("Usa audífonos y sube el volumen. Si tienes música sonando, baja sola mientras él habla. Puedes silenciarlo arriba en \"Voz del entrenador\".")
            Text("🗺️ Mapas sin internet", fontWeight = FontWeight.Bold)
            Muted("Antes de salir, en Mapa → \"Sin internet\", descarga tu zona. El GPS funciona siempre; el mapa solo se ve sin conexión donde lo descargaste.")
            Text("🔋 Batería", fontWeight = FontWeight.Bold)
            Muted("La app baja sola la calidad de la cámara si el teléfono se calienta o la batería está baja. Para actividades largas con GPS, deja la pantalla apagada: sigue grabando.")
            Text("🔒 Privacidad", fontWeight = FontWeight.Bold)
            Muted("Tus datos, fotos y música se quedan en tu teléfono. Nada se sube hasta que conectes un servidor, y tú eliges quién ve tu racha y tus estadísticas.")
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
