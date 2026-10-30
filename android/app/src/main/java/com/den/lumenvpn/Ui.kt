package com.den.lumenvpn

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageShader
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.cos
import kotlin.math.min

/** Полотно Void Silk: дві аури в Oklab + шар «оптичного шовку» зверху. `presence` = теплий ореол зверху. */
@Composable
fun SilkCanvas(presence: Float, content: @Composable BoxScope.() -> Unit) {
    val noise = remember { interleavedGradientNoise() }
    val shader = remember(noise) { ShaderBrush(ImageShader(noise, TileMode.Repeated, TileMode.Repeated)) }
    Box(
        Modifier
            .fillMaxSize()
            .background(Lumen.void)
            .drawBehind {
                val big = max(size.width, size.height)
                fun aura(cx: Float, cy: Float, r: Float, c: Color4) = drawRect(
                    Brush.radialGradient(
                        oklabStops(c, c.copy(a = 0f)), center = Offset(size.width * cx, size.height * cy), radius = r,
                    ),
                )
                aura(0.85f, 0.80f, big * 0.65f, Color4(0.26f, 0.06f, 265f, 0.35f))
                aura(0.15f, 0.20f, big * 0.65f, Color4(0.24f, 0.05f, 200f, 0.28f))
                if (presence > 0.01f) aura(0.5f, -0.05f, big * 0.55f, Color4(0.55f, 0.10f, 70f, 0.32f * presence))
            }
            .drawWithContent {
                drawContent()
                drawRect(shader, alpha = 0.07f, blendMode = BlendMode.Overlay)
            },
        content = content,
    )
}

// палітри кулі: (світла сторона, середній тон, тінь)
private val offLit = Color4(0.40f, 0.05f, 265f)
private val offMid = Color4(0.245f, 0.045f, 272f)
private val offShade = Color4(0.105f, 0.03f, 288f)
private val onLit = Color4(0.74f, 0.11f, 212f)
private val onMid = Color4(0.47f, 0.11f, 232f)
private val onShade = Color4(0.20f, 0.08f, 272f)
private val errLit = Color4(0.62f, 0.14f, 28f)
private val errMid = Color4(0.38f, 0.12f, 25f)
private val errShade = Color4(0.16f, 0.07f, 20f)

private fun sphereBrush(lit: Color4, mid: Color4, shade: Color4, center: Offset, radius: Float): Brush {
    val n = 6
    val a = oklabStops(lit, mid, n)
    val b = oklabStops(mid, shade, n)
    val stops = ArrayList<Pair<Float, Color>>()
    for (i in 0 until n) stops += (0.55f * i / (n - 1)) to a[i]
    for (i in 1 until n) stops += (0.55f + 0.45f * i / (n - 1)) to b[i]
    return Brush.radialGradient(colorStops = stops.toTypedArray(), center = center, radius = radius)
}

/**
 * Велика кнопка: матова планета із супутником на орбіті. Коли VPN увімкнено, між ними
 * з'являється промінь-зв'язок. Стан передається світлом і рухом, без тексту.
 */
