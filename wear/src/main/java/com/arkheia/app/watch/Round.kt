package com.arkheia.app.watch

import android.view.HapticFeedbackConstants
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.animateFloat
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.rotary.onRotaryScrollEvent
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.foundation.CurvedDirection
import androidx.wear.compose.foundation.CurvedLayout
import androidx.wear.compose.material.Icon
import androidx.wear.compose.material.curvedText
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

// ------------------------------------------------------------------ Bezel rings

/** Arcs (start angle, sweep) of the bezel that a ring is drawn on; 0° is 3 o'clock, -90° is the top. */
object Arcs {
    /** Whole circle, gap at the top for the time. */
    val topGap = listOf(-90f + 26f to 360f - 52f)

    /** Two side brackets: gaps at the top (time) and bottom (curved caption). */
    val sides = listOf(-90f + 34f to 180f - 68f, 90f + 34f to 180f - 68f)
}

/**
 * Draws [segments] (fraction of the ring, colour) along [arcs], starting at the first arc's start
 * and going clockwise, skipping the gaps. [progress] animates the sweep in.
 */
fun DrawScope.ring(
    arcs: List<Pair<Float, Float>>,
    segments: List<Pair<Float, Color>>,
    progress: Float,
    stroke: Dp,
    inset: Dp = 3.dp,
    track: Color = Palette.track,
) {
    val sw = stroke.toPx()
    val pad = inset.toPx() + sw / 2
    val topLeft = Offset(pad, pad)
    val size = Size(this.size.width - pad * 2, this.size.height - pad * 2)
    arcs.forEach { (start, sweep) ->
        drawArc(track, start, sweep, false, topLeft, size, style = Stroke(sw, cap = StrokeCap.Round))
    }
    val total = arcs.sumOf { it.second.toDouble() }.toFloat()
    var from = 0f
    segments.forEach { (fraction, color) ->
        val to = (from + fraction).coerceAtMost(1f)
        // Map [from, to] of the whole ring onto the arcs, scaled by progress.
        var a = from * total * progress
        val b = to * total * progress
        var base = 0f
        for ((start, sweep) in arcs) {
            val lo = maxOf(a, base)
            val hi = minOf(b, base + sweep)
            if (hi - lo > 0.3f) {
                drawArc(color, start + (lo - base), hi - lo, false, topLeft, size, style = Stroke(sw, cap = StrokeCap.Round))
            }
            base += sweep
        }
        a = b
        from = to
    }
}

/** A ring around the screen edge that sweeps in when it first appears. */
@Composable
fun BezelRing(
    segments: List<Pair<Float, Color>>,
    modifier: Modifier = Modifier,
    arcs: List<Pair<Float, Float>> = Arcs.topGap,
    stroke: Dp = 6.dp,
    inset: Dp = 3.dp,
) {
    val sweep = remember { Animatable(0f) }
    LaunchedEffect(Unit) { sweep.animateTo(1f, tween(1_100)) }
    Canvas(modifier.fillMaxSize()) { ring(arcs, segments, sweep.value, stroke, inset) }
}

/** Animated fraction (0..1) that eases to its new value. */
@Composable
fun animatedFraction(target: Float): Float {
    val v by animateFloatAsState(target.coerceIn(0f, 1f), tween(800), label = "fraction")
    return v
}

// ------------------------------------------------------------------ Curved text

/** A caption that follows the bottom of the screen, reading left to right. */
@Composable
fun CurvedCaption(text: String, color: Color = Palette.muted, fontSize: TextUnit = 11.sp, modifier: Modifier = Modifier) {
    CurvedLayout(
        modifier = modifier.fillMaxSize(),
        anchor = 90f,
        angularDirection = CurvedDirection.Angular.CounterClockwise,
    ) {
        curvedText(text.take(34), color = color, fontSize = fontSize)
    }
}

// ------------------------------------------------------------------ Radial menus

data class RadialItem(
    val label: String,
    val icon: ImageVector,
    val tint: Color = Palette.primary,
    val detail: String? = null,
    val danger: Boolean = false,
    val onClick: () -> Unit,
)

/**
 * Buttons laid out in a circle around a centre disc. Turn the crown to move the highlight round
 * the ring (with a tick you can feel); tap a bubble, or the centre to open the highlighted one.
 */
