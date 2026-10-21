package app.gymquest.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.gymquest.data.AppState
import app.gymquest.ui.Controller
import app.gymquest.ui.components.GqCard
import app.gymquest.ui.components.Muted

@Composable
private fun ComingSoon(title: String, emoji: String, lines: List<String>) {
    Column(
        Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("$emoji $title", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.ExtraBold)
        GqCard {
            lines.forEach { Text("• $it") }
            Muted("Esta pantalla se está construyendo.")
        }
    }
}

@Composable
fun MapScreen(state: AppState, c: Controller) =
    ComingSoon("Mapa", "🗺️", listOf("GPS para caminar, correr o pedalear", "Mapas 3D y descargas sin internet", "Conquista territorio cerrando circuitos"))

@Composable
fun CommunityScreen(state: AppState, c: Controller) =
    ComingSoon("Comunidad", "👥", listOf("Amigos, gremios y rankings", "Duelos y guerras", "Mercado y chat"))
