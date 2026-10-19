package com.jotsarup.nowish.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntSize
import kotlin.random.Random

private class Piece(
    var x: Float,
    var y: Float,
    var vx: Float,
    var vy: Float,
    var rotation: Float,
    val spin: Float,
    val color: Color,
    val w: Float,
    val h: Float,
    val round: Boolean,
    var age: Float = 0f,
    val life: Float,
)

/** Full-screen confetti burst. Bump [trigger] to fire another one. Doesn't block touches. */
@Composable
fun ConfettiOverlay(trigger: Int, modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    val palette = remember(scheme) {
        listOf(
            scheme.primary, scheme.tertiary, scheme.secondary,
            Color(0xFFFFC94D), Color(0xFFFF6B9A), Color(0xFF5ED6A0), Color(0xFF6CB8FF),
        )
    }
    val density = LocalDensity.current.density
    val pieces = remember { ArrayList<Piece>() }
    var size by remember { mutableLongStateOf(0L) }
    var frame by remember { mutableLongStateOf(0L) }

    LaunchedEffect(trigger) {
        if (trigger == 0) return@LaunchedEffect
        val width = (size shr 32).toFloat()
        val height = (size and 0xffffffffL).toFloat()
        if (width <= 0f) return@LaunchedEffect
        val originX = width / 2f
        val originY = height * 0.32f
        repeat(90) {
            val angle = Random.nextDouble(-Math.PI, 0.0) // upward half-circle
            val speed = Random.nextFloat() * 900f + 450f
            pieces += Piece(
                x = originX,
                y = originY,
                vx = (kotlin.math.cos(angle) * speed).toFloat() * density / 2.6f,
                vy = (kotlin.math.sin(angle) * speed).toFloat() * density / 2.6f - 200f * density / 2.6f,
                rotation = Random.nextFloat() * 360f,
                spin = Random.nextFloat() * 720f - 360f,
                color = palette.random(),
                w = (Random.nextFloat() * 6f + 6f) * density,
                h = (Random.nextFloat() * 4f + 4f) * density,
                round = Random.nextInt(4) == 0,
                life = Random.nextFloat() * 0.8f + 1.4f,
            )
        }
        var last = withFrameNanos { it }
        while (pieces.isNotEmpty()) {
            withFrameNanos { now ->
                val dt = ((now - last) / 1_000_000_000f).coerceAtMost(0.05f)
                last = now
                val gravity = 1100f * density / 2.6f
                val iterator = pieces.iterator()
                while (iterator.hasNext()) {
                    val p = iterator.next()
                    p.age += dt
                    p.vy += gravity * dt
                    p.vx *= (1f - 1.4f * dt)
                    p.x += p.vx * dt
                    p.y += p.vy * dt
                    p.rotation += p.spin * dt
                    if (p.age > p.life || p.y > height + 50f) iterator.remove()
                }
                frame = now
            }
        }
        frame = 0L
    }

    Canvas(
        modifier
            .fillMaxSize()
            .onSizeChanged { s: IntSize -> size = (s.width.toLong() shl 32) or s.height.toLong() }
    ) {
        if (frame == 0L) return@Canvas
        pieces.forEach { p ->
            val alpha = ((p.life - p.age) / 0.4f).coerceIn(0f, 1f)
            rotate(p.rotation, Offset(p.x, p.y)) {
                if (p.round) {
                    drawCircle(p.color.copy(alpha = alpha), radius = p.h / 1.4f, center = Offset(p.x, p.y))
                } else {
                    drawRect(
                        p.color.copy(alpha = alpha),
                        topLeft = Offset(p.x - p.w / 2f, p.y - p.h / 2f),
                        size = Size(p.w, p.h),
                    )
                }
            }
        }
    }
}