@Composable
fun OrbLegacy(state: VpnState, onClick: () -> Unit, diameter: androidx.compose.ui.unit.Dp, modifier: Modifier = Modifier) {
    val inf = rememberInfiniteTransition(label = "orb")
    val breath by inf.animateFloat(0f, 1f, infiniteRepeatable(tween(4200, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "breath")
    val flow by inf.animateFloat(0f, 1f, infiniteRepeatable(tween(1500, easing = LinearEasing)), label = "flow")
    val crystal by animateFloatAsState(if (state == VpnState.ON) 1f else 0f, tween(1100), label = "crystal")
    val danger by animateFloatAsState(if (state == VpnState.ERROR) 1f else 0f, tween(400), label = "danger")
    val link by animateFloatAsState(if (state == VpnState.ON) 1f else 0f, tween(1100, easing = FastOutSlowInEasing), label = "link")
    var pressed by remember { mutableStateOf(false) }
    val press by animateFloatAsState(
        if (pressed) 1f else 0f,
        if (pressed) spring(dampingRatio = 0.8f, stiffness = 900f) else spring(dampingRatio = 0.42f, stiffness = 320f),
        label = "press",
    )
    val shake = remember { Animatable(0f) }
    LaunchedEffect(state) {
        if (state == VpnState.ERROR) for (v in listOf(1f, -1f, 0.6f, -0.6f, 0.25f, 0f)) shake.animateTo(v, tween(70))
    }

    // супутник: кут інтегрується в часі, швидкість плавно змінюється зі станом (без стрибків)
    val targetSpeed = when (state) { VpnState.CONNECTING -> 1.7f; VpnState.ON -> 0.38f; else -> 0.55f }
    val speed by animateFloatAsState(targetSpeed, tween(900), label = "speed")
    val speedNow by rememberUpdatedState(speed)
    var angle by remember { mutableFloatStateOf(0.9f) }
    LaunchedEffect(Unit) {
        var last = 0L
        while (true) withFrameNanos { t ->
            if (last != 0L) angle += (t - last) / 1e9f * speedNow
            last = t
        }
    }

    val connecting = state == VpnState.CONNECTING
    Box(
        modifier
            .size(diameter)
            .semantics { contentDescription = "VPN" }
            .pointerInput(Unit) { detectTapGestures(onPress = { pressed = true; tryAwaitRelease(); pressed = false }, onTap = { onClick() }) }
            .graphicsLayer {
                val sc = 1f - 0.06f * press
                scaleX = sc; scaleY = sc
                translationX = shake.value * 14.dp.toPx()
                clip = false
            },
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val d = min(size.width, size.height)
            val c = center
            val rp = d * 0.34f
            val oa = d * 0.47f
            val ob = d * 0.15f
            val tilt = Math.toRadians(-18.0)
            val ct = cos(tilt).toFloat()
            val st = sin(tilt).toFloat()
            fun orbit(t: Float): Offset {
                val x = oa * cos(t); val y = ob * sin(t)
                return Offset(c.x + x * ct - y * st, c.y + x * st + y * ct)
            }

            val lit = lerp4(lerp4(offLit, onLit, crystal), errLit, danger).let { it.copy(l = it.l - 0.07f * press) }
            val mid = lerp4(lerp4(offMid, onMid, crystal), errMid, danger)
            val shade = lerp4(lerp4(offShade, onShade, crystal), errShade, danger)
            val hue = lerpF(lerpF(265f, 215f, crystal), 25f, danger)

            // власне свічення планети (коли активно)
            val glowA = 0.05f + 0.28f * crystal * (0.65f + 0.35f * breath) + 0.18f * danger
            drawCircle(
                Brush.radialGradient(oklabStops(Color4(0.62f, 0.11f, hue, glowA), Color4(0.62f, 0.11f, hue, 0f)), c, rp * 1.9f),
                radius = rp * 1.9f, center = c,
            )

            // підключення: м'яке кільце від планети
            if (connecting) {
                drawCircle(
                    oklch(0.80f, 0.08f, 230f, 0.34f * (1f - flow)),
                    radius = rp * (1f + 0.4f * flow), center = c, style = Stroke(2.dp.toPx()),
                )
            }

            // орбіта: усе кільце позаду планети, передня половина — поверх неї (малюється нижче)
            fun orbitPath(from: Float, to: Float): Path = Path().apply {
                val n = 64
                for (i in 0..n) {
                    val p = orbit(from + (to - from) * i / n)
                    if (i == 0) moveTo(p.x, p.y) else lineTo(p.x, p.y)
                }
            }
            val ringColor = oklch(0.75f, 0.04f, 240f, 0.10f + 0.10f * crystal)
            drawPath(orbitPath(0f, 6.2832f), ringColor, style = Stroke(1.dp.toPx()))

            val sat = orbit(angle)
            val depth = sin(angle)               // > 0: супутник спереду планети
            val rs = d * 0.05f * (1f + 0.2f * depth)
            val satFront = depth > 0f

            fun drawSatellite() {
                val sp = Offset(sat.x - rs * 0.4f, sat.y - rs * 0.46f)
                drawCircle(sphereBrush(lit, mid, shade, sp, rs * 1.75f), radius = rs, center = sat)
            }

            // зв'язок планета-супутник: росте від планети до супутника при підключенні
            fun drawLink() {
                val v = sat - c
                val dist = v.getDistance()
                if (dist < rp + rs * 1.4f) return           // супутник за диском планети
                val dir = Offset(v.x / dist, v.y / dist)
                val start = Offset(c.x + dir.x * rp * 0.98f, c.y + dir.y * rp * 0.98f)
                val full = Offset(sat.x - dir.x * rs, sat.y - dir.y * rs)
                val prog = if (connecting) 1f else link
                if (prog < 0.01f) return
                val end = Offset(start.x + (full.x - start.x) * prog, start.y + (full.y - start.y) * prog)
                val alpha = if (connecting) 0.55f else 0.95f * link
                val col = oklch(0.86f, 0.10f, 215f, alpha)
                drawLine(oklch(0.80f, 0.10f, 215f, alpha * 0.22f), start, end, 7.dp.toPx(), cap = StrokeCap.Round)
                drawLine(
                    col, start, end, 2.dp.toPx(), cap = StrokeCap.Round,
                    pathEffect = if (connecting) PathEffect.dashPathEffect(floatArrayOf(10.dp.toPx(), 8.dp.toPx()), -flow * 18.dp.toPx()) else null,
                )
                if (!connecting && link > 0.95f) {          // світловий імпульс уздовж променя
                    val f = flow
                    val sp = Offset(start.x + (end.x - start.x) * f, start.y + (end.y - start.y) * f)
                    drawCircle(
                        Brush.radialGradient(listOf(oklch(0.97f, 0.05f, 215f, 0.9f), Color.Transparent), sp, 7.dp.toPx()),
                        radius = 7.dp.toPx(), center = sp,
                    )
                }
            }

            if (!satFront) { drawLink(); drawSatellite() }

            // планета: дифузне освітлення, джерело світла зверху-зліва
            val lp = Offset(c.x - rp * (0.40f - 0.10f * press), c.y - rp * (0.46f - 0.12f * press))
            drawCircle(sphereBrush(lit, mid, shade, lp, rp * 1.75f), radius = rp, center = c)
            val bp = Offset(c.x + rp * 0.62f, c.y + rp * 0.66f)
            drawCircle(
                Brush.radialGradient(
                    oklabStops(Color4(0.55f, 0.07f, 230f, 0.20f * (0.5f + 0.5f * crystal)), Color4(0.55f, 0.07f, 230f, 0f)), bp, rp * 0.95f,
                ),
                radius = rp, center = c,
            )
            drawCircle(
                Brush.radialGradient(
                    oklabStops(Color4(0.97f, 0.02f, 240f, (0.09f + 0.10f * crystal) * (1f - 0.5f * press)), Color4(0.97f, 0.02f, 240f, 0f)),
                    lp, rp * 0.75f,
                ),
                radius = rp, center = c,
            )

            // передня половина орбіти й супутник — поверх планети
            drawPath(orbitPath(0f, 3.1416f), ringColor, style = Stroke(1.dp.toPx()))
            if (satFront) { drawLink(); drawSatellite() }
        }
    }
}

/** Кнопка налаштувань: тонка шестерня зі світлом, без підпису. */
@Composable
fun SettingsDot(onClick: () -> Unit, modifier: Modifier = Modifier) {
    var pressed by remember { mutableStateOf(false) }
    val k by animateFloatAsState(if (pressed) 1f else 0f, tween(180), label = "gear")
    Box(
        modifier
            .size(64.dp)
            .semantics { contentDescription = "Налаштування" }
            .pointerInput(Unit) { detectTapGestures(onPress = { pressed = true; tryAwaitRelease(); pressed = false }, onTap = { onClick() }) },
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val c = center
            val col = oklch(0.62f + 0.25f * k, 0.02f, 250f, 0.80f)
            val glow = 26.dp.toPx()
            drawCircle(
                Brush.radialGradient(oklabStops(Color4(0.55f, 0.04f, 250f, 0.18f + 0.20f * k), Color4(0.55f, 0.04f, 250f, 0f)), c, glow),
                radius = glow, center = c,
            )
            val w = 1.6.dp.toPx()
            val ro = 8.dp.toPx()
            drawCircle(col, radius = ro, center = c, style = Stroke(w))
            drawCircle(col, radius = 3.dp.toPx(), center = c, style = Stroke(w))
            for (i in 0 until 8) {
                rotate(i * 45f, c) {
                    drawLine(col, Offset(c.x, c.y - ro), Offset(c.x, c.y - ro - 3.5.dp.toPx()), w, cap = androidx.compose.ui.graphics.StrokeCap.Round)
                }
            }
        }
    }
}
