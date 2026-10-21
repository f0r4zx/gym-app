package app.gymquest.ui.components

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalTextStyle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.foundation.text.InlineTextContent

/** Emoji and pictographic symbols. The app uses its own line icons ([Ico]) instead, so none of these are ever shown. */
private val PICTOGRAPHS = Regex("[\\u2300-\\u23FF\\u25A0-\\u25FF\\u2600-\\u27BF\\u2B00-\\u2BFF\\uFE0F\\u200D]|[\\uD83C-\\uD83E][\\uDC00-\\uDFFF]")
private val TOKEN = Regex(":([a-z_]+):")

/** Removes emoji from [text] (system messages, texts that come from the shared game logic...). */
fun stripEmoji(text: String): String = PICTOGRAPHS.replace(text, "").replace("  ", " ").trim()

/** [text] as plain words: icon tokens such as `:coin:` and any emoji are removed (for snackbars and shared text). */
fun plainText(text: String): String =
    stripEmoji(TOKEN.replace(text) { if (Ico.byName.containsKey(it.groupValues[1])) "" else it.value })

private class Prepared(val plain: String, val annotated: AnnotatedString?, val icons: Set<String>)

private fun prepare(raw: String): Prepared {
    val tokens = TOKEN.findAll(raw).filter { Ico.byName.containsKey(it.groupValues[1]) }.toList()
    if (tokens.isEmpty()) return Prepared(PICTOGRAPHS.replace(raw, "").trimStart(), null, emptySet())
    val used = mutableSetOf<String>()
    val built = buildAnnotatedString {
        var last = 0
        for (t in tokens) {
            append(PICTOGRAPHS.replace(raw.substring(last, t.range.first), ""))
            val name = t.groupValues[1]
            used += name
            appendInlineContent(name, " ")
            last = t.range.last + 1
        }
        append(PICTOGRAPHS.replace(raw.substring(last), ""))
    }
    return Prepared("", built, used)
}

/**
 * The app's text. It works like Material's Text, plus: writing `:flame:` inside the string draws the
 * flame icon right there, sized and coloured like the text; and any emoji left in the string is dropped.
 */
@Composable
fun Text(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified,
    fontSize: TextUnit = TextUnit.Unspecified,
    fontWeight: FontWeight? = null,
    textAlign: TextAlign? = null,
    lineHeight: TextUnit = TextUnit.Unspecified,
    overflow: TextOverflow = TextOverflow.Clip,
    maxLines: Int = Int.MAX_VALUE,
    style: TextStyle = LocalTextStyle.current,
) {
    val prepared = remember(text) { prepare(text) }
    val annotated = prepared.annotated
    if (annotated == null) {
        androidx.compose.material3.Text(
            text = prepared.plain, modifier = modifier, color = color, fontSize = fontSize, fontWeight = fontWeight,
            textAlign = textAlign, lineHeight = lineHeight, overflow = overflow, maxLines = maxLines, style = style,
        )
        return
    }
    val tint = color.takeOrUnspecified(style.color)
    val inline = prepared.icons.associateWith { name ->
        InlineTextContent(Placeholder(1.2.em, 1.2.em, PlaceholderVerticalAlign.TextCenter)) {
            Icon(Ico.byName.getValue(name), contentDescription = null, modifier = Modifier.fillMaxSize(), tint = if (tint == Color.Unspecified) LocalContentColor.current else tint)
        }
    }
    androidx.compose.material3.Text(
        text = annotated, modifier = modifier, color = color, fontSize = fontSize, fontWeight = fontWeight,
        textAlign = textAlign, lineHeight = lineHeight, overflow = overflow, maxLines = maxLines,
        inlineContent = inline, style = style,
    )
}

private fun Color.takeOrUnspecified(fallback: Color): Color = if (this != Color.Unspecified) this else fallback

/** A standalone icon in the app's style. */
@Composable
fun Ic(icon: ImageVector, modifier: Modifier = Modifier, size: Dp = 20.dp, tint: Color = LocalContentColor.current) {
    Icon(icon, contentDescription = null, modifier = modifier.size(size), tint = tint)
}
