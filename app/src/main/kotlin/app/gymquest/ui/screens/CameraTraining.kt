package app.gymquest.ui.screens

import android.os.SystemClock
import android.util.Size
import android.view.Surface
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import app.gymquest.camera.DeviceProbe
import app.gymquest.camera.Hud
import app.gymquest.camera.PoseAnalyzer
import app.gymquest.camera.RollSensor
import app.gymquest.camera.TrainingEngine
import app.gymquest.core.exercise.SessionState
import app.gymquest.core.platform.AdaptiveQuality
import app.gymquest.core.platform.FramePacer
import app.gymquest.core.platform.PoseModel
import app.gymquest.core.platform.QualityProfile
import app.gymquest.core.pose.Joint
import app.gymquest.data.SettingsDto
import app.gymquest.ui.components.Bar
import app.gymquest.ui.theme.Danger
import app.gymquest.ui.theme.Gold
import app.gymquest.ui.theme.Mint
import kotlinx.coroutines.delay
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.min

/** The live camera screen: preview, skeleton, counter and boss fight. */
@Composable
fun CameraTraining(engine: TrainingEngine, settings: SettingsDto, onStop: () -> Unit) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val view = LocalView.current
    val front = settings.useFrontCamera

    val previewView = remember {
        PreviewView(context).apply { scaleType = PreviewView.ScaleType.FIT_CENTER }
    }
    val pacer = remember { FramePacer(QualityProfile.BALANCED.analysisFps) }
    val quality = remember { AdaptiveQuality() }
    val modelRef = remember { AtomicReference(QualityProfile.BALANCED.poseModel) }
    var drawSkeleton by remember { mutableStateOf(true) }
    val frameMs = remember { DoubleArray(1) }
    val roll = remember { RollSensor(context) { view.display?.rotation ?: Surface.ROTATION_0 } }

    DisposableEffect(lifecycleOwner, front) {
        view.keepScreenOn = true
        roll.start()
        val executor = Executors.newSingleThreadExecutor()
        val analyzer = PoseAnalyzer(executor, pacer, mirror = front, model = { modelRef.get() }) { frame, tookMs ->
            if (tookMs > 0) frameMs[0] = if (frameMs[0] == 0.0) tookMs else frameMs[0] * 0.9 + tookMs * 0.1
            if (frame == null) engine.onNoPerson(SystemClock.elapsedRealtime()) else engine.onFrame(frame, roll.rollDegrees)
        }
        val future = ProcessCameraProvider.getInstance(context)
        var provider: ProcessCameraProvider? = null
        future.addListener({
            val p = future.get()
            provider = p
            val ratio = AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY
            val preview = Preview.Builder()
                .setResolutionSelector(ResolutionSelector.Builder().setAspectRatioStrategy(ratio).build())
                .build()
                .also { it.surfaceProvider = previewView.surfaceProvider }
            val analysis = ImageAnalysis.Builder()
                .setResolutionSelector(
                    ResolutionSelector.Builder()
                        .setAspectRatioStrategy(ratio)
                        .setResolutionStrategy(ResolutionStrategy(Size(640, 480), ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER))
                        .build(),
                )
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
                .also { it.setAnalyzer(executor, analyzer) }
            p.unbindAll()
            p.bindToLifecycle(
                lifecycleOwner,
                if (front) CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA,
                preview, analysis,
            )
        }, ContextCompat.getMainExecutor(context))
        onDispose {
            roll.stop()
            provider?.unbindAll()
            executor.shutdown()
            analyzer.close()
            view.keepScreenOn = false
        }
    }

    // Every couple of seconds look at battery, heat and speed and lower the workload if needed.
    LaunchedEffect(Unit) {
        while (true) {
            delay(2_000)
            val profile = quality.update(DeviceProbe.read(context, frameMs[0]))
            pacer.targetFps = profile.analysisFps
            modelRef.set(profile.poseModel)
            drawSkeleton = profile.drawSkeleton
        }
    }

    // Tell the DJ what part of the workout this is, so the next song fits it.
    val music = (context.applicationContext as app.gymquest.GymQuestApp).music
    LaunchedEffect(engine.hud.state) {
        music.setPhase(
            when (engine.hud.state) {
                SessionState.ACTIVE -> app.gymquest.core.music.DjPhase.WORK
                SessionState.SETUP, SessionState.COUNTDOWN -> app.gymquest.core.music.DjPhase.WARMUP
                SessionState.PAUSED -> app.gymquest.core.music.DjPhase.REST
            },
        )
    }

    // When the fight ends, leave the picture up for a moment before showing the result.
    LaunchedEffect(engine.hud.outcome) {
        if (engine.hud.outcome != null) {
            delay(1_800)
            onStop()
        }
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        AndroidView(factory = { previewView }, modifier = Modifier.fillMaxSize())
        if (settings.showSkeleton && drawSkeleton) SkeletonOverlay(engine)
        TrainingHud(engine, onStop)
    }
}

