package app.gymquest.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** A simple line chart with dots. [smooth] is an optional second, calmer line drawn under the raw one. */
@Composable
fun LineChart(
    values: List<Double>,
    modifier: Modifier = Modifier,
    smooth: List<Double>? = null,
    color: Color = MaterialTheme.colorScheme.primary,
    height: Dp = 150.dp,
) {
    if (values.size < 2) return
    val all = values + (smooth ?: emptyList())
    val lo = all.min()
    val hi = all.max()
    val pad = ((hi - lo) * 0.1).coerceAtLeast(0.5)
    val track = MaterialTheme.colorScheme.surfaceVariant
    Canvas(modifier.fillMaxWidth().height(height)) {
        val w = size.width
        val h = size.height
        fun x(i: Int, n: Int) = w * i / (n - 1).coerceAtLeast(1)
        fun y(v: Double) = (h - ((v - (lo - pad)) / ((hi + pad) - (lo - pad))).toFloat() * h)
        for (g in 0..3) drawLine(track, Offset(0f, h * g / 3f), Offset(w, h * g / 3f), 1f)
        smooth?.let { s ->
            val p = Path()
            s.forEachIndexed { i, v -> if (i == 0) p.moveTo(x(i, s.size), y(v)) else p.lineTo(x(i, s.size), y(v)) }
            drawPath(p, color, style = Stroke(5f, cap = StrokeCap.Round))
        }
        values.forEachIndexed { i, v -> drawCircle(color.copy(alpha = if (smooth != null) 0.45f else 1f), 6f, Offset(x(i, values.size), y(v))) }
        if (smooth == null) {
            val p = Path()
            values.forEachIndexed { i, v -> if (i == 0) p.moveTo(x(i, values.size), y(v)) else p.lineTo(x(i, values.size), y(v)) }
            drawPath(p, color, style = Stroke(4f, cap = StrokeCap.Round))
        }
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = androidx.compose.foundation.layout.Arrangement.SpaceBetween) {
        Text("%.1f".format(lo), fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text("%.1f".format(hi), fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Vertical bars with a label under each. */
@Composable
fun BarChart(
    values: List<Double>,
    labels: List<String>,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.primary,
    height: Dp = 130.dp,
) {
    if (values.isEmpty()) return
    val top = (values.max()).coerceAtLeast(1.0)
    val track = MaterialTheme.colorScheme.surfaceVariant
    Canvas(modifier.fillMaxWidth().height(height)) {
        val slot = size.width / values.size
        val bar = slot * 0.62f
        values.forEachIndexed { i, v ->
            val bh = (v / top).toFloat() * size.height
            val x = i * slot + (slot - bar) / 2
            drawRoundRect(track, Offset(x, 0f), Size(bar, size.height), CornerRadius(8f, 8f))
            if (bh > 0f) drawRoundRect(color, Offset(x, size.height - bh), Size(bar, bh), CornerRadius(8f, 8f))
        }
    }
    Row(Modifier.fillMaxWidth()) {
        labels.forEach { Text(it, Modifier.weight(1f), fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = androidx.compose.ui.text.style.TextAlign.Center) }
    }
}
