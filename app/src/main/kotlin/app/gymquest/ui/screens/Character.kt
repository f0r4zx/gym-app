package app.gymquest.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import app.gymquest.ui.components.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.gymquest.core.avatar.AvatarLook
import app.gymquest.core.avatar.AvatarMorph
import app.gymquest.core.avatar.CharacterStats
import app.gymquest.core.avatar.Cosmetics
import app.gymquest.core.avatar.CosmeticItem
import app.gymquest.core.avatar.HairStyle
import app.gymquest.core.avatar.Rarity
import app.gymquest.core.avatar.SkinTone
import app.gymquest.core.avatar.Slot
import app.gymquest.core.avatar.Stat
import app.gymquest.core.avatar.Unlock
import app.gymquest.core.game.Achievements
import app.gymquest.data.Actions
import app.gymquest.data.AppState
import app.gymquest.data.Derived
import app.gymquest.data.enumOr
import app.gymquest.ui.Controller
import app.gymquest.ui.components.AvatarView
import app.gymquest.ui.components.Bar
import app.gymquest.ui.components.ChoiceChips
import app.gymquest.ui.components.GqCard
import app.gymquest.ui.components.Muted
import app.gymquest.ui.components.Pill
import app.gymquest.ui.components.SectionTitle
import app.gymquest.ui.components.skinColor
import app.gymquest.ui.theme.Gold
import app.gymquest.ui.theme.Mint
import app.gymquest.ui.theme.Orange
import app.gymquest.ui.theme.Sky

@Composable
fun CharacterScreen(state: AppState, c: Controller, onBack: () -> Unit) {
    val profile = Derived.profile(state) ?: return
    val now = System.currentTimeMillis()
    val stats = Derived.stats(state)
    val values = stats.all(now)
    val shape = AvatarMorph.shape(profile.sex, profile.heightCm, profile.weightKg, null, values)
    val outfit = Derived.outfit(state, c.today)
    val look = AvatarLook(
        skin = enumOr(state.settings.skinTone, SkinTone.TONE_3),
        hair = enumOr(state.settings.hairStyle, HairStyle.SHORT),
        hairColor = state.settings.hairColor,
        outfit = outfit,
    )
    val owned = Derived.ownedItems(state, c.today)

    Column(
        Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        BackHeader("Mi personaje", onBack)

        GqCard {
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                AvatarView(shape, look, Modifier.height(280.dp).fillMaxWidth())
            }
            Text(profile.name, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, modifier = Modifier.align(Alignment.CenterHorizontally))
            Muted(AvatarMorph.describe(shape), Modifier.align(Alignment.CenterHorizontally))
            Row(Modifier.align(Alignment.CenterHorizontally), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Pill(":bolt: Poder ${stats.power(now)}", Gold.copy(alpha = 0.25f))
                Pill("Nivel ${Derived.level(state)}")
            }
            Muted("Tu cuerpo cambia con tu peso real y con tus entrenamientos. Las estadísticas solo suben ejercitándote de verdad.", Modifier.align(Alignment.CenterHorizontally))
        }

        if (state.weights.size >= 2) {
            SectionTitle("Tu evolución")
            GqCard {
                val shapes = remember(state.weights, values) {
                    state.weights.sortedBy { it.epochDay }.map { w ->
                        AvatarMorph.shape(profile.sex, profile.heightCm, w.kg, null, values)
                    }
                }
                val frames = remember(shapes) { AvatarMorph.timeline(shapes, 40) }
                var pos by rememberSaveable { mutableFloatStateOf(1f) }
                val shown = frames[(pos * (frames.size - 1)).toInt().coerceIn(0, frames.size - 1)]
                val dayIndex = (pos * (state.weights.size - 1)).toInt().coerceIn(0, state.weights.size - 1)
                val entry = state.weights.sortedBy { it.epochDay }[dayIndex]
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    AvatarView(shown, look, Modifier.height(180.dp).fillMaxWidth())
                }
                Slider(value = pos, onValueChange = { pos = it })
                Muted("${java.time.LocalDate.ofEpochDay(entry.epochDay)} · ${"%.1f".format(entry.kg)} kg · ${AvatarMorph.describe(shown)}")
            }
        }

        SectionTitle("Estadísticas")
        GqCard {
            Stat.entries.forEach { st ->
                val v = values.getValue(st)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("${st.label}  ${CharacterStats.rank(v).letter}", fontWeight = FontWeight.SemiBold)
                    Text("$v / 100")
                }
                Bar(v / 100.0, color = statColor(st), height = 8.dp)
            }
            Muted("Se desvanecen despacio si dejas de entrenar (vida media de 60 días).")
        }

        SectionTitle("Apariencia")
        GqCard {
            Text("Tono de piel", fontWeight = FontWeight.SemiBold)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                SkinTone.entries.forEach { t ->
                    Box(
                        Modifier.size(34.dp).clip(CircleShape).background(skinColor(t))
                            .clickable { c.change { it.copy(settings = it.settings.copy(skinTone = t.name)) } },
                    ) {
                        if (t == look.skin) Text(":check:", Modifier.align(Alignment.Center), color = Color.Black, fontWeight = FontWeight.Bold)
                    }
                }
            }
            Text("Peinado", fontWeight = FontWeight.SemiBold)
            ChoiceChips(HairStyle.entries, look.hair, { hairLabel(it) }, { h -> c.change { it.copy(settings = it.settings.copy(hairStyle = h.name)) } })
            Text("Color de pelo", fontWeight = FontWeight.SemiBold)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                HAIR_COLORS.forEach { col ->
                    Box(
                        Modifier.size(34.dp).clip(CircleShape).background(Color(col or (0xFF shl 24)))
                            .clickable { c.change { it.copy(settings = it.settings.copy(hairColor = col)) } },
                    ) {
                        if (col == look.hairColor) Text(":check:", Modifier.align(Alignment.Center), color = Color.White, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }

        SectionTitle("Equipo")
        Slot.entries.forEach { slot ->
            val items = Cosmetics.all.filter { it.slot == slot && it.id in owned }
            if (items.isEmpty()) return@forEach
            GqCard {
                Text(slot.label, fontWeight = FontWeight.Bold)
                items.forEach { item ->
                    val on = outfit.equipped[slot] == item.id
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(item.name)
                            Muted(item.rarity.label, Modifier)
                        }
                        if (on) {
                            OutlinedButton(onClick = { c.change { Actions.unequip(it, slot.name) } }) { Text("Quitar") }
                        } else {
                            OutlinedButton(onClick = { c.change { Actions.equip(it, item.id, c.today) } }) { Text("Ponerme") }
                        }
                    }
                }
            }
        }

        SectionTitle("Tienda  ·  :coin: ${state.game.coins}")
        val forSale = Cosmetics.shopItems().filter { it.id !in owned }
        if (forSale.isEmpty()) Muted("Ya tienes todo lo de la tienda.")
        forSale.forEach { item -> ShopRow(item, state.game.coins) { c.change { s -> Actions.buy(s, item.id, c.today) ?: s }; c.say("¡Compraste ${item.name}!") } }

        val locked = Cosmetics.all.filter { it.id !in owned && it.unlock !is Unlock.Shop }
        if (locked.isNotEmpty()) {
            SectionTitle("Por desbloquear")
            GqCard {
                locked.forEach { item ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(":lock: ${item.name}", Modifier.weight(1f))
                        Muted(unlockText(item))
                    }
                }
            }
        }

        SectionTitle("Logros")
        val ps = Derived.playerStats(state, c.today)
        GqCard {
            Achievements.all.forEach { a ->
                val done = a.id in state.game.achievements
                Text("${if (done) ":trophy:" else ":square:"} ${a.name}", fontWeight = if (done) FontWeight.Bold else FontWeight.Normal)
                Muted(a.description)
                if (!done) Bar(a.fraction(ps), color = Orange, height = 6.dp)
            }
        }
    }
}