@Composable
fun RadialDial(
    items: List<RadialItem>,
    modifier: Modifier = Modifier,
    radius: Float = 0.355f,
    bubble: Dp = 40.dp,
    selected: Int,
    onSelect: (Int) -> Unit,
    onCenter: () -> Unit,
    center: @Composable () -> Unit,
) {
    val view = LocalView.current
    val focus = remember { FocusRequester() }
    var acc by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }

    BoxWithConstraints(
        modifier
            .fillMaxSize()
            .onRotaryScrollEvent {
                acc += it.verticalScrollPixels
                if (abs(acc) > 46f && items.isNotEmpty()) {
                    val step = if (acc > 0) 1 else -1
                    val now = if (selected < 0) (if (step > 0) 0 else items.size - 1) else (selected + step + items.size) % items.size
                    onSelect(now)
                    view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                    acc = 0f
                }
                true
            }
            .focusRequester(focus)
            .focusable(),
        contentAlignment = Alignment.Center,
    ) {
        val r = minOf(maxWidth, maxHeight) * radius
        val n = items.size.coerceAtLeast(1)
        items.forEachIndexed { i, item ->
            val angle = Math.toRadians((-90.0 + i * 360.0 / n))
            Bubble(
                item = item,
                index = i,
                highlighted = i == selected,
                size = bubble,
                modifier = Modifier.offset(x = r * cos(angle).toFloat(), y = r * sin(angle).toFloat()),
            )
        }
        val centerSize = r * 2 - bubble - 10.dp
        Box(
            Modifier
                .size(centerSize)
                .clip(CircleShape)
                .clickable(onClick = onCenter),
            contentAlignment = Alignment.Center,
        ) { center() }
    }
}

@Composable
private fun Bubble(item: RadialItem, index: Int, highlighted: Boolean, size: Dp, modifier: Modifier) {
    // Pop in one after another.
    val appear = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        kotlinx.coroutines.delay(60L + index * 45L)
        appear.animateTo(1f, spring(dampingRatio = 0.55f, stiffness = Spring.StiffnessMediumLow))
    }
    val grow by animateFloatAsState(if (highlighted) 1.18f else 1f, spring(dampingRatio = 0.5f), label = "grow")
    val color = if (item.danger) Palette.red else item.tint
    Box(
        modifier
            .size(size)
            .graphicsLayer {
                scaleX = appear.value * grow
                scaleY = appear.value * grow
                alpha = appear.value.coerceIn(0f, 1f)
            }
            .clip(CircleShape)
            .background(
                Brush.radialGradient(
                    listOf(color.copy(alpha = if (highlighted) 0.55f else 0.28f), Palette.surface),
                )
            )
            .border(if (highlighted) 2.dp else 1.dp, color.copy(alpha = if (highlighted) 1f else 0.35f), CircleShape)
            .clickable(onClick = item.onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(item.icon, item.label, tint = if (highlighted) Color.White else color, modifier = Modifier.size(size * 0.5f))
    }
}

/** A round glass disc for the middle of a dial. */
@Composable
fun CenterDisc(modifier: Modifier = Modifier, glow: Color = Palette.primary, content: @Composable () -> Unit) {
    Box(
        modifier
            .fillMaxSize()
            .clip(CircleShape)
            .background(
                Brush.radialGradient(
                    listOf(glow.copy(alpha = 0.30f), Palette.primaryContainer.copy(alpha = 0.35f), Color.Black),
                )
            )
            .border(1.dp, glow.copy(alpha = 0.25f), CircleShape),
        contentAlignment = Alignment.Center,
    ) { content() }
}

/** Round icon button for docks. */
@Composable
fun RoundButton(icon: ImageVector, label: String, onClick: () -> Unit, tint: Color = Palette.primary, size: Dp = 40.dp, filled: Boolean = false) {
    Box(
        Modifier
            .size(size)
            .clip(CircleShape)
            .background(if (filled) tint else Palette.surfaceHigh)
            .border(1.dp, tint.copy(alpha = if (filled) 0f else 0.35f), CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, label, tint = if (filled) Palette.onPrimary else tint, modifier = Modifier.size(size * 0.5f).scale(1f))
    }
}

// ------------------------------------------------------------------ Liquid orb

