package app.gymquest.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.gymquest.GymQuestApp
import app.gymquest.core.music.DjPhase
import app.gymquest.data.AppState
import app.gymquest.music.MusicLibrary
import app.gymquest.ui.Controller
import app.gymquest.ui.components.Bar
import app.gymquest.ui.components.ChoiceChips
import app.gymquest.ui.components.GqCard
import app.gymquest.ui.components.Muted
import app.gymquest.ui.components.Pill
import app.gymquest.ui.components.PrimaryButton
import app.gymquest.ui.theme.Mint
import kotlin.math.roundToInt

private fun phaseLabel(p: DjPhase) = when (p) {
    DjPhase.WARMUP -> "Calentar"
    DjPhase.WORK -> "Entrenar"
    DjPhase.REST -> "Descansar"
    DjPhase.COOLDOWN -> "Enfriar"
}

/** The songs on the phone, a player that keeps going in the background and the personal DJ. */
@Composable
fun MusicScreen(state: AppState, c: Controller, onBack: () -> Unit) {
    val context = LocalContext.current
    val music = (context.applicationContext as GymQuestApp).music
    val tracks by music.tracks.collectAsStateWithLifecycle()
    val bpm by music.bpm.collectAsStateWithLifecycle()
    val analysis by music.analysis.collectAsStateWithLifecycle()
    val now by music.nowPlaying.collectAsStateWithLifecycle()
    val playing by music.playing.collectAsStateWithLifecycle()
    val djOn by music.djOn.collectAsStateWithLifecycle()
    val phase by music.phase.collectAsStateWithLifecycle()
    val target by music.targetBpm.collectAsStateWithLifecycle()
    val note by music.djNote.collectAsStateWithLifecycle()
    val hasPermission = MusicLibrary.hasPermission(context)

    LaunchedEffect(state.settings.bpmSync, state.settings.restMusic) { music.configure(state.settings.bpmSync, state.settings.restMusic) }
    LaunchedEffect(hasPermission) { if (hasPermission) music.refresh() }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted -> if (granted) music.refresh() }

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item { BackHeader("Música y DJ", onBack) }

        if (!hasPermission) {
            item {
                GqCard {
                    Text("Tu música, en tu teléfono", fontWeight = FontWeight.Bold)
                    Muted("Para tocar y analizar tus canciones necesito permiso para leer los archivos de audio. No se sube nada a internet.")
                    PrimaryButton("Permitir acceso a mi música", { ask.launch(MusicLibrary.permission) }, Modifier.fillMaxWidth())
                }
            }
        } else {
            item {
                GqCard {
                    Text(now?.title ?: "Nada sonando", fontWeight = FontWeight.Bold)
                    Muted(now?.let { t -> bpm[t.id.toString()]?.takeIf { it > 0 }?.let { "${t.artist} · ${it.roundToInt()} BPM" } ?: t.artist } ?: "Elige una canción de la lista")
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { music.toggle() }) { Text(if (playing) "⏸" else "▶") }
                        OutlinedButton(onClick = { music.skip() }) { Text("⏭") }
                        OutlinedButton(onClick = { music.like() }) { Text("♥") }
                    }
                    if (note.isNotBlank()) Muted(note)
                }
            }
            item {
                GqCard {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Column2 {
                            Text("🎧 Modo DJ", fontWeight = FontWeight.Bold)
                            Muted("Elige la siguiente canción según lo que estés haciendo y aprende de lo que saltas o repites.")
                        }
                        Switch(checked = djOn, onCheckedChange = { music.setDj(it) })
                    }
                    Text("Momento", fontWeight = FontWeight.SemiBold)
                    ChoiceChips(DjPhase.entries, phase, { phaseLabel(it) }, { music.setPhase(it) })
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Column2 {
                            Text("Sincronizar con mi ritmo (BPM)", fontWeight = FontWeight.SemiBold)
                            Muted("Opcional. Busca canciones al ritmo de tus pasos o repeticiones por minuto.")
                        }
                        Switch(checked = state.settings.bpmSync, onCheckedChange = { v -> c.change { it.copy(settings = it.settings.copy(bpmSync = v)) } })
                    }
                    if (state.settings.bpmSync) {
                        Text("Mi cadencia: ${target.roundToInt()} por minuto", fontWeight = FontWeight.SemiBold)
                        ChoiceChips(listOf(100, 110, 120, 130, 140, 150, 160, 170), target.roundToInt(), { "$it" }, { music.setTargetBpm(it.toDouble()) })
                        Muted("Caminar ≈ 100–120 pasos/min · trotar ≈ 150–170 · fuerza ≈ 100–130 repeticiones+pasos.")
                    }
                }
            }
            item {
                GqCard {
                    val a = analysis
                    if (a != null) {
                        Text("Analizando el ritmo… ${a.first} de ${a.second}", fontWeight = FontWeight.SemiBold)
                        Bar(a.first.toDouble() / a.second, color = Mint)
                    } else {
                        val done = tracks.count { it.id.toString() in bpm }
                        Text("Ritmo de tus canciones: $done de ${tracks.size}", fontWeight = FontWeight.SemiBold)
                        OutlinedButton(onClick = { music.analyzeAll() }, enabled = done < tracks.size) { Text("Analizar el ritmo (BPM)") }
                        Muted("Se calcula en el teléfono, una sola vez por canción. Puede tardar unos minutos.")
                    }
                }
            }
            item {
                GqCard {
                    Text("👥 Música en grupo", fontWeight = FontWeight.Bold)
                    Muted("Escuchar la misma lista sincronizada con tus amigos al correr necesita conexión entre teléfonos (Bluetooth/wifi directo o el servidor). La lógica de sincronización ya está lista; falta conectarla.")
                }
            }
            if (tracks.isEmpty()) item { Muted("No encontré canciones en este teléfono.") }
            items(tracks, key = { it.id }) { t ->
                GqCard(onClick = { music.play(t) }) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Column2(Modifier.weight(1f)) {
                            Text(t.title, fontWeight = if (now?.id == t.id) FontWeight.ExtraBold else FontWeight.Normal)
                            Muted(t.artist)
                        }
                        bpm[t.id.toString()]?.takeIf { it > 0 }?.let { Pill("${it.roundToInt()} BPM") }
                    }
                }
            }
        }
    }
}

@Composable
private fun Column2(modifier: Modifier = Modifier, content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) {
    androidx.compose.foundation.layout.Column(modifier.then(Modifier), verticalArrangement = Arrangement.spacedBy(2.dp), content = content)
}
