package app.gymquest.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import app.gymquest.camera.Speaker
import app.gymquest.camera.TrainingEngine
import app.gymquest.camera.TrainingSummary
import app.gymquest.core.avatar.BattleModifiers
import app.gymquest.core.avatar.Cosmetics
import app.gymquest.core.exercise.CountingMode
import app.gymquest.core.exercise.ExerciseCategory
import app.gymquest.core.exercise.ExerciseType
import app.gymquest.core.exercise.Strictness
import app.gymquest.core.game.Boss
import app.gymquest.core.game.Bosses
import app.gymquest.core.voice.Verbosity
import app.gymquest.data.Actions
import app.gymquest.data.AppState
import app.gymquest.data.Derived
import app.gymquest.data.Reward
import app.gymquest.data.enumOr
import app.gymquest.ui.Controller
import app.gymquest.ui.components.ChoiceChips
import app.gymquest.ui.components.GqCard
import app.gymquest.ui.components.Muted
import app.gymquest.ui.components.Pill
import app.gymquest.ui.components.PrimaryButton
import app.gymquest.ui.components.SectionTitle
import app.gymquest.ui.theme.Danger
import app.gymquest.ui.theme.Gold
import app.gymquest.ui.theme.Mint
import kotlin.math.roundToInt

@Composable
fun TrainScreen(state: AppState, c: Controller) {
    var target by rememberSaveable { mutableStateOf<String?>(null) }
    val t = target
    when {
        t == null -> TrainMenu(state) { target = it }
        t == "plan" -> {
            BackHandler { target = null }
            PlanScreen(state, c, onTrain = { target = it }, onBack = { target = null })
        }
        else -> TrainFlow(state, c, t) { target = null }
    }
}

@Composable
private fun TrainMenu(state: AppState, onPick: (String) -> Unit) {
    val level = Derived.level(state)
    Column(
        Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Entrenar", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.ExtraBold)
        Muted("La cámara cuenta tus repeticiones y revisa tu postura. Funciona sin internet.")

        GqCard(onClick = { onPick("plan") }) {
            Text("📋 Mi plan y recomendaciones", fontWeight = FontWeight.Bold)
            Muted("Los mejores ejercicios para tu edad y tu meta, y tu semana de entrenamiento")
        }

        SectionTitle("⚔️ Jefes")
        Bosses.all.forEach { boss ->
            val locked = level < boss.unlockLevel
            val beaten = boss.id in state.game.bosses
            GqCard(onClick = if (locked) null else ({ onPick("boss:${boss.id}") })) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text((if (locked) "🔒 " else if (beaten) "✅ " else "") + boss.name, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                    Pill(boss.tier.label)
                }
                Muted(boss.title)
                Muted(
                    if (locked) "Se desbloquea en el nivel ${boss.unlockLevel}"
                    else "${boss.exercises.joinToString(" / ") { it.displayName }} · ${boss.maxHp} PV · +${boss.xpReward} XP",
                )
                if (!locked) Muted("Botín: ${Cosmetics.byId(boss.lootId)?.name ?: boss.lootId}")
            }
        }

        SectionTitle("🏋️ Entrenamiento libre")
        ExerciseCategory.entries.forEach { cat ->
            val items = ExerciseType.entries.filter { it.category == cat }
            if (items.isEmpty()) return@forEach
            Text(cat.label, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurfaceVariant)
            items.forEach { ex ->
                GqCard(onClick = { onPick("free:${ex.id}") }) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Text(ex.displayName, fontWeight = FontWeight.Bold)
                        Pill(ex.view.label)
                    }
                    Muted(ex.muscles.joinToString(", ") { it.label })
                    val total = state.game.repsByExercise[ex.id] ?: 0
                    if (total > 0) Muted("Total acumulado: $total ${if (ex.mode == CountingMode.HOLD) "s" else "reps"}")
                }
            }
        }
    }
}

private enum class Stage { INTRO, LIVE, RESULT }

@Composable
private fun TrainFlow(state: AppState, c: Controller, target: String, onExit: () -> Unit) {
    val context = LocalContext.current
    val boss: Boss? = if (target.startsWith("boss:")) Bosses.byId(target.removePrefix("boss:")) else null
    val type: ExerciseType? = boss?.primaryExercise ?: ExerciseType.entries.firstOrNull { it.id == target.removePrefix("free:") }
    if (type == null) {
        onExit()
        return
    }

    var stage by rememberSaveable { mutableStateOf(Stage.INTRO.name) }
    var targetReps by rememberSaveable { mutableIntStateOf(if (type.mode == CountingMode.HOLD) 30 else 10) }
    var engine by remember { mutableStateOf<TrainingEngine?>(null) }
    var summary by remember { mutableStateOf<TrainingSummary?>(null) }
    var reward by remember { mutableStateOf(Reward.NONE) }
    val speaker = remember { arrayOfNulls<Speaker>(1) }
    DisposableEffect(Unit) { onDispose { speaker[0]?.shutdown() } }

    // A saved "live" stage without an engine (process restarted) falls back to the intro.
    val current = Stage.valueOf(stage).let { if (it == Stage.LIVE && engine == null) Stage.INTRO else if (it == Stage.RESULT && summary == null) Stage.INTRO else it }

    fun begin() {
        val sp = speaker[0] ?: Speaker(context).also { speaker[0] = it }
        val stats = Derived.statValues(state, System.currentTimeMillis())
        engine = TrainingEngine(
            type = type,
            boss = boss,
            speaker = sp,
            strictness = enumOr(state.settings.strictness, Strictness.NORMAL),
            verbosity = enumOr(state.settings.voice, Verbosity.NORMAL),
            targetReps = if (boss == null) targetReps else null,
            modifiers = if (boss != null) BattleModifiers.fromStats(stats) else BattleModifiers.NONE,
        )
        stage = Stage.LIVE.name
    }

    fun finish() {
        val e = engine ?: return
        val s = e.finish()
        summary = s
        reward = c.act {
            Actions.finishSet(
                it, s.type, s.reps, s.avgQuality, s.durationMs, System.currentTimeMillis(), c.today,
                bossId = s.bossId, victory = s.victory, flawless = s.flawless, bestCombo = s.bestCombo,
            )
        }
        engine = null
        stage = Stage.RESULT.name
    }

    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) begin() else c.say("Necesito la cámara para contar tus repeticiones.")
    }
    fun requestStart() {
        val has = ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        if (has) begin() else permission.launch(Manifest.permission.CAMERA)
    }

    when (current) {
        Stage.INTRO -> {
            BackHandler { onExit() }
            Intro(type, boss, targetReps, { targetReps = it }, onStart = ::requestStart, onBack = onExit)
        }
        Stage.LIVE -> {
            BackHandler { finish() }
            engine?.let { CameraTraining(it, state.settings, onStop = ::finish) }
        }
        Stage.RESULT -> {
            BackHandler { onExit() }
            summary?.let { TrainResult(it, reward, boss, onAgain = { stage = Stage.INTRO.name }, onExit = onExit) }
        }
    }
}