/** How far the wrist is tilted left/right, in degrees, smoothed. 0 when the watch has no sensor. */
@Composable
fun rememberTilt(): Float {
    val context = androidx.compose.ui.platform.LocalContext.current
    var tilt by remember { mutableFloatStateOf(0f) }
    androidx.compose.runtime.DisposableEffect(Unit) {
        val sm = context.getSystemService(android.hardware.SensorManager::class.java)
        val sensor = sm?.getDefaultSensor(android.hardware.Sensor.TYPE_GRAVITY)
            ?: sm?.getDefaultSensor(android.hardware.Sensor.TYPE_ACCELEROMETER)
        val listener = object : android.hardware.SensorEventListener {
            override fun onSensorChanged(e: android.hardware.SensorEvent) {
                // x is left/right on the watch face. Smooth it so the liquid eases instead of jittering.
                val target = (-e.values[0] * 5.5f).coerceIn(-38f, 38f)
                tilt += (target - tilt) * 0.12f
            }

            override fun onAccuracyChanged(s: android.hardware.Sensor?, accuracy: Int) {}
        }
        if (sensor != null) sm?.registerListener(listener, sensor, android.hardware.SensorManager.SENSOR_DELAY_UI)
        onDispose { sm?.unregisterListener(listener) }
    }
    return tilt
}

/**
 * The app icon come to life: a glass orb with liquid inside. [level] (0..1) is how full it is,
 * the surface waves gently, and it stays level as you tilt your wrist.
 */
@Composable
fun LiquidOrb(
    level: Float,
    modifier: Modifier = Modifier,
    color: Color = Palette.primary,
    content: @Composable () -> Unit,
) {
    val tilt = rememberTilt()
    val shown = animatedFraction(level)
    val waves = androidx.compose.animation.core.rememberInfiniteTransition(label = "waves")
    val phase by waves.animateFloat(
        0f, (2 * Math.PI).toFloat(),
        androidx.compose.animation.core.infiniteRepeatable(tween(2_600, easing = androidx.compose.animation.core.LinearEasing)),
        label = "phase",
    )
    val tint by androidx.compose.animation.animateColorAsState(color, tween(400), label = "tint")
    Box(modifier.fillMaxSize().clip(CircleShape), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val w = size.width
            val h = size.height
            drawCircle(Brush.verticalGradient(listOf(Color(0xFF14100A), Color(0xFF2A1F10))))
            rotate(tilt) {
                val surface = h * (1f - shown.coerceIn(0.04f, 0.96f))
                fun wave(amp: Float, length: Float, shift: Float): androidx.compose.ui.graphics.Path {
                    val p = androidx.compose.ui.graphics.Path()
                    val step = 4f
                    var x = -w * 0.5f
                    p.moveTo(x, h * 1.6f)
                    while (x <= w * 1.5f) {
                        val y = surface + amp * sin((x / length) * 2 * Math.PI.toFloat() + phase + shift)
                        p.lineTo(x, y)
                        x += step
                    }
                    p.lineTo(w * 1.5f, h * 1.6f)
                    p.close()
                    return p
                }
                drawPath(wave(h * 0.035f, w * 0.75f, 1.7f), tint.copy(alpha = 0.35f))
                drawPath(
                    wave(h * 0.028f, w * 0.9f, 0f),
                    Brush.verticalGradient(listOf(tint.copy(alpha = 0.95f), Palette.primaryContainer), startY = surface, endY = h),
                )
                // A couple of bubbles rising.
                val t = phase / (2 * Math.PI.toFloat())
                for (i in 0..2) {
                    val bx = w * (0.32f + i * 0.17f)
                    val travel = (h - surface).coerceAtLeast(1f)
                    val by = h - ((t + i * 0.33f) % 1f) * travel
                    if (by > surface + 4f) drawCircle(Color.White.copy(alpha = 0.18f), 2.2f + i, Offset(bx, by))
                }
            }
            // Glass highlight.
            drawCircle(
                Brush.radialGradient(listOf(Color.White.copy(alpha = 0.20f), Color.Transparent), center = Offset(w * 0.34f, h * 0.30f), radius = w * 0.45f),
            )
            drawCircle(tint.copy(alpha = 0.35f), style = Stroke(1.5f))
        }
        content()
    }
}

/** A slow-turning glow behind a dial, so the screen never feels static. */
@Composable
fun Aurora(color: Color, modifier: Modifier = Modifier) {
    val spin = androidx.compose.animation.core.rememberInfiniteTransition(label = "aurora")
    val angle by spin.animateFloat(
        0f, 360f,
        androidx.compose.animation.core.infiniteRepeatable(tween(24_000, easing = androidx.compose.animation.core.LinearEasing)),
        label = "angle",
    )
    val tint by androidx.compose.animation.animateColorAsState(color, tween(600), label = "auroraTint")
    Canvas(modifier.fillMaxSize().graphicsLayer { rotationZ = angle }) {
        drawCircle(
            Brush.sweepGradient(
                listOf(Color.Transparent, tint.copy(alpha = 0.16f), Color.Transparent, tint.copy(alpha = 0.10f), Color.Transparent),
            )
        )
    }
}
