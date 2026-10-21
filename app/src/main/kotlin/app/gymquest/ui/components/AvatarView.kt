package app.gymquest.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import app.gymquest.core.avatar.AvatarLook
import app.gymquest.core.avatar.BodyShape
import app.gymquest.core.avatar.HairStyle
import app.gymquest.core.avatar.SkinTone
import app.gymquest.core.avatar.Slot
import kotlin.math.absoluteValue

fun skinColor(tone: SkinTone): Color = when (tone) {
    SkinTone.TONE_1 -> Color(0xFFFCE0CF)
    SkinTone.TONE_2 -> Color(0xFFF3C9A5)
    SkinTone.TONE_3 -> Color(0xFFD9A06E)
    SkinTone.TONE_4 -> Color(0xFFB87A4B)
    SkinTone.TONE_5 -> Color(0xFF8A5A36)
    SkinTone.TONE_6 -> Color(0xFF5B3A24)
}

private fun itemColor(id: String): Color = when (id) {
    "starter_shirt" -> Color(0xFF4DA3FF)
    "starter_shorts" -> Color(0xFF2B3A67)
    "starter_shoes" -> Color(0xFFEDEDED)
    "headband" -> Color(0xFFFF5C5C)
    "hoodie" -> Color(0xFF6B7280)
    "tank_top" -> Color(0xFFFFC53D)
    "gold_chain" -> Color(0xFFFFD54F)
    "sunglasses" -> Color(0xFF111111)
    else -> Color.hsv((id.hashCode().absoluteValue % 360).toFloat(), 0.55f, 0.85f)
}

private fun auraColor(id: String): Color = when (id) {
    "aura_blue" -> Color(0xFF4DA3FF)
    "aura_gold" -> Color(0xFFFFC53D)
    "flame_aura" -> Color(0xFFFF6A00)
    "diamond_aura" -> Color(0xFF7DF9FF)
    "neon_aura" -> Color(0xFFFF2BD6)
    else -> Color.White
}

/** The player's character: a stylised figure whose body follows the real measurements and stats. */
@Composable
fun AvatarView(shape: BodyShape, look: AvatarLook, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val s = size.height / 320f
        val cx = size.width / 2f
        val worn = look.outfit.equipped
        val skin = skinColor(look.skin)

        worn[Slot.AURA]?.let { id ->
            val c = auraColor(id)
            drawCircle(
                brush = Brush.radialGradient(listOf(c.copy(alpha = 0.55f), Color.Transparent), center = Offset(cx, 175 * s), radius = 150 * s),
                radius = 150 * s, center = Offset(cx, 175 * s),
            )
        }

        // A taller person is drawn a little bigger, anchored at the feet.
        val heightScale = 0.92f + shape.height.toFloat() * 0.16f
        scale(heightScale, Offset(cx, size.height)) {
            drawFigure(s, cx, shape, look, worn, skin)
        }
    }
}

