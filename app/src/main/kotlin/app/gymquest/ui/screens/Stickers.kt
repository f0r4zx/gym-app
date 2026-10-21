package app.gymquest.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import app.gymquest.stickers.StickerStore
import app.gymquest.ui.Controller
import app.gymquest.ui.components.GqCard
import app.gymquest.ui.components.Muted
import app.gymquest.ui.components.PrimaryButton
import kotlinx.coroutines.launch
import java.io.File

/** Make stickers from your own pictures (works with no internet). Sending them in chat comes with the server. */
@Composable
fun StickersScreen(c: Controller, onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var files by remember { mutableStateOf(StickerStore.list(context)) }
    var caption by remember { mutableStateOf("") }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) {
            scope.launch {
                val made = StickerStore.create(context, uri, caption)
                if (made != null) {
                    files = StickerStore.list(context)
                    caption = ""
                    c.say("Sticker creado ✓")
                } else {
                    c.say("No pude leer esa imagen.")
                }
            }
        }
    }

    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        BackHeader("Mis stickers", onBack)
        GqCard {
            OutlinedTextField(caption, { caption = it.take(24) }, label = { Text("Texto del sticker (opcional)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            PrimaryButton("🖼 Crear desde una foto", { pick.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }, Modifier.fillMaxWidth())
            Muted("Se recorta en cuadrado y se guarda en tu teléfono. Podrás enviarlos en el chat cuando esté el servidor.")
        }
        if (files.isEmpty()) Muted("Aún no tienes stickers.")
        LazyVerticalGrid(columns = GridCells.Fixed(3), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(files, key = { it.name }) { f -> StickerCell(f) { StickerStore.delete(f); files = StickerStore.list(context) } }
        }
    }
}

@Composable
private fun StickerCell(file: File, onDelete: () -> Unit) {
    val bitmap = remember(file) { android.graphics.BitmapFactory.decodeFile(file.path)?.asImageBitmap() }
    if (bitmap != null) {
        Image(bitmap, contentDescription = "Sticker", modifier = Modifier.fillMaxWidth().aspectRatio(1f).clickable { onDelete() })
    }
}
