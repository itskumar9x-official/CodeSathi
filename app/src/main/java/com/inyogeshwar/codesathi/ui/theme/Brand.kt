package com.inyogeshwar.codesathi.ui.theme

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.background
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * CodeSathi "Sathi Spark" mark, drawn natively from brand/codesathi-mark.svg
 * (108-unit grid): two code brackets as a friendly pair sharing a spark.
 *
 * [working] adds one subtle motion: the spark breathes (scale + glow).
 * The brackets stay still. With system animations off it stays static.
 *
 * [tile] draws the rounded gradient app-icon tile behind the mark.
 * [mono] draws everything in one colour.
 */
@Composable
fun SathiMark(
    modifier: Modifier = Modifier,
    size: Dp = 40.dp,
    working: Boolean = false,
    tile: Boolean = false,
    mono: Color? = null,
    decorative: Boolean = false,
) {
    val status = LocalSathiStatus.current
    val dark = status.isDark
    var sparkScale = 1f
    var sparkAlpha = 1f
    if (working && !LocalReduceMotion.current) {
        val t = rememberInfiniteTransition(label = "sathiSpark")
        sparkScale = t.animateFloat(
            initialValue = 0.78f, targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(950, easing = FastOutSlowInEasing), RepeatMode.Reverse),
            label = "sparkScale"
        ).value
        sparkAlpha = t.animateFloat(
            initialValue = 0.7f, targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(950, easing = FastOutSlowInEasing), RepeatMode.Reverse),
            label = "sparkAlpha"
        ).value
    }
    val left = mono ?: if (tile) Color.White else if (dark) SathiColors.Mint else Color(0xFF0B7A72)
    val right = mono ?: if (tile) SathiColors.MintPale else if (dark) SathiColors.MintPale else SathiColors.TealBright
    val spark = mono ?: SathiColors.Saffron
    val sem = if (decorative) Modifier else Modifier.semantics { contentDescription = "CodeSathi logo" }
    val tileBrush = sathiGradient(dark)

    Canvas(modifier.size(size).then(sem)) {
        // Window of the 108 grid that is shown: the whole tile, or a tight crop around the mark.
        val (from, span) = if (tile) 0f to 108f else 21f to 66f
        val u = this.size.minDimension / span
        fun p(x: Float, y: Float) = Offset((x - from) * u, (y - from) * u)
        if (tile) {
            drawRoundRect(tileBrush, cornerRadius = CornerRadius(26f * u, 26f * u))
        }
        val stroke = Stroke(width = 7.5f * u, cap = StrokeCap.Round, join = StrokeJoin.Round)
        drawPath(Path().apply {
            p(41f, 37f).let { moveTo(it.x, it.y) }
            p(27f, 54f).let { lineTo(it.x, it.y) }
            p(41f, 71f).let { lineTo(it.x, it.y) }
        }, left, style = stroke)
        drawPath(Path().apply {
            p(67f, 37f).let { moveTo(it.x, it.y) }
            p(81f, 54f).let { lineTo(it.x, it.y) }
            p(67f, 71f).let { lineTo(it.x, it.y) }
        }, right, style = stroke)
        // Four-point spark (same curves as the SVG master).
        val sparkPath = Path().apply {
            fun m(x: Float, y: Float) = p(x, y)
            m(54f, 42.5f).let { moveTo(it.x, it.y) }
            cubic(this, m(55.4f, 49.5f), m(58.5f, 52.6f), m(65.5f, 54f))
            cubic(this, m(58.5f, 55.4f), m(55.4f, 58.5f), m(54f, 65.5f))
            cubic(this, m(52.6f, 58.5f), m(49.5f, 55.4f), m(42.5f, 54f))
            cubic(this, m(49.5f, 52.6f), m(52.6f, 49.5f), m(54f, 42.5f))
            close()
        }
        scale(sparkScale, pivot = p(54f, 54f)) {
            drawPath(sparkPath, spark.copy(alpha = spark.alpha * sparkAlpha))
        }
    }
}

private fun cubic(path: Path, c1: Offset, c2: Offset, end: Offset) {
    path.cubicTo(c1.x, c1.y, c2.x, c2.y, end.x, end.y)
}

/** Mark + "CodeSathi" wordmark, with "Sathi" in the brand teal. */
@Composable
fun SathiLockup(modifier: Modifier = Modifier, markSize: Dp = 32.dp, working: Boolean = false) {
    Row(
        modifier.semantics(mergeDescendants = true) { contentDescription = "CodeSathi" },
        verticalAlignment = Alignment.CenterVertically
    ) {
        SathiMark(size = markSize, working = working, decorative = true)
        Text(
            buildAnnotatedString {
                append("Code")
                withStyle(SpanStyle(color = MaterialTheme.colorScheme.primary)) { append("Sathi") }
            },
            style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
            color = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier.padding(start = 10.dp)
        )
    }
}

/** Three small dots that hop in turn — the "agent is working" indicator. */
@Composable
fun BouncingDots(modifier: Modifier = Modifier, color: Color = LocalSathiStatus.current.live, dot: Dp = 6.dp) {
    val reduce = LocalReduceMotion.current
    val t = rememberInfiniteTransition(label = "dots")
    val phase = if (reduce) 0f else t.animateFloat(
        initialValue = 0f, targetValue = 3f,
        animationSpec = infiniteRepeatable(tween(1050, easing = androidx.compose.animation.core.LinearEasing)),
        label = "dotPhase"
    ).value
    Row(modifier.padding(vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(dot * 0.7f)) {
        repeat(3) { i ->
            // Each dot rises during its own third of the cycle.
            val local = ((phase - i + 3f) % 3f)
            val lift = if (local < 1f) kotlin.math.sin(local * Math.PI).toFloat() else 0f
            Box(
                Modifier
                    .offset(y = -(dot * 0.8f) * lift)
                    .size(dot)
                    .background(color.copy(alpha = 0.55f + 0.45f * lift), CircleShape)
            )
        }
    }
}