private fun DrawScope.drawFigure(s: Float, cx: Float, shape: BodyShape, look: AvatarLook, worn: Map<Slot, String>, skin: Color) {
    val shoulderY = 112 * s
    val hipY = 196 * s
    val footY = 300 * s
    val shoulderW = shape.shoulders.toFloat() * 130 * s
    val waistW = shape.waist.toFloat() * 130 * s
    val limb = shape.limbThickness.toFloat()

    // Back item (cape, wings...) sits behind the body.
    worn[Slot.BACK]?.let { id ->
        val p = Path().apply {
            moveTo(cx - shoulderW / 2, shoulderY)
            lineTo(cx + shoulderW / 2, shoulderY)
            lineTo(cx + shoulderW * 0.75f, hipY + 70 * s)
            lineTo(cx - shoulderW * 0.75f, hipY + 70 * s)
            close()
        }
        drawPath(p, itemColor(id).copy(alpha = 0.85f))
    }

    // Legs (skin), then shorts/pants over the upper part, then shoes.
    val legTop = hipY - 6 * s
    val legX = waistW * 0.26f
    val legW = limb * 36 * s
    for (side in listOf(-1f, 1f)) {
        drawLine(skin, Offset(cx + side * legX, legTop), Offset(cx + side * (legX + 4 * s), footY - 10 * s), legW, StrokeCap.Round)
    }
    val pants = itemColor(worn[Slot.LEGS] ?: "starter_shorts")
    if (worn[Slot.LEGS] != null) {
        for (side in listOf(-1f, 1f)) {
            drawLine(pants, Offset(cx + side * legX, legTop), Offset(cx + side * (legX + 2 * s), legTop + 62 * s), legW * 1.05f, StrokeCap.Round)
        }
    }
    val shoeColor = worn[Slot.FEET]?.let { itemColor(it) } ?: Color(0xFF444444)
    for (side in listOf(-1f, 1f)) {
        drawRoundRect(
            shoeColor, Offset(cx + side * (legX + 4 * s) - 17 * s + side * 4 * s, footY - 12 * s), Size(34 * s, 16 * s),
            CornerRadius(8 * s, 8 * s),
        )
    }

    // Arms.
    val armW = limb * 30 * s
    val shirt = worn[Slot.TORSO]?.let { itemColor(it) } ?: skin
    for (side in listOf(-1f, 1f)) {
        val shoulder = Offset(cx + side * (shoulderW / 2 - 2 * s), shoulderY + 10 * s)
        val hand = Offset(cx + side * (shoulderW / 2 + 12 * s), shoulderY + 92 * s)
        drawLine(skin, shoulder, hand, armW, StrokeCap.Round)
        val sleeve = Offset(shoulder.x + (hand.x - shoulder.x) * 0.35f, shoulder.y + (hand.y - shoulder.y) * 0.35f)
        drawLine(shirt, shoulder, sleeve, armW * 1.05f, StrokeCap.Round)
        worn[Slot.HANDS]?.let { drawCircle(itemColor(it), armW * 0.62f, hand) }
    }

    // Torso, with the belly pushing out when there is more volume.
    val torso = Path().apply {
        moveTo(cx - shoulderW / 2, shoulderY)
        lineTo(cx + shoulderW / 2, shoulderY)
        lineTo(cx + waistW / 2, hipY)
        lineTo(cx - waistW / 2, hipY)
        close()
    }
    drawPath(torso, shirt)
    val belly = shape.belly.toFloat()
    if (belly > 0.02f) {
        val bw = waistW * (1f + belly * 0.45f)
        drawOval(shirt, Offset(cx - bw / 2, hipY - 48 * s), Size(bw, 56 * s))
    }
    worn[Slot.TORSO]?.let { id ->
        if (id == "gold_chain") drawArc(Color(0xFFFFD54F), 10f, 160f, false, Offset(cx - 16 * s, shoulderY - 2 * s), Size(32 * s, 28 * s), style = Stroke(3 * s))
    }

    // Neck and head.
    drawRect(skin, Offset(cx - 8 * s, shoulderY - 14 * s), Size(16 * s, 18 * s))
    val headC = Offset(cx, 78 * s)
    val headR = 27 * s
    drawHair(look.hair, Color(look.hairColor or (0xFF shl 24)), headC, headR, s)
    drawCircle(skin, headR, headC)
    drawHairFront(look.hair, Color(look.hairColor or (0xFF shl 24)), headC, headR)
    drawCircle(Color(0xFF222222), 2.8f * s, Offset(cx - 9 * s, headC.y - 1 * s))
    drawCircle(Color(0xFF222222), 2.8f * s, Offset(cx + 9 * s, headC.y - 1 * s))
    drawArc(Color(0xFF7A2E2E), 20f, 140f, false, Offset(cx - 8 * s, headC.y + 4 * s), Size(16 * s, 12 * s), style = Stroke(2 * s, cap = StrokeCap.Round))

    worn[Slot.HEAD]?.let { id ->
        val c = itemColor(id)
        if (id == "sunglasses") {
            drawRoundRect(c, Offset(cx - 18 * s, headC.y - 7 * s), Size(36 * s, 11 * s), CornerRadius(5 * s, 5 * s))
        } else if (id == "headband") {
            drawRect(c, Offset(cx - headR, headC.y - headR * 0.55f), Size(headR * 2, 7 * s))
        } else {
            drawArc(c, 180f, 180f, true, Offset(headC.x - headR - 2 * s, headC.y - headR - 6 * s), Size((headR + 2 * s) * 2, (headR + 2 * s) * 2))
        }
    }
}

private fun DrawScope.drawHair(style: HairStyle, color: Color, c: Offset, r: Float, s: Float) {
    when (style) {
        HairStyle.AFRO -> drawCircle(color, r * 1.45f, Offset(c.x, c.y - 6 * s))
        HairStyle.LONG -> drawRoundRect(color, Offset(c.x - r * 1.1f, c.y - r), Size(r * 2.2f, r * 2.9f), CornerRadius(r, r))
        HairStyle.PONYTAIL -> drawCircle(color, r * 0.55f, Offset(c.x + r * 1.05f, c.y + r * 0.2f))
        HairStyle.BRAIDS -> for (side in listOf(-1f, 1f)) drawLine(color, Offset(c.x + side * r * 0.95f, c.y), Offset(c.x + side * r * 1.1f, c.y + r * 1.9f), 7 * s, StrokeCap.Round)
        else -> Unit
    }
}

private fun DrawScope.drawHairFront(style: HairStyle, color: Color, c: Offset, r: Float) {
    when (style) {
        HairStyle.BALD -> Unit
        HairStyle.BUZZ -> drawArc(color.copy(alpha = 0.55f), 180f, 180f, true, Offset(c.x - r, c.y - r), Size(r * 2, r * 1.15f))
        HairStyle.CURLY -> {
            for (i in 0..4) drawCircle(color, r * 0.32f, Offset(c.x - r * 0.8f + i * r * 0.4f, c.y - r * 0.85f))
        }
        else -> drawArc(color, 180f, 180f, true, Offset(c.x - r, c.y - r), Size(r * 2, r * 1.3f))
    }
}