@Composable
private fun SkeletonOverlay(engine: TrainingEngine) {
    Canvas(Modifier.fillMaxSize()) {
        // Reading the pose here (draw phase) redraws only this canvas for each new frame.
        val frame = engine.pose ?: return@Canvas
        val aspect = frame.aspect.toFloat()
        // The preview shows the whole picture (letterboxed), so the skeleton uses the same fit.
        val scale = min(size.width / aspect, size.height)
        val w = aspect * scale
        val h = scale
        val ox = (size.width - w) / 2f
        val oy = (size.height - h) / 2f
        fun point(j: Joint): Offset? = frame.landmark(j)
            ?.takeIf { it.likelihood >= 0.5 }
            ?.let { Offset(ox + it.x.toFloat() * w, oy + it.y.toFloat() * h) }
        for ((a, b) in Joint.BONES) {
            val pa = point(a)
            val pb = point(b)
            if (pa != null && pb != null) drawLine(Mint, pa, pb, 7f, StrokeCap.Round)
        }
        for (j in Joint.BODY) point(j)?.let { drawCircle(Color.White, 8f, it) }
    }
}

@Composable
private fun TrainingHud(engine: TrainingEngine, onStop: () -> Unit) {
    val hud = engine.hud
    val boss = engine.boss

    Column(Modifier.fillMaxSize().safeDrawingPadding().padding(12.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Shaded {
                    Text(boss?.let { "👹 ${it.name}" } ?: engine.type.displayName, color = Color.White, fontWeight = FontWeight.Bold)
                    if (hud.bossHp != null) {
                        Bar(hud.bossHp, color = Danger, height = 12.dp)
                        Bar(hud.playerHp ?: 1.0, color = Mint, height = 8.dp)
                    }
                    hud.timeLeftSec?.let { Text("⏱ ${it / 60}:${"%02d".format(it % 60)}", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold) }
                }
            }
            OutlinedButton(onClick = onStop) { Text(if (hud.reps > 0 || hud.outcome != null) "Terminar" else "Salir", color = Color.White) }
        }

        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            when {
                hud.outcome != null -> BigMessage(
                    when (hud.outcome) {
                        app.gymquest.camera.Outcome.VICTORY -> "¡VICTORIA! 🏆"
                        app.gymquest.camera.Outcome.DEFEAT_TIME -> "Se acabó el tiempo"
                        app.gymquest.camera.Outcome.DEFEAT_HP -> "Has caído…"
                        else -> "¡Listo!"
                    },
                )
                hud.state == SessionState.COUNTDOWN && hud.countdown != null -> Text(
                    "${hud.countdown}", color = Color.White, fontSize = 140.sp, fontWeight = FontWeight.ExtraBold,
                )
                hud.state != SessionState.ACTIVE && hud.message.isNotBlank() -> BigMessage(hud.message)
            }
            Popup(hud)
        }

        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            hud.liveIssue?.let {
                Text(
                    "⚠ $it", color = Gold, fontWeight = FontWeight.Bold, fontSize = 18.sp, textAlign = TextAlign.Center,
                    modifier = Modifier.clip(RoundedCornerShape(10.dp)).background(Color.Black.copy(alpha = 0.55f)).padding(horizontal = 12.dp, vertical = 6.dp),
                )
            }
            Text("${hud.reps}", color = Color.White, fontSize = 96.sp, fontWeight = FontWeight.ExtraBold)
            Text(
                if (engine.type.mode == app.gymquest.core.exercise.CountingMode.HOLD) "segundos" else "repeticiones",
                color = Color.White.copy(alpha = 0.85f),
            )
            if (hud.combo >= 2) Text("🔥 Combo x${hud.combo}", color = Gold, fontWeight = FontWeight.Bold, fontSize = 18.sp)
        }
    }
}

@Composable
private fun Popup(hud: Hud) {
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(hud.popupId) {
        if (hud.popup != null) {
            visible = true
            delay(800)
            visible = false
        }
    }
    if (visible && hud.popup != null) {
        Text(
            hud.popup, color = if (hud.popup.startsWith("¡Te")) Danger else Gold, fontSize = 34.sp, fontWeight = FontWeight.ExtraBold,
            modifier = Modifier.alpha(0.95f),
        )
    }
}

@Composable
private fun BigMessage(text: String) {
    Text(
        text, color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center,
        modifier = Modifier.clip(RoundedCornerShape(16.dp)).background(Color.Black.copy(alpha = 0.6f)).padding(horizontal = 18.dp, vertical = 12.dp),
    )
}

@Composable
private fun Shaded(content: @Composable () -> Unit) {
    Column(
        Modifier.clip(RoundedCornerShape(12.dp)).background(Color.Black.copy(alpha = 0.5f)).padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) { content() }
}

@Suppress("unused")
private val unusedTheme = MaterialTheme
