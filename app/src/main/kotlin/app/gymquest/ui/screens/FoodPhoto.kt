package app.gymquest.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.OutlinedButton
import app.gymquest.ui.components.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import app.gymquest.core.fitness.DietPreferences
import app.gymquest.core.nutrition.FoodMatch
import app.gymquest.core.nutrition.FoodMatcher
import app.gymquest.core.nutrition.ImageLabel
import app.gymquest.core.nutrition.PortionSize
import app.gymquest.data.enumOr
import app.gymquest.ui.components.ChoiceChips
import app.gymquest.ui.components.GqCard
import app.gymquest.ui.components.Muted
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.label.ImageLabeling
import com.google.mlkit.vision.label.defaults.ImageLabelerOptions
import java.io.File
import kotlin.math.roundToInt

/**
 * Take or pick a photo of a meal; the phone's own image model (no internet) names what it sees and
 * the food database turns that into a suggestion you confirm. It is an estimate, never a measurement.
 */
@Composable
internal fun FoodPhoto(diet: DietPreferences, onAdd: (FoodMatch) -> Unit) {
    val context = LocalContext.current
    var labels by remember { mutableStateOf<List<ImageLabel>?>(null) }
    var busy by remember { mutableStateOf(false) }
    var pendingUri by remember { mutableStateOf<Uri?>(null) }
    var sizeName by rememberSaveable { mutableStateOf(PortionSize.MEDIUM.name) }
    val size = enumOr(sizeName, PortionSize.MEDIUM)

    fun analyze(uri: Uri) {
        busy = true
        labels = null
        val image = runCatching { InputImage.fromFilePath(context, uri) }.getOrNull()
        if (image == null) {
            busy = false
            labels = emptyList()
            return
        }
        val labeler = ImageLabeling.getClient(ImageLabelerOptions.Builder().setConfidenceThreshold(0.4f).build())
        labeler.process(image)
            .addOnSuccessListener { result -> labels = result.map { ImageLabel(it.text, it.confidence.toDouble()) } }
            .addOnFailureListener { labels = emptyList() }
            .addOnCompleteListener {
                busy = false
                labeler.close()
            }
    }

    val take = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        val uri = pendingUri
        if (ok && uri != null) analyze(uri)
    }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) analyze(uri)
    }
    fun launchCamera() {
        val dir = File(context.cacheDir, "photos").apply { mkdirs() }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", File(dir, "meal.jpg"))
        pendingUri = uri
        take.launch(uri)
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) launchCamera()
    }

    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(
            onClick = {
                val has = ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
                if (has) launchCamera() else permission.launch(Manifest.permission.CAMERA)
            },
            modifier = Modifier.weight(1f),
        ) { Text(":camera: Foto de comida") }
        OutlinedButton(
            onClick = { pick.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
            modifier = Modifier.weight(1f),
        ) { Text(":image: Galería") }
    }

    if (busy) Muted("Analizando la foto…")
    val found = labels
    if (found != null && !busy) {
        val matches = remember(found, size) { FoodMatcher.match(found, diet, size) }
        GqCard {
            if (matches.isEmpty()) {
                Text("No reconocí la comida", fontWeight = FontWeight.Bold)
                Muted("Búscala por nombre abajo o agrégala a mano. Mejor luz y el plato completo ayudan.")
            } else {
                Text("Creo que es…", fontWeight = FontWeight.Bold)
                Muted("Elige el tamaño de tu porción y toca lo correcto. Es una estimación.")
                ChoiceChips(PortionSize.entries, size, { it.label }, { sizeName = it.name })
                matches.forEach { m ->
                    GqCard(onClick = { onAdd(m); labels = null }) {
                        Text("${m.food.name} · ${m.grams} g", fontWeight = FontWeight.SemiBold)
                        Muted("${m.macros.kcal.roundToInt()} kcal · P ${m.macros.proteinG.roundToInt()} g · seguridad ${(m.confidence * 100).roundToInt()}%")
                    }
                }
            }
        }
    }
}
