package app.stronger.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.stronger.data.BodyPart

/** Each body part gets its own shape from the M3 shape library, so lists scan by silhouette. */
@Composable
fun bodyPartShape(part: BodyPart): Shape = when (part) {
    BodyPart.CHEST -> MaterialShapes.Cookie4Sided
    BodyPart.BACK -> MaterialShapes.Clover4Leaf
    BodyPart.SHOULDERS -> MaterialShapes.Sunny
    BodyPart.ARMS -> MaterialShapes.Pill
    BodyPart.LEGS -> MaterialShapes.Arch
    BodyPart.CORE -> MaterialShapes.Cookie6Sided
    BodyPart.FULL_BODY -> MaterialShapes.Flower
    BodyPart.CARDIO -> MaterialShapes.Heart
    BodyPart.OLYMPIC -> MaterialShapes.SoftBurst
    BodyPart.OTHER -> MaterialShapes.Circle
}.toShape()

@Composable
fun bodyPartColors(part: BodyPart): Pair<Color, Color> {
    val c = MaterialTheme.colorScheme
    return when (part) {
        BodyPart.CHEST, BodyPart.LEGS, BodyPart.OLYMPIC -> c.primaryContainer to c.onPrimaryContainer
        BodyPart.BACK, BodyPart.CORE, BodyPart.OTHER -> c.secondaryContainer to c.onSecondaryContainer
        else -> c.tertiaryContainer to c.onTertiaryContainer
    }
}

@Composable
fun ExerciseAvatar(name: String, part: BodyPart, modifier: Modifier = Modifier, size: Dp = 48.dp) {
    val (bg, fg) = bodyPartColors(part)
    Box(
        modifier.size(size).clip(bodyPartShape(part)).background(bg),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            name.firstOrNull { it.isLetterOrDigit() }?.uppercase() ?: "?",
            style = when {
                size >= 64.dp -> MaterialTheme.typography.headlineSmallEmphasized
                size >= 48.dp -> MaterialTheme.typography.titleMediumEmphasized
                else -> MaterialTheme.typography.labelLargeEmphasized
            },
            color = fg,
        )
    }
}

@Composable
fun SectionHeader(text: String, modifier: Modifier = Modifier, trailing: @Composable () -> Unit = {}) {
    Row(
        modifier.fillMaxWidth().padding(start = 24.dp, end = 12.dp, top = 20.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text, style = MaterialTheme.typography.titleLargeEmphasized, modifier = Modifier.weight(1f))
        trailing()
    }
}

@Composable
fun EmptyState(
    icon: ImageVector,
    title: String,
    body: String,
    modifier: Modifier = Modifier,
    action: @Composable () -> Unit = {},
) {
    Column(
        modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 40.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Surface(
            shape = MaterialShapes.Cookie9Sided.toShape(),
            color = MaterialTheme.colorScheme.secondaryContainer,
            modifier = Modifier.size(96.dp),
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(icon, null, Modifier.size(40.dp), tint = MaterialTheme.colorScheme.onSecondaryContainer)
            }
        }
        Spacer(Modifier.height(20.dp))
        Text(title, style = MaterialTheme.typography.titleLargeEmphasized, textAlign = TextAlign.Center)
        Spacer(Modifier.height(8.dp))
        Text(
            body, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(20.dp))
        action()
    }
}

/** Big number over a small label, used in stat rows. */
@Composable
fun Stat(value: String, label: String, modifier: Modifier = Modifier, color: Color = MaterialTheme.colorScheme.onSurface) {
    // A trailing unit ("84.4k kg") is set smaller so the number keeps the room.
    val last = value.substringAfterLast(' ')
    val hasUnit = ' ' in value && last.all { it.isLetter() }
    val number = if (hasUnit) value.substringBeforeLast(' ') else value
    val unit = if (hasUnit) " $last" else ""
    val unitStyle = MaterialTheme.typography.labelLarge.toSpanStyle()
    Column(modifier, horizontalAlignment = Alignment.Start) {
        Text(
            buildAnnotatedString {
                append(number)
                withStyle(unitStyle) { append(unit) }
            },
            style = MaterialTheme.typography.headlineSmallEmphasized, color = color, maxLines = 1, softWrap = false,
        )
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
    }
}

@Composable
fun StatRow(vararg stats: Pair<String, String>, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        stats.forEach { (v, l) -> Stat(v, l, Modifier.weight(1f)) }
    }
}

@Composable
fun Dot(color: Color, size: Dp = 6.dp) {
    Box(Modifier.size(size).clip(MaterialShapes.Circle.toShape()).background(color))
}

@Composable
fun HSpace(w: Dp) = Spacer(Modifier.width(w))

@Composable
fun VSpace(h: Dp) = Spacer(Modifier.height(h))