@Composable
private fun Intro(type: ExerciseType, boss: Boss?, targetReps: Int, onTarget: (Int) -> Unit, onStart: () -> Unit, onBack: () -> Unit) {
    Column(
        Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        BackHeader(boss?.name ?: type.displayName, onBack)
        if (boss != null) {
            GqCard {
                Text(boss.title, fontWeight = FontWeight.Bold)
                Text(boss.intro)
                Muted("Vence a ${boss.name} con ${boss.exercises.joinToString(" o ") { it.displayName.lowercase() }}. Si descansas demasiado, te ataca.")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Pill("${boss.maxHp} PV")
                    Pill("⏱ ${boss.timeLimitMs / 60_000} min")
                    Pill("+${boss.xpReward} XP", Gold.copy(alpha = 0.25f))
                }
            }
        }
        GqCard {
            Text("📱 Cómo colocar el celular", fontWeight = FontWeight.Bold)
            Text(type.setupTip)
            Muted("Vista: ${type.view.label}. Puede estar inclinado o apoyado en cualquier cosa; se corrige solo.")
        }
        if (boss == null) {
            GqCard {
                Text(if (type.mode == CountingMode.HOLD) "Meta de segundos" else "Meta de repeticiones", fontWeight = FontWeight.Bold)
                val options = if (type.mode == CountingMode.HOLD) listOf(20, 30, 45, 60, 90) else listOf(5, 10, 15, 20, 30)
                ChoiceChips(options, targetReps, { it.toString() }, onTarget)
                Muted("La voz te avisará cuando falten pocas.")
            }
        }
        PrimaryButton("Abrir cámara y empezar", onStart, Modifier.fillMaxWidth())
        Muted("Cuando te vea de cuerpo completo, empezará una cuenta regresiva de 3 segundos.")
    }
}

@Composable
private fun TrainResult(s: TrainingSummary, reward: Reward, boss: Boss?, onAgain: () -> Unit, onExit: () -> Unit) {
    Column(
        Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        val title = when {
            boss != null && s.victory == true -> "¡Derrotaste a ${boss.name}! 🏆"
            boss != null -> "${boss.name} te venció esta vez"
            s.reps > 0 -> "¡Buen trabajo!"
            else -> "Sin repeticiones esta vez"
        }
        Text(title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.ExtraBold)
        GqCard {
            Text("${s.reps} ${if (s.type.mode == CountingMode.HOLD) "segundos" else "repeticiones"}", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            Muted("${s.type.displayName} · calidad media ${(s.avgQuality * 100).roundToInt()}% · mejor combo x${s.bestCombo}")
            Muted("Tiempo activo: ${s.durationMs / 60_000}:${"%02d".format(s.durationMs / 1000 % 60)}")
            if (boss != null && s.victory == true) {
                if (s.flawless) Text("✨ ¡Victoria perfecta! Bonus de XP", color = Gold, fontWeight = FontWeight.Bold)
                Cosmetics.byId(boss.lootId)?.let { Text("🎁 Botín: ${it.name}", color = Mint, fontWeight = FontWeight.Bold) }
            }
        }
        if (!reward.isEmpty) {
            GqCard(container = Gold.copy(alpha = 0.15f)) {
                Text("Recompensas", fontWeight = FontWeight.Bold)
                if (reward.xp > 0) Text("+${reward.xp} XP")
                if (reward.coins > 0) Text("+${reward.coins} 🪙 monedas")
                reward.newLevel?.let { Text("🎉 ¡Subiste al nivel $it!", fontWeight = FontWeight.Bold) }
                reward.achievements.forEach { Text("🏆 Logro: $it") }
                reward.items.forEach { Text("🎁 Nuevo objeto: $it") }
            }
        }
        if (s.reps > 0 && s.avgQuality < 0.6) Text("💡 Baja el ritmo y cuida el rango completo: las repeticiones de calidad dan más XP y más daño.", color = Danger)
        PrimaryButton("Otra serie", onAgain, Modifier.fillMaxWidth())
        OutlinedButton(onClick = onExit, modifier = Modifier.fillMaxWidth()) { Text("Volver") }
    }
}