@Composable
fun BackHeader(title: String, onBack: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = onBack) { Text(":chevron_left:") }
        Text(title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.ExtraBold)
    }
}

@Composable
private fun ShopRow(item: CosmeticItem, coins: Long, onBuy: () -> Unit) {
    val price = (item.unlock as? Unlock.Shop)?.price ?: return
    GqCard {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(item.name, fontWeight = FontWeight.SemiBold)
                Muted("${item.slot.label} · ${item.rarity.label}")
            }
            OutlinedButton(onClick = onBuy, enabled = coins >= price) { Text(":coin: $price") }
        }
    }
}

private fun unlockText(item: CosmeticItem): String = when (val u = item.unlock) {
    is Unlock.Level -> "Nivel ${u.level}"
    is Unlock.Boss -> "Vence a ${app.gymquest.core.game.Bosses.byId(u.bossId)?.name ?: "un jefe"}"
    is Unlock.Achievement -> "Logro: ${Achievements.byId(u.id)?.name ?: u.id}"
    is Unlock.Streak -> "Racha de ${u.days} días"
    is Unlock.Territory -> "${u.cells} zonas conquistadas"
    is Unlock.WarWins -> "${u.wins} guerras ganadas"
    is Unlock.Shop -> "Tienda"
}

private fun statColor(st: Stat): Color = when (st) {
    Stat.STRENGTH -> Orange
    Stat.ENDURANCE -> Sky
    Stat.SPEED -> Gold
    Stat.AGILITY -> Mint
    Stat.VITALITY -> Color(0xFFFF6B9A)
}

private fun hairLabel(h: HairStyle): String = when (h) {
    HairStyle.SHORT -> "Corto"
    HairStyle.BUZZ -> "Rapado"
    HairStyle.LONG -> "Largo"
    HairStyle.PONYTAIL -> "Cola"
    HairStyle.CURLY -> "Rizado"
    HairStyle.BALD -> "Calvo"
    HairStyle.BRAIDS -> "Trenzas"
    HairStyle.AFRO -> "Afro"
}

private val HAIR_COLORS = listOf(0x2B1B12, 0x6B4423, 0xC69C5C, 0xE8D18A, 0x9A2A2A, 0x808080, 0x1F6FEB, 0xC13584)

@Suppress("unused")
private val rarityOrder = Rarity.entries
