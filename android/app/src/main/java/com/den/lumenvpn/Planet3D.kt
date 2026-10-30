package com.den.lumenvpn

import android.graphics.RuntimeShader
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Справжня 3D-сцена (трасування променів у AGSL): матова планета, супутник на нахиленій
 * орбіті, м'які тіні одне на одному, взаємне затінення, кільце орбіти, яке ховає планета,
 * і промінь-зв'язок між ними. 4 семпли на піксель для згладжування.
 */
private const val SCENE = """
uniform float2 iRes;
uniform float3 uSat;
uniform float uSatR;
uniform float uPlanetR;
uniform float3 uLinkA;
uniform float3 uLinkB;
uniform float3 uLinkC;
uniform float uLink;
uniform float uDash;
uniform float uTime;
uniform float3 uAlbedo;
uniform float3 uSatAlbedo;
uniform float3 uAtmo;
uniform float uOn;
uniform float3 uRingN;
uniform float uRingR;

float sphHit(float3 ro, float3 rd, float4 s) {
    float3 oc = ro - s.xyz;
    float b = dot(oc, rd);
    float c = dot(oc, oc) - s.w * s.w;
    float h = b * b - c;
    if (h < 0.0) return -1.0;
    return -b - sqrt(h);
}

// м'яка тінь від сфери (Inigo Quilez)
float sphShadow(float3 ro, float3 rd, float4 s, float k) {
    float3 oc = ro - s.xyz;
    float b = dot(oc, rd);
    float c = dot(oc, oc) - s.w * s.w;
    float h = b * b - c;
    return (b > 0.0) ? step(-0.0001, c) : smoothstep(0.0, 1.0, h * k / b);
}

// взаємне затінення (ambient occlusion) від сфери
float sphOcc(float3 p, float3 n, float4 s) {
    float3 r = s.xyz - p;
    float l = length(r);
    return clamp(dot(n, r) * (s.w * s.w) / (l * l * l), 0.0, 1.0);
}

float3 shade(float3 p, float3 n, float3 rd, float3 alb, float4 other, float3 L) {
    float wrap = clamp((dot(n, L) + 0.08) / 1.08, 0.0, 1.0);        // матова поверхня
    float sh = sphShadow(p + n * 0.002, L, other, 10.0);
    float occ = 1.0 - sphOcc(p, n, other);
    float3 key = float3(1.0, 0.95, 0.88) * 3.4 * wrap * sh;
    float3 sky = float3(0.20, 0.28, 0.45) * 0.14 * (0.5 + 0.5 * n.y) * occ;
    float3 bounce = float3(0.10, 0.14, 0.24) * 0.22 * clamp(dot(n, -L), 0.0, 1.0) * occ;
    float3 col = alb * (key + sky + bounce);
    float fre = 1.0 - clamp(dot(n, -rd), 0.0, 1.0);
    col += float3(0.45, 0.55, 0.75) * 0.05 * fre * fre * fre * occ;   // пил на краю диска
    return col;
}

float3 bez(float u) {
    float v = 1.0 - u;
    return v * v * uLinkA + 2.0 * v * u * uLinkC + u * u * uLinkB;
}

// найближча точка між променем камери і відрізком p0-p1: (відстань, параметр на відрізку, t на промені)
float3 raySeg(float3 ro, float3 rd, float3 p0, float3 p1) {
    float3 ba = p1 - p0;
    float3 w0 = p0 - ro;
    float A = dot(ba, ba);
    float B = dot(ba, rd);
    float D = dot(ba, w0);
    float E = dot(rd, w0);
    float s = clamp((B * E - D) / max(A - B * B, 1e-6), 0.0, 1.0);
    float3 pc = p0 + ba * s;
    float t = dot(pc - ro, rd);
    return float3(length(ro + rd * t - pc), s, t);
}

// світла частинка в точці простору
float particle(float3 ro, float3 rd, float3 pos, float tHit, float size) {
    float t = dot(pos - ro, rd);
    if (t <= 0.0 || t > tHit) return 0.0;
    float d = length(ro + rd * t - pos);
    return exp(-d * d / (size * size));
}

float4 render(float2 fc) {
    float m = min(iRes.x, iRes.y);
    float2 uv = (fc - 0.5 * iRes) / (0.5 * m);
    uv.y = -uv.y;

    float3 ro = float3(0.0, 1.15, 6.0);
    float3 ww = normalize(-ro);
    float3 uu = normalize(cross(ww, float3(0.0, 1.0, 0.0)));
    float3 vv = cross(uu, ww);
    float3 rd = normalize(uv.x * uu + uv.y * vv + 3.4 * ww);
    float3 L = normalize(float3(-0.62, 0.58, 0.55));

    float4 P = float4(0.0, 0.0, 0.0, uPlanetR);
    float4 S = float4(uSat, uSatR);

    float tHit = 1e9;
    float3 col = float3(0.0);
    float a = 0.0;

    float tp = sphHit(ro, rd, P);
    if (tp > 0.0) {
        tHit = tp;
        float3 p = ro + rd * tp;
        float3 n = normalize(p - P.xyz);
        // ледь помітні смуги атмосфери, що повільно обертаються
        float ang = uTime * 0.04;
        float3 q = float3(n.x * cos(ang) - n.z * sin(ang), n.y, n.x * sin(ang) + n.z * cos(ang));
        float bands = 1.0 + 0.10 * sin(q.y * 15.0 + 1.6 * sin(q.x * 3.0 + q.z * 2.0)) + 0.05 * sin(q.y * 41.0);
        col = shade(p, n, rd, uAlbedo * bands, S, L);
        float fre = 1.0 - clamp(dot(n, -rd), 0.0, 1.0);
        col += uAtmo * uOn * 0.35 * fre * fre * fre;                     // атмосфера (VPN увімкнено)
        a = 1.0;
    }
    float ts = sphHit(ro, rd, S);
    if (ts > 0.0 && ts < tHit) {
        tHit = ts;
        float3 p = ro + rd * ts;
        float3 n = normalize(p - S.xyz);
        col = shade(p, n, rd, uSatAlbedo, P, L);
        a = 1.0;
    }

    // тонкий ореол атмосфери навколо диска
    if (tp < 0.0) {
        float b = dot(-ro, rd);
        float d = length(ro + rd * b) - uPlanetR;
        float halo = exp(-max(d, 0.0) * 22.0) * uOn * 0.22;
        if (b < tHit) { col += uAtmo * halo; a = max(a, halo); }
    }

    // кільце орбіти: перетин з площиною орбіти, ховається за планетою, приймає її тінь
    float dn = dot(rd, uRingN);
    if (abs(dn) > 1e-4) {
        float tr = -dot(ro, uRingN) / dn;
        if (tr > 0.0 && tr < tHit) {
            float3 q = ro + rd * tr;
            float dist = abs(length(q) - uRingR);
            float ring = (1.0 - smoothstep(0.0, 0.009, dist)) * (0.10 + 0.08 * uOn);
            ring *= 0.35 + 0.65 * sphShadow(q, L, P, 8.0);
            col += float3(0.62, 0.70, 0.85) * ring;
            a = max(a, ring);
        }
    }

    // зв'язок: тонка вигнута дуга (квадратична крива Безьє) + частинки даних в обидва боки
    if (uLink > 0.001) {
        float best = 1e9;
        float bu = 0.0;
        float bt = 0.0;
        for (int i = 0; i < 14; i++) {
            float u0 = float(i) / 14.0;
            float u1 = float(i + 1) / 14.0;
            float3 r = raySeg(ro, rd, bez(u0), bez(u1));
            if (r.x < best) { best = r.x; bu = mix(u0, u1, r.y); bt = r.z; }
        }
        float reveal = uDash > 0.5 ? 1.0 : uLink;                      // дуга простягається від планети
        if (bt > 0.0 && bt < tHit && bu <= reveal) {
            float fadeEnds = smoothstep(0.0, 0.08, bu) * smoothstep(1.0, 0.92, bu);
            float core = 1.0 - smoothstep(0.0025, 0.0085, best);
            float soft = exp(-best * 90.0) * 0.10;
            float dash = mix(1.0, step(0.5, fract(bu * 9.0 - uTime * 1.2)), uDash);
            float e = (core * 0.42 + soft) * fadeEnds * dash * mix(uLink, 0.7, uDash);
            col += float3(0.52, 0.80, 1.0) * e;
            a = max(a, clamp(e, 0.0, 1.0));
        }
        // частинки: 3 від планети до супутника, 2 назад (обмін даними)
        if (uDash < 0.5 && uLink > 0.9) {
            float k = (uLink - 0.9) * 10.0;
            float e = 0.0;
            for (int i = 0; i < 3; i++) {
                float u = fract(uTime * 0.42 + float(i) / 3.0);
                e += particle(ro, rd, bez(u), tHit, 0.016) * sin(3.14159 * u);
            }
            for (int i = 0; i < 2; i++) {
                float u = 1.0 - fract(uTime * 0.31 + float(i) / 2.0 + 0.17);
                e += particle(ro, rd, bez(u), tHit, 0.012) * sin(3.14159 * u) * 0.7;
            }
            e *= k;
            col += float3(0.80, 0.94, 1.0) * e;
            a = max(a, clamp(e, 0.0, 1.0));
        }
    }
    return float4(col, a);
}

half4 main(float2 fc) {
    float4 c = render(fc + float2(-0.125, -0.375)) + render(fc + float2(0.375, -0.125))
             + render(fc + float2(0.125, 0.375)) + render(fc + float2(-0.375, 0.125));
    c *= 0.25;
    float3 col = c.rgb;
    col = col / (1.0 + col * 0.55);                 // м'який тонмапінг
    col = pow(max(col, float3(0.0)), float3(1.0 / 2.2));
    float alpha = clamp(c.a, 0.0, 1.0);
    return half4(half3(min(col, float3(alpha))), half(alpha));   // premultiplied
}
"""

