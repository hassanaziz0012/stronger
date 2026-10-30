package app.stronger.ui.charts

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.pow

data class ChartPoint(val x: Long, val y: Double, val label: String)

/** Rounds the axis to 3–4 friendly ticks. */
private fun niceTicks(min: Double, max: Double, count: Int = 3): List<Double> {
    val span = (max - min).takeIf { it > 0 } ?: (abs(max).takeIf { it > 0 } ?: 1.0)
    val raw = span / count
    val mag = 10.0.pow(floor(log10(raw)))
    val step = listOf(1.0, 2.0, 2.5, 5.0, 10.0).first { it * mag >= raw } * mag
    val lo = floor(min / step) * step
    val hi = ceil(max / step) * step
    return generateSequence(lo) { it + step }.takeWhile { it <= hi + step / 2 }.toList()
}

private fun DrawScope.tooltip(tm: TextMeasurer, text: String, anchor: Offset, style: TextStyle, bg: androidx.compose.ui.graphics.Color) {
    val layout = tm.measure(text, style)
    val pad = 8.dp.toPx()
    val w = layout.size.width + pad * 2
    val h = layout.size.height + pad * 1.2f
    val x = (anchor.x - w / 2).coerceIn(0f, size.width - w)
    val y = (anchor.y - h - 10.dp.toPx()).coerceAtLeast(0f)
    drawRoundRect(bg, Offset(x, y), Size(w, h), CornerRadius(8.dp.toPx()))
    drawText(layout, topLeft = Offset(x + pad, y + pad * 0.6f))
}

/**
 * Single-series line over time: one hue, 2dp line, 8dp markers, recessive grid, drag to inspect.
 */
@Composable
fun LineChart(
    points: List<ChartPoint>,
    formatY: (Double) -> String,
    formatX: (Long) -> String,
    modifier: Modifier = Modifier,
    height: Dp = 200.dp,
) {
    val c = MaterialTheme.colorScheme
    val tm = rememberTextMeasurer()
    val axisStyle = MaterialTheme.typography.labelSmall.copy(color = c.onSurfaceVariant)
    val tipStyle = MaterialTheme.typography.labelMedium.copy(color = c.inverseOnSurface)
    var selected by remember(points) { mutableStateOf<Int?>(null) }
    if (points.isEmpty()) return
    val ticks = remember(points) { niceTicks(points.minOf { it.y }, points.maxOf { it.y }) }

    Canvas(
        modifier.fillMaxWidth().height(height)
            .pointerInput(points) {
                fun pick(x: Float) {
                    val left = 40.dp.toPx(); val right = size.width - 12.dp.toPx()
                    val f = ((x - left) / (right - left)).coerceIn(0f, 1f)
                    val t = points.first().x + f * (points.last().x - points.first().x)
                    selected = points.indices.minByOrNull { abs(points[it].x - t) }
                }
                detectTapGestures { pick(it.x) }
            }
            .pointerInput(points) {
                detectDragGestures(
                    onDragStart = { o ->
                        val left = 40.dp.toPx(); val right = size.width - 12.dp.toPx()
                        val f = ((o.x - left) / (right - left)).coerceIn(0f, 1f)
                        val t = points.first().x + f * (points.last().x - points.first().x)
                        selected = points.indices.minByOrNull { abs(points[it].x - t) }
                    },
                ) { change, _ ->
                    val left = 40.dp.toPx(); val right = size.width - 12.dp.toPx()
                    val f = ((change.position.x - left) / (right - left)).coerceIn(0f, 1f)
                    val t = points.first().x + f * (points.last().x - points.first().x)
                    selected = points.indices.minByOrNull { abs(points[it].x - t) }
                }
            }
    ) {
        val left = 40.dp.toPx()
        val right = size.width - 12.dp.toPx()
        val top = 28.dp.toPx()
        val bottom = size.height - 20.dp.toPx()
        val yMin = ticks.first(); val yMax = ticks.last()
        val xMin = points.first().x; val xMax = points.last().x
        fun px(p: ChartPoint) = Offset(
            if (xMax == xMin) (left + right) / 2 else left + (p.x - xMin).toFloat() / (xMax - xMin) * (right - left),
            bottom - ((p.y - yMin) / (yMax - yMin).coerceAtLeast(1e-9)).toFloat() * (bottom - top),
        )
        ticks.forEach { t ->
            val y = bottom - ((t - yMin) / (yMax - yMin).coerceAtLeast(1e-9)).toFloat() * (bottom - top)
            drawLine(c.outlineVariant, Offset(left, y), Offset(right, y), 1.dp.toPx())
            val l = tm.measure(formatY(t), axisStyle)
            drawText(l, topLeft = Offset(left - l.size.width - 6.dp.toPx(), y - l.size.height / 2))
        }
        listOf(points.first().x, points.last().x).distinct().forEachIndexed { i, x ->
            val l = tm.measure(formatX(x), axisStyle)
            val xPos = if (i == 0) left else right - l.size.width
            drawText(l, topLeft = Offset(xPos, bottom + 4.dp.toPx()))
        }
        val offsets = points.map(::px)
        if (offsets.size > 1) {
            val path = Path().apply {
                moveTo(offsets[0].x, offsets[0].y)
                offsets.drop(1).forEach { lineTo(it.x, it.y) }
            }
            drawPath(path, c.primary, style = Stroke(2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
        }
        if (offsets.size <= 40) offsets.forEach {
            drawCircle(c.surfaceContainerLow, 5.dp.toPx(), it)
            drawCircle(c.primary, 4.dp.toPx(), it)
        }
        val sel = selected ?: points.lastIndex
        val o = offsets[sel]
        if (selected != null) {
            drawLine(c.outline, Offset(o.x, top), Offset(o.x, bottom), 1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 6f)))
        }
        drawCircle(c.surfaceContainerLow, 7.dp.toPx(), o)
        drawCircle(c.primary, 6.dp.toPx(), o)
        tooltip(tm, "${points[sel].label} · ${formatX(points[sel].x)}", o, tipStyle, c.inverseSurface)
    }
}

