package app.gymquest.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.health.connect.client.PermissionController
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import app.gymquest.core.wellness.ScreenTimeAdvisor
import app.gymquest.core.wellness.SleepEstimator
import app.gymquest.core.wellness.SleepSession
import app.gymquest.core.wellness.UsageLevel
import app.gymquest.data.Actions
import app.gymquest.data.AppState
import app.gymquest.data.Derived
import app.gymquest.health.HealthSync
import app.gymquest.health.UsageProbe
import app.gymquest.ui.Controller
import app.gymquest.ui.components.GqCard
import app.gymquest.ui.components.Muted
import app.gymquest.ui.theme.Danger
import app.gymquest.ui.theme.Gold
import app.gymquest.ui.theme.Mint
import kotlinx.coroutines.launch
import java.time.ZoneId

private fun save(c: Controller, s: SleepSession) {
    c.act { Actions.logSleep(it, s.startMs, s.endMs, s.awakeMs, s.source.name, c.today) }
}

/** Ways to fill in last night's sleep without typing: a smartwatch (Health Connect) or the phone's own screen log. */
@Composable
internal fun SleepSources(state: AppState, c: Controller) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var tick by remember { mutableIntStateOf(0) }
    var hcGranted by remember { mutableStateOf(false) }
    var info by remember { mutableStateOf("") }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { tick++ }
    LaunchedEffect(tick) { hcGranted = HealthSync.hasPermissions(context) }

    val usageAccess = remember(tick) { UsageProbe.hasAccess(context) }
    val zone = ZoneId.systemDefault()
    val hcPermission = rememberLauncherForActivityResult(PermissionController.createRequestPermissionResultContract()) { granted ->
        hcGranted = granted.containsAll(HealthSync.permissions)
    }

    GqCard {
        Text("Registro automático", fontWeight = FontWeight.Bold)
        Muted("Sin teclear nada: usa tu reloj/pulsera o el registro de pantalla del teléfono.")
        if (HealthSync.available(context)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (!hcGranted) {
                    OutlinedButton(onClick = { hcPermission.launch(HealthSync.permissions) }, modifier = Modifier.weight(1f)) { Text("⌚ Conectar Health Connect") }
                } else {
                    OutlinedButton(
                        onClick = {
                            scope.launch {
                                val s = HealthSync.lastNight(context)
                                if (s != null) {
                                    save(c, s)
                                    info = "Sueño importado de Health Connect ✓"
                                } else {
                                    info = "Health Connect no tiene sueño de anoche."
                                }
                            }
                        },
                        modifier = Modifier.weight(1f),
                    ) { Text("⌚ Importar de Health Connect") }
                }
            }
        } else {
            Muted("Health Connect no está disponible en este teléfono (instálalo desde Play Store si tu reloj lo usa).")
        }
        if (!usageAccess) {
            OutlinedButton(onClick = { UsageProbe.openSettings(context) }, modifier = Modifier.fillMaxWidth()) { Text("📱 Permitir ver el uso de pantalla") }
            Muted("Solo se leen las horas en que la pantalla se prende y apaga, nunca qué apps usas.")
        } else {
            OutlinedButton(
                onClick = {
                    val now = System.currentTimeMillis()
                    val events = UsageProbe.screenEvents(context, now - 40 * 3_600_000L, now)
                    val guess = SleepEstimator.infer(events, now, zone)
                    if (guess != null) {
                        save(c, guess)
                        info = "Calculé tu sueño con el uso del teléfono (confianza ${(guess.confidence * 100).toInt()}%). Corrígelo abajo si no es correcto."
                    } else {
                        info = "No pude detectar tu sueño: faltan datos de anoche."
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("📱 Detectar con el uso del teléfono") }
        }
        if (info.isNotBlank()) Text(info, color = Mint)
    }

    if (usageAccess) {
        val usage = remember(tick) { UsageProbe.dailyUsage(context, System.currentTimeMillis(), zone) }
        if (usage != null) {
            val assess = ScreenTimeAdvisor.assess(usage, Derived.age(state, c.today))
            GqCard {
                Text("📵 Uso del teléfono hoy", fontWeight = FontWeight.Bold)
                Text(
                    "${usage.totalMs / 3_600_000} h ${usage.totalMs / 60_000 % 60} min · ${usage.pickups} desbloqueos",
                    color = when (assess.level) {
                        UsageLevel.GOOD -> Mint
                        UsageLevel.WATCH -> Gold
                        UsageLevel.HIGH -> Danger
                    },
                )
                assess.tips.forEach { Muted("• $it") }
            }
        }
    }
}
