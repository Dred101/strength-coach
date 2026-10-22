package app.repcoach.ui

import androidx.compose.foundation.Canvas
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.repcoach.core.ChartPoint
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt

internal val RecordGold = Color(0xFFF5B841)

private val shortDate = DateTimeFormatter.ofPattern("d MMM", Locale.UK)

private fun axisLabel(v: Double): String = if (v >= 1000) "%.1fk".format(Locale.UK, v / 1000) else v.roundToInt().toString()

/**
 * A line chart over time with a labelled value axis, start and end dates, and gold dots on the
 * sessions that set a new best.
 */
@Composable
fun ProgressLineChart(points: List<ChartPoint>, modifier: Modifier) {
    val line = MaterialTheme.colorScheme.primary
    val grid = MaterialTheme.colorScheme.outlineVariant
    val labelColor = MaterialTheme.colorScheme.onSurfaceVariant
    val measurer = rememberTextMeasurer()
    val labelStyle = TextStyle(fontSize = 11.sp, color = labelColor)

    Canvas(modifier) {
        if (points.size < 2) return@Canvas
        val rawMin = points.minOf { it.value }
        val rawMax = points.maxOf { it.value }
        val pad = ((rawMax - rawMin) * 0.1).coerceAtLeast(1.0)
        val min = floor(rawMin - pad)
        val max = ceil(rawMax + pad)

        val axisWidth = 40.dp.toPx()
        val dateHeight = 18.dp.toPx()
        val left = axisWidth
        val chartW = size.width - left
        val chartH = size.height - dateHeight
        val t0 = points.first().millis.toDouble()
        val span = (points.last().millis - points.first().millis).coerceAtLeast(1).toDouble()

        fun x(ms: Long) = (left + (ms - t0) / span * chartW).toFloat()
        fun y(v: Double) = (chartH - (v - min) / (max - min) * chartH).toFloat()

        // Grid lines with value labels.
        for (i in 0..3) {
            val v = min + (max - min) * i / 3
            val yy = y(v)
            drawLine(grid, Offset(left, yy), Offset(size.width, yy), strokeWidth = 1f)
            val text = measurer.measure(axisLabel(v), labelStyle)
            drawText(text, topLeft = Offset(left - text.size.width - 6.dp.toPx(), yy - text.size.height / 2f))
        }

        val path = Path()
        points.forEachIndexed { i, p -> if (i == 0) path.moveTo(x(p.millis), y(p.value)) else path.lineTo(x(p.millis), y(p.value)) }
        drawPath(path, line, style = Stroke(width = 2.5.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))

        for (p in points) {
            if (p.isRecord) drawCircle(RecordGold, radius = 4.5.dp.toPx(), center = Offset(x(p.millis), y(p.value)))
            else drawCircle(line, radius = 2.5.dp.toPx(), center = Offset(x(p.millis), y(p.value)))
        }

        fun date(ms: Long) = Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()).format(shortDate)
        val first = measurer.measure(date(points.first().millis), labelStyle)
        val last = measurer.measure(date(points.last().millis), labelStyle)
        drawText(first, topLeft = Offset(left, chartH + 4.dp.toPx()))
        drawText(last, topLeft = Offset(size.width - last.size.width, chartH + 4.dp.toPx()))
    }
}

/** Vertical bars with a label under each, e.g. hard sets per week. */
@Composable
fun BarChart(bars: List<Pair<String, Int>>, modifier: Modifier) {
    val bar = MaterialTheme.colorScheme.primary
    val empty = MaterialTheme.colorScheme.outlineVariant
    val labelColor = MaterialTheme.colorScheme.onSurfaceVariant
    val measurer = rememberTextMeasurer()
    val labelStyle = TextStyle(fontSize = 10.sp, color = labelColor)

    Canvas(modifier) {
        if (bars.isEmpty()) return@Canvas
        val labelHeight = 16.dp.toPx()
        val valueHeight = 14.dp.toPx()
        val chartH = size.height - labelHeight - valueHeight
        val slot = size.width / bars.size
        val barW = slot * 0.6f
        val max = bars.maxOf { it.second }.coerceAtLeast(1)

        bars.forEachIndexed { i, (label, value) ->
            val h = (value.toFloat() / max * chartH).coerceAtLeast(2f)
            val left = i * slot + (slot - barW) / 2
            val top = valueHeight + chartH - h
            drawRoundRect(if (value == 0) empty else bar, Offset(left, top), Size(barW, h), CornerRadius(3.dp.toPx()))
            if (value > 0) {
                val v = measurer.measure(value.toString(), labelStyle)
                drawText(v, topLeft = Offset(left + barW / 2 - v.size.width / 2f, top - v.size.height))
            }
            // Label every other bar so dates don't collide on narrow screens.
            if (i % 2 == (bars.size - 1) % 2) {
                val l = measurer.measure(label, labelStyle)
                drawText(l, topLeft = Offset(left + barW / 2 - l.size.width / 2f, valueHeight + chartH + 2.dp.toPx()))
            }
        }
    }
}