/** Single-series bars with an optional dashed goal line. Tap a bar to read it. */
@Composable
fun BarChart(
    values: List<Pair<String, Double>>,
    modifier: Modifier = Modifier,
    goal: Double? = null,
    formatValue: (Double) -> String = { it.toInt().toString() },
    height: Dp = 160.dp,
) {
    val c = MaterialTheme.colorScheme
    val tm = rememberTextMeasurer()
    val axisStyle = MaterialTheme.typography.labelSmall.copy(color = c.onSurfaceVariant)
    val tipStyle = MaterialTheme.typography.labelMedium.copy(color = c.inverseOnSurface)
    var selected by remember(values) { mutableStateOf<Int?>(null) }
    if (values.isEmpty()) return
    val max = maxOf(values.maxOf { it.second }, goal ?: 0.0, 1.0)

    Canvas(
        modifier.fillMaxWidth().height(height).pointerInput(values) {
            detectTapGestures { p ->
                val slot = size.width / values.size
                selected = (p.x / slot).toInt().coerceIn(0, values.lastIndex).takeIf { it != selected }
            }
        }
    ) {
        val top = 26.dp.toPx()
        val bottom = size.height - 18.dp.toPx()
        val slot = size.width / values.size
        val gap = 2.dp.toPx() + slot * 0.25f
        drawLine(c.outlineVariant, Offset(0f, bottom), Offset(size.width, bottom), 1.dp.toPx())
        values.forEachIndexed { i, (label, v) ->
            val h = ((v / max) * (bottom - top)).toFloat()
            val x = i * slot + gap / 2
            val w = slot - gap
            if (h > 0) {
                val r = 4.dp.toPx().coerceAtMost(h)
                val path = Path().apply {
                    addRoundRect(RoundRect(x, bottom - h, x + w, bottom, topLeftCornerRadius = CornerRadius(r), topRightCornerRadius = CornerRadius(r)))
                }
                drawPath(path, if (selected == null || selected == i) c.primary else c.primary.copy(alpha = 0.4f))
            }
            if (i % 2 == (values.size - 1) % 2) {
                val l = tm.measure(label, axisStyle)
                drawText(l, topLeft = Offset(x + w / 2 - l.size.width / 2, bottom + 3.dp.toPx()))
            }
        }
        goal?.let { g ->
            val y = bottom - ((g / max) * (bottom - top)).toFloat()
            drawLine(c.tertiary, Offset(0f, y), Offset(size.width, y), 1.5.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 8f)))
            val l = tm.measure("goal", axisStyle.copy(color = c.tertiary))
            drawText(l, topLeft = Offset(size.width - l.size.width, y - l.size.height - 2.dp.toPx()))
        }
        selected?.let { i ->
            val (label, v) = values[i]
            val h = ((v / max) * (bottom - top)).toFloat()
            tooltip(tm, "${formatValue(v)} · $label", Offset(i * slot + slot / 2, bottom - h), tipStyle, c.inverseSurface)
        }
    }
}