/** Велика кнопка: 3D-планета із супутником. На Android < 13 — спрощена 2D-версія. */
@Composable
fun Orb(state: VpnState, onClick: () -> Unit, diameter: Dp, modifier: Modifier = Modifier) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) Planet3D(state, onClick, diameter, modifier)
    else OrbLegacy(state, onClick, diameter, modifier)
}

@RequiresApi(Build.VERSION_CODES.TIRAMISU)
@Composable
private fun Planet3D(state: VpnState, onClick: () -> Unit, diameter: Dp, modifier: Modifier) {
    val shader = remember { RuntimeShader(SCENE) }
    val brush = remember(shader) { ShaderBrush(shader) }

    val on by animateFloatAsState(if (state == VpnState.ON) 1f else 0f, tween(1200), label = "on")
    val danger by animateFloatAsState(if (state == VpnState.ERROR) 1f else 0f, tween(350), label = "danger")
    val link by animateFloatAsState(if (state == VpnState.ON) 1f else 0f, tween(1300, easing = FastOutSlowInEasing), label = "link")
    val dash by animateFloatAsState(if (state == VpnState.CONNECTING) 1f else 0f, tween(400), label = "dash")
    var pressed by remember { mutableStateOf(false) }
    val press by animateFloatAsState(
        if (pressed) 1f else 0f,
        if (pressed) spring(dampingRatio = 0.85f, stiffness = 1200f) else spring(dampingRatio = 0.5f, stiffness = 260f),
        label = "press",
    )
    val shake = remember { Animatable(0f) }
    LaunchedEffect(state) {
        if (state == VpnState.ERROR) for (v in listOf(1f, -1f, 0.6f, -0.6f, 0.25f, 0f)) shake.animateTo(v, tween(70))
    }

    // орбіта: кут інтегрується за часом, швидкість плавно міняється зі станом
    val speed by animateFloatAsState(
        when (state) { VpnState.CONNECTING -> 0.95f; VpnState.ON -> 0.32f; else -> 0.42f },
        tween(1400, easing = FastOutSlowInEasing), label = "speed",
    )
    val speedNow by rememberUpdatedState(speed)
    var angle by remember { mutableFloatStateOf(2.2f) }
    var time by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(Unit) {
        var last = 0L
        while (true) withFrameNanos { t ->
            if (last != 0L) {
                val dt = (t - last) / 1e9f
                angle += dt * speedNow
                time += dt
            }
            last = t
        }
    }

    Box(
        modifier
            .size(diameter)
            .semantics { contentDescription = "VPN" }
            .pointerInput(Unit) { detectTapGestures(onPress = { pressed = true; tryAwaitRelease(); pressed = false }, onTap = { onClick() }) }
            .graphicsLayer {
                val sc = 1f - 0.05f * press
                scaleX = sc; scaleY = sc
                translationX = shake.value * 14.dp.toPx()
            },
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val tilt = Math.toRadians(-16.0)
            val ct = cos(tilt).toFloat(); val st = sin(tilt).toFloat()
            val orbitR = 1.58f
            val satR = 0.14f
            val planetR = 0.80f - 0.03f * press
            val bx = orbitR * cos(angle); val bz = orbitR * sin(angle)
            val sx = bx * ct; val sy = bx * st; val sz = bz

            // кінці дуги: поверхня планети -> поверхня супутника; контрольна точка відстає
            // від руху супутника (по дотичній до орбіти) і трохи піднята над площиною орбіти
            val len = sqrt(sx * sx + sy * sy + sz * sz)
            val nx = sx / len; val ny = sy / len; val nz = sz / len
            val ax = nx * planetR; val ay = ny * planetR; val az = nz * planetR
            val fx = sx - nx * satR; val fy = sy - ny * satR; val fz = sz - nz * satR
            val tx0 = -sin(angle) * ct; val ty0 = -sin(angle) * st; val tz0 = cos(angle)
            val tl = sqrt(tx0 * tx0 + ty0 * ty0 + tz0 * tz0)
            val span = sqrt((fx - ax) * (fx - ax) + (fy - ay) * (fy - ay) + (fz - az) * (fz - az))
            val bow = 0.55f * span
            val lift = 0.18f * span
            val cx = (ax + fx) / 2 - tx0 / tl * bow - st * lift
            val cy = (ay + fy) / 2 - ty0 / tl * bow + ct * lift
            val cz = (az + fz) / 2 - tz0 / tl * bow

            // кольори в лінійному RGB: графіт-індиго -> бірюзова планета, помилка -> червона
            fun mix(x: Float, y: Float, t: Float) = x + (y - x) * t
            val r = mix(mix(0.016f, 0.010f, on), 0.16f, danger)
            val gg = mix(mix(0.020f, 0.090f, on), 0.020f, danger)
            val b = mix(mix(0.048f, 0.170f, on), 0.020f, danger)

            shader.setFloatUniform("iRes", size.width, size.height)
            shader.setFloatUniform("uSat", sx, sy, sz)
            shader.setFloatUniform("uSatR", satR)
            shader.setFloatUniform("uPlanetR", planetR)
            shader.setFloatUniform("uLinkA", ax, ay, az)
            shader.setFloatUniform("uLinkB", fx, fy, fz)
            shader.setFloatUniform("uLinkC", cx, cy, cz)
            shader.setFloatUniform("uLink", if (state == VpnState.CONNECTING) dash else link)
            shader.setFloatUniform("uDash", dash)
            shader.setFloatUniform("uTime", time)
            shader.setFloatUniform("uAlbedo", r, gg, b)
            shader.setFloatUniform("uSatAlbedo", 0.075f, 0.078f, 0.088f)
            shader.setFloatUniform("uAtmo", 0.10f, 0.45f, 0.90f)
            shader.setFloatUniform("uOn", on)
            shader.setFloatUniform("uRingN", -st, ct, 0f)
            shader.setFloatUniform("uRingR", orbitR)
            drawRect(brush)
        }
    }
}
