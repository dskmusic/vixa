package com.dskmusic.vixa.engine

import android.graphics.Path
import android.graphics.RectF
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** Formas y flechas vectoriales incluidas. Cada una dibuja su trazado en el cuadrado unidad (0..1). */
class ShapeDef(val id: String, val stroke: Boolean = false, val build: (Path) -> Unit)

object Shapes {
    private fun poly(p: Path, vararg pts: Float) {
        p.moveTo(pts[0], pts[1])
        var i = 2
        while (i < pts.size) { p.lineTo(pts[i], pts[i + 1]); i += 2 }
        p.close()
    }

    private fun star(p: Path, points: Int, inner: Float) {
        for (i in 0 until points * 2) {
            val r = if (i % 2 == 0) 0.5f else inner
            val a = (-PI / 2 + i * PI / points).toFloat()
            val x = 0.5f + r * cos(a)
            val y = 0.5f + r * sin(a)
            if (i == 0) p.moveTo(x, y) else p.lineTo(x, y)
        }
        p.close()
    }

    private fun ngon(p: Path, n: Int) {
        for (i in 0 until n) {
            val a = (-PI / 2 + i * 2 * PI / n).toFloat()
            val x = 0.5f + 0.5f * cos(a)
            val y = 0.5f + 0.5f * sin(a)
            if (i == 0) p.moveTo(x, y) else p.lineTo(x, y)
        }
        p.close()
    }

    val all: List<ShapeDef> = listOf(
        ShapeDef("rect") { it.addRect(0f, 0.15f, 1f, 0.85f, Path.Direction.CW) },
        ShapeDef("square") { it.addRect(0.05f, 0.05f, 0.95f, 0.95f, Path.Direction.CW) },
        ShapeDef("round_rect") { it.addRoundRect(RectF(0f, 0.15f, 1f, 0.85f), 0.15f, 0.15f, Path.Direction.CW) },
        ShapeDef("circle") { it.addCircle(0.5f, 0.5f, 0.5f, Path.Direction.CW) },
        ShapeDef("ring", stroke = true) { it.addCircle(0.5f, 0.5f, 0.44f, Path.Direction.CW) },
        ShapeDef("frame", stroke = true) { it.addRect(0.06f, 0.06f, 0.94f, 0.94f, Path.Direction.CW) },
        ShapeDef("triangle") { poly(it, 0.5f, 0.05f, 0.98f, 0.92f, 0.02f, 0.92f) },
        ShapeDef("diamond") { poly(it, 0.5f, 0f, 1f, 0.5f, 0.5f, 1f, 0f, 0.5f) },
        ShapeDef("pentagon") { ngon(it, 5) },
        ShapeDef("hexagon") { ngon(it, 6) },
        ShapeDef("star") { star(it, 5, 0.2f) },
        ShapeDef("star4") { star(it, 4, 0.14f) },
        ShapeDef("burst") { star(it, 14, 0.38f) },
        ShapeDef("heart") {
            it.moveTo(0.5f, 0.92f)
            it.cubicTo(-0.25f, 0.45f, 0.2f, -0.1f, 0.5f, 0.28f)
            it.cubicTo(0.8f, -0.1f, 1.25f, 0.45f, 0.5f, 0.92f)
            it.close()
        },
        ShapeDef("speech") {
            it.addRoundRect(RectF(0f, 0.08f, 1f, 0.72f), 0.14f, 0.14f, Path.Direction.CW)
            poly(it, 0.2f, 0.7f, 0.42f, 0.7f, 0.18f, 0.96f)
        },
        ShapeDef("check") { poly(it, 0.05f, 0.55f, 0.2f, 0.4f, 0.4f, 0.6f, 0.82f, 0.12f, 0.97f, 0.27f, 0.4f, 0.9f) },
        ShapeDef("cross") {
            poly(it, 0.2f, 0.05f, 0.5f, 0.35f, 0.8f, 0.05f, 0.95f, 0.2f, 0.65f, 0.5f, 0.95f, 0.8f, 0.8f, 0.95f, 0.5f, 0.65f, 0.2f, 0.95f, 0.05f, 0.8f, 0.35f, 0.5f, 0.05f, 0.2f)
        },
        ShapeDef("plus") {
            poly(it, 0.38f, 0.05f, 0.62f, 0.05f, 0.62f, 0.38f, 0.95f, 0.38f, 0.95f, 0.62f, 0.62f, 0.62f, 0.62f, 0.95f, 0.38f, 0.95f, 0.38f, 0.62f, 0.05f, 0.62f, 0.05f, 0.38f, 0.38f, 0.38f)
        },
        ShapeDef("line") { it.addRect(0f, 0.47f, 1f, 0.53f, Path.Direction.CW) },
        ShapeDef("arrow_right") { poly(it, 0f, 0.38f, 0.6f, 0.38f, 0.6f, 0.15f, 1f, 0.5f, 0.6f, 0.85f, 0.6f, 0.62f, 0f, 0.62f) },
        ShapeDef("arrow_left") { poly(it, 1f, 0.38f, 0.4f, 0.38f, 0.4f, 0.15f, 0f, 0.5f, 0.4f, 0.85f, 0.4f, 0.62f, 1f, 0.62f) },
        ShapeDef("arrow_up") { poly(it, 0.38f, 1f, 0.38f, 0.4f, 0.15f, 0.4f, 0.5f, 0f, 0.85f, 0.4f, 0.62f, 0.4f, 0.62f, 1f) },
        ShapeDef("arrow_down") { poly(it, 0.38f, 0f, 0.38f, 0.6f, 0.15f, 0.6f, 0.5f, 1f, 0.85f, 0.6f, 0.62f, 0.6f, 0.62f, 0f) },
        ShapeDef("arrow_double") { poly(it, 0f, 0.5f, 0.28f, 0.18f, 0.28f, 0.4f, 0.72f, 0.4f, 0.72f, 0.18f, 1f, 0.5f, 0.72f, 0.82f, 0.72f, 0.6f, 0.28f, 0.6f, 0.28f, 0.82f) },
        ShapeDef("chevron") { poly(it, 0.2f, 0.05f, 0.75f, 0.5f, 0.2f, 0.95f, 0.05f, 0.8f, 0.42f, 0.5f, 0.05f, 0.2f) },
        ShapeDef("arrow_curved") {
            it.moveTo(0.05f, 0.9f)
            it.cubicTo(0.05f, 0.3f, 0.4f, 0.15f, 0.68f, 0.2f)
            it.lineTo(0.66f, 0.02f)
            it.lineTo(1f, 0.3f)
            it.lineTo(0.62f, 0.52f)
            it.lineTo(0.66f, 0.36f)
            it.cubicTo(0.45f, 0.32f, 0.22f, 0.45f, 0.2f, 0.9f)
            it.close()
        },
        ShapeDef("bolt") { poly(it, 0.6f, 0f, 0.15f, 0.56f, 0.45f, 0.56f, 0.36f, 1f, 0.85f, 0.4f, 0.55f, 0.4f) },
        ShapeDef("play") { poly(it, 0.15f, 0.05f, 0.95f, 0.5f, 0.15f, 0.95f) },
        ShapeDef("ribbon") { poly(it, 0f, 0.25f, 1f, 0.25f, 0.88f, 0.5f, 1f, 0.75f, 0f, 0.75f, 0.12f, 0.5f) },
        ShapeDef("tag") { poly(it, 0f, 0.2f, 0.7f, 0.2f, 1f, 0.5f, 0.7f, 0.8f, 0f, 0.8f) },

        ShapeDef("octagon") { ngon(it, 8) },
        ShapeDef("heptagon") { ngon(it, 7) },
        ShapeDef("star6") { star(it, 6, 0.28f) },
        ShapeDef("star8") { star(it, 8, 0.3f) },
        ShapeDef("star_thin") { star(it, 5, 0.12f) },
        ShapeDef("seal") { star(it, 24, 0.46f) },
        ShapeDef("semicircle") { it.moveTo(0f, 0.7f); it.arcTo(RectF(0f, 0.2f, 1f, 1.2f), 180f, 180f); it.close() },
        ShapeDef("quarter") { it.moveTo(0f, 1f); it.lineTo(0f, 0f); it.arcTo(RectF(-1f, 0f, 1f, 2f), 270f, 90f); it.close() },
        ShapeDef("trapezoid") { poly(it, 0.2f, 0.15f, 0.8f, 0.15f, 1f, 0.85f, 0f, 0.85f) },
        ShapeDef("parallelogram") { poly(it, 0.2f, 0.15f, 1f, 0.15f, 0.8f, 0.85f, 0f, 0.85f) },
        ShapeDef("pill") { it.addRoundRect(RectF(0f, 0.3f, 1f, 0.7f), 0.2f, 0.2f, Path.Direction.CW) },
        ShapeDef("cloud") {
            it.addCircle(0.3f, 0.58f, 0.2f, Path.Direction.CW); it.addCircle(0.52f, 0.42f, 0.26f, Path.Direction.CW)
            it.addCircle(0.76f, 0.58f, 0.2f, Path.Direction.CW); it.addRect(0.3f, 0.58f, 0.76f, 0.78f, Path.Direction.CW)
        },
        ShapeDef("drop") {
            it.moveTo(0.5f, 0f)
            it.cubicTo(0.6f, 0.2f, 0.95f, 0.45f, 0.85f, 0.7f); it.cubicTo(0.78f, 0.92f, 0.6f, 1f, 0.5f, 1f)
            it.cubicTo(0.4f, 1f, 0.22f, 0.92f, 0.15f, 0.7f); it.cubicTo(0.05f, 0.45f, 0.4f, 0.2f, 0.5f, 0f); it.close()
        },
        ShapeDef("moon") {
            val a = Path().apply { addCircle(0.5f, 0.5f, 0.5f, Path.Direction.CW) }
            val b = Path().apply { addCircle(0.74f, 0.4f, 0.4f, Path.Direction.CW) }
            it.op(a, b, Path.Op.DIFFERENCE)
        },
        ShapeDef("sun") {
            it.addCircle(0.5f, 0.5f, 0.24f, Path.Direction.CW)
            for (i in 0 until 12) {
                val a = (i * PI / 6).toFloat(); val d = 0.08f
                poly(it, 0.5f + 0.32f * cos(a - d), 0.5f + 0.32f * sin(a - d), 0.5f + 0.5f * cos(a), 0.5f + 0.5f * sin(a), 0.5f + 0.32f * cos(a + d), 0.5f + 0.32f * sin(a + d))
            }
        },
        ShapeDef("house") { poly(it, 0.5f, 0.05f, 1f, 0.45f, 0.85f, 0.45f, 0.85f, 0.95f, 0.15f, 0.95f, 0.15f, 0.45f, 0f, 0.45f) },
        ShapeDef("pin") { it.addCircle(0.5f, 0.36f, 0.32f, Path.Direction.CW); poly(it, 0.26f, 0.55f, 0.74f, 0.55f, 0.5f, 0.99f) },
        ShapeDef("shield") {
            it.moveTo(0.5f, 0.02f); it.lineTo(0.95f, 0.15f); it.cubicTo(0.95f, 0.65f, 0.8f, 0.85f, 0.5f, 0.98f)
            it.cubicTo(0.2f, 0.85f, 0.05f, 0.65f, 0.05f, 0.15f); it.close()
        },
        ShapeDef("flower") {
            for (i in 0 until 6) { val a = (i * PI / 3).toFloat(); it.addCircle(0.5f + 0.26f * cos(a), 0.5f + 0.26f * sin(a), 0.2f, Path.Direction.CW) }
            it.addCircle(0.5f, 0.5f, 0.16f, Path.Direction.CW)
        },
        ShapeDef("flag") { it.addRect(0.08f, 0.05f, 0.14f, 0.98f, Path.Direction.CW); poly(it, 0.14f, 0.1f, 0.95f, 0.1f, 0.78f, 0.33f, 0.95f, 0.56f, 0.14f, 0.56f) },
        ShapeDef("bookmark") { poly(it, 0.2f, 0.02f, 0.8f, 0.02f, 0.8f, 0.98f, 0.5f, 0.72f, 0.2f, 0.98f) },
        ShapeDef("crown") { poly(it, 0.05f, 0.8f, 0.05f, 0.25f, 0.28f, 0.5f, 0.5f, 0.15f, 0.72f, 0.5f, 0.95f, 0.25f, 0.95f, 0.8f) },
        ShapeDef("speech_round") { it.addOval(RectF(0f, 0.05f, 1f, 0.7f), Path.Direction.CW); poly(it, 0.25f, 0.62f, 0.45f, 0.66f, 0.2f, 0.95f) },
        ShapeDef("thought") {
            it.addCircle(0.3f, 0.4f, 0.2f, Path.Direction.CW); it.addCircle(0.52f, 0.28f, 0.24f, Path.Direction.CW); it.addCircle(0.74f, 0.42f, 0.2f, Path.Direction.CW)
            it.addRect(0.3f, 0.4f, 0.74f, 0.58f, Path.Direction.CW); it.addCircle(0.3f, 0.76f, 0.07f, Path.Direction.CW); it.addCircle(0.18f, 0.9f, 0.04f, Path.Direction.CW)
        },
        ShapeDef("wave") {
            val n = 24
            for (i in 0..n) { val x = i / n.toFloat(); val y = 0.35f + 0.12f * sin(4 * PI * x).toFloat(); if (i == 0) it.moveTo(x, y) else it.lineTo(x, y) }
            for (i in n downTo 0) { val x = i / n.toFloat(); it.lineTo(x, 0.65f + 0.12f * sin(4 * PI * x).toFloat()) }
            it.close()
        },
        ShapeDef("cursor") { poly(it, 0.2f, 0.05f, 0.2f, 0.85f, 0.4f, 0.68f, 0.55f, 0.98f, 0.7f, 0.9f, 0.55f, 0.62f, 0.82f, 0.62f) },
        ShapeDef("hexagon_ring", stroke = true) { ngon(it, 6) },
        ShapeDef("triangle_ring", stroke = true) { poly(it, 0.5f, 0.06f, 0.96f, 0.92f, 0.04f, 0.92f) },
        ShapeDef("diamond_ring", stroke = true) { poly(it, 0.5f, 0.02f, 0.98f, 0.5f, 0.5f, 0.98f, 0.02f, 0.5f) },
        ShapeDef("star_ring", stroke = true) { star(it, 5, 0.2f) },
        ShapeDef("heart_ring", stroke = true) {
            it.moveTo(0.5f, 0.92f); it.cubicTo(-0.25f, 0.45f, 0.2f, -0.1f, 0.5f, 0.28f); it.cubicTo(0.8f, -0.1f, 1.25f, 0.45f, 0.5f, 0.92f); it.close()
        },
        ShapeDef("round_frame", stroke = true) { it.addRoundRect(RectF(0.06f, 0.06f, 0.94f, 0.94f), 0.18f, 0.18f, Path.Direction.CW) },
        ShapeDef("target", stroke = true) { it.addCircle(0.5f, 0.5f, 0.46f, Path.Direction.CW); it.addCircle(0.5f, 0.5f, 0.3f, Path.Direction.CW); it.addCircle(0.5f, 0.5f, 0.14f, Path.Direction.CW) },
        ShapeDef("corners", stroke = true) {
            it.moveTo(0.05f, 0.3f); it.lineTo(0.05f, 0.05f); it.lineTo(0.3f, 0.05f)
            it.moveTo(0.7f, 0.05f); it.lineTo(0.95f, 0.05f); it.lineTo(0.95f, 0.3f)
            it.moveTo(0.95f, 0.7f); it.lineTo(0.95f, 0.95f); it.lineTo(0.7f, 0.95f)
            it.moveTo(0.3f, 0.95f); it.lineTo(0.05f, 0.95f); it.lineTo(0.05f, 0.7f)
        },
        ShapeDef("bracket_l", stroke = true) { it.moveTo(0.7f, 0.05f); it.lineTo(0.3f, 0.05f); it.lineTo(0.3f, 0.95f); it.lineTo(0.7f, 0.95f) },
        ShapeDef("bracket_r", stroke = true) { it.moveTo(0.3f, 0.05f); it.lineTo(0.7f, 0.05f); it.lineTo(0.7f, 0.95f); it.lineTo(0.3f, 0.95f) },
        ShapeDef("arrow_notch") { poly(it, 0f, 0.2f, 0.65f, 0.2f, 1f, 0.5f, 0.65f, 0.8f, 0f, 0.8f, 0.2f, 0.5f) },
        ShapeDef("arrow_block_up") { poly(it, 0.5f, 0f, 1f, 0.5f, 0.72f, 0.5f, 0.72f, 1f, 0.28f, 1f, 0.28f, 0.5f, 0f, 0.5f) },

        // ---- Segunda tanda (24)
        ShapeDef("leaf") {
            it.moveTo(0.08f, 0.92f); it.cubicTo(0.02f, 0.35f, 0.4f, 0.04f, 0.96f, 0.04f)
            it.cubicTo(0.96f, 0.62f, 0.64f, 0.98f, 0.08f, 0.92f); it.close()
        },
        ShapeDef("clover") {
            it.addCircle(0.5f, 0.28f, 0.22f, Path.Direction.CW); it.addCircle(0.5f, 0.72f, 0.22f, Path.Direction.CW)
            it.addCircle(0.28f, 0.5f, 0.22f, Path.Direction.CW); it.addCircle(0.72f, 0.5f, 0.22f, Path.Direction.CW)
            it.addCircle(0.5f, 0.5f, 0.2f, Path.Direction.CW)
        },
        ShapeDef("gear") {
            val a = Path()
            val teeth = 8
            for (i in 0 until teeth) {
                val b = (i * 2 * PI / teeth).toFloat()
                val offs = floatArrayOf(-0.14f, 0.14f, 0.26f, 0.52f)
                val rad = floatArrayOf(0.5f, 0.5f, 0.38f, 0.38f)
                for (k in 0 until 4) {
                    val x = 0.5f + rad[k] * cos(b + offs[k]); val y = 0.5f + rad[k] * sin(b + offs[k])
                    if (i == 0 && k == 0) a.moveTo(x, y) else a.lineTo(x, y)
                }
            }
            a.close()
            val hole = Path().apply { addCircle(0.5f, 0.5f, 0.17f, Path.Direction.CW) }
            it.op(a, hole, Path.Op.DIFFERENCE)
        },
        ShapeDef("camera") {
            val a = Path().apply { addRoundRect(RectF(0.02f, 0.25f, 0.98f, 0.88f), 0.08f, 0.08f, Path.Direction.CW); poly(this, 0.3f, 0.12f, 0.7f, 0.12f, 0.76f, 0.27f, 0.24f, 0.27f) }
            val hole = Path().apply { addCircle(0.5f, 0.57f, 0.2f, Path.Direction.CW) }
            it.op(a, hole, Path.Op.DIFFERENCE)
        },
        ShapeDef("music_note") {
            it.addOval(RectF(0.08f, 0.68f, 0.52f, 0.94f), Path.Direction.CW); it.addRect(0.44f, 0.1f, 0.52f, 0.8f, Path.Direction.CW)
            it.moveTo(0.52f, 0.1f); it.cubicTo(0.72f, 0.2f, 0.92f, 0.3f, 0.88f, 0.58f); it.cubicTo(0.82f, 0.42f, 0.7f, 0.36f, 0.52f, 0.32f); it.close()
        },
        ShapeDef("eye") {
            val a = Path().apply {
                moveTo(0f, 0.5f); cubicTo(0.25f, 0.12f, 0.75f, 0.12f, 1f, 0.5f); cubicTo(0.75f, 0.88f, 0.25f, 0.88f, 0f, 0.5f); close()
            }
            val hole = Path().apply { addCircle(0.5f, 0.5f, 0.17f, Path.Direction.CW) }
            it.op(a, hole, Path.Op.DIFFERENCE)
        },
        ShapeDef("lock") {
            val a = Path().apply { addRoundRect(RectF(0.15f, 0.45f, 0.85f, 0.96f), 0.08f, 0.08f, Path.Direction.CW); addRoundRect(RectF(0.27f, 0.06f, 0.73f, 0.6f), 0.23f, 0.23f, Path.Direction.CW) }
            val inner = Path().apply { addRoundRect(RectF(0.38f, 0.17f, 0.62f, 0.45f), 0.12f, 0.12f, Path.Direction.CW) }
            it.op(a, inner, Path.Op.DIFFERENCE)
        },
        ShapeDef("ticket") {
            val a = Path().apply { addRoundRect(RectF(0f, 0.2f, 1f, 0.8f), 0.06f, 0.06f, Path.Direction.CW) }
            val holes = Path().apply { addCircle(0f, 0.5f, 0.1f, Path.Direction.CW); addCircle(1f, 0.5f, 0.1f, Path.Direction.CW) }
            it.op(a, holes, Path.Op.DIFFERENCE)
        },
        ShapeDef("umbrella") {
            it.moveTo(0f, 0.52f); it.arcTo(RectF(0f, 0.02f, 1f, 1.02f), 180f, 180f); it.close()
            it.addRect(0.47f, 0.5f, 0.53f, 0.84f, Path.Direction.CW); it.addCircle(0.4f, 0.86f, 0.08f, Path.Direction.CW)
        },
        ShapeDef("snowflake", stroke = true) {
            for (i in 0 until 3) {
                val a = (i * PI / 3).toFloat()
                it.moveTo(0.5f + 0.46f * cos(a), 0.5f + 0.46f * sin(a)); it.lineTo(0.5f - 0.46f * cos(a), 0.5f - 0.46f * sin(a))
            }
        },
        ShapeDef("cube", stroke = true) {
            ngon(it, 6)
            for (k in intArrayOf(1, 3, 5)) { val a = (-PI / 2 + k * PI / 3).toFloat(); it.moveTo(0.5f, 0.5f); it.lineTo(0.5f + 0.5f * cos(a), 0.5f + 0.5f * sin(a)) }
        },
        ShapeDef("trophy") {
            poly(it, 0.2f, 0.05f, 0.8f, 0.05f, 0.75f, 0.45f, 0.6f, 0.62f, 0.4f, 0.62f, 0.25f, 0.45f)
            it.addRect(0.45f, 0.6f, 0.55f, 0.8f, Path.Direction.CW); it.addRect(0.28f, 0.8f, 0.72f, 0.93f, Path.Direction.CW)
        },
        ShapeDef("lightbulb") {
            it.addCircle(0.5f, 0.36f, 0.32f, Path.Direction.CW); it.addRect(0.37f, 0.58f, 0.63f, 0.78f, Path.Direction.CW); it.addRect(0.4f, 0.8f, 0.6f, 0.93f, Path.Direction.CW)
        },
        ShapeDef("paw") {
            it.addOval(RectF(0.24f, 0.5f, 0.76f, 0.94f), Path.Direction.CW)
            it.addCircle(0.16f, 0.42f, 0.1f, Path.Direction.CW); it.addCircle(0.37f, 0.24f, 0.11f, Path.Direction.CW)
            it.addCircle(0.63f, 0.24f, 0.11f, Path.Direction.CW); it.addCircle(0.84f, 0.42f, 0.1f, Path.Direction.CW)
        },
        ShapeDef("rocket") {
            val a = Path().apply {
                moveTo(0.5f, 0.02f); cubicTo(0.76f, 0.2f, 0.76f, 0.55f, 0.66f, 0.76f); lineTo(0.34f, 0.76f); cubicTo(0.24f, 0.55f, 0.24f, 0.2f, 0.5f, 0.02f); close()
                poly(this, 0.35f, 0.55f, 0.14f, 0.82f, 0.35f, 0.78f); poly(this, 0.65f, 0.55f, 0.86f, 0.82f, 0.65f, 0.78f); poly(this, 0.42f, 0.78f, 0.5f, 0.99f, 0.58f, 0.78f)
            }
            val hole = Path().apply { addCircle(0.5f, 0.38f, 0.09f, Path.Direction.CW) }
            it.op(a, hole, Path.Op.DIFFERENCE)
        },
        ShapeDef("mountain") { poly(it, 0f, 0.92f, 0.35f, 0.3f, 0.5f, 0.55f, 0.7f, 0.12f, 1f, 0.92f) },
        ShapeDef("wave_line", stroke = true) {
            val n = 32
            for (i in 0..n) { val x = 0.04f + 0.92f * i / n; val y = 0.5f + 0.3f * sin(3 * PI * i / n).toFloat(); if (i == 0) it.moveTo(x, y) else it.lineTo(x, y) }
        },
        ShapeDef("spiral", stroke = true) {
            val n = 120
            for (k in 0..n) { val t = k / n.toFloat(); val a = (t * 4 * PI).toFloat(); val r = 0.04f + 0.44f * t; val x = 0.5f + r * cos(a); val y = 0.5f + r * sin(a); if (k == 0) it.moveTo(x, y) else it.lineTo(x, y) }
        },
        ShapeDef("zigzag", stroke = true) { it.moveTo(0.04f, 0.7f); it.lineTo(0.22f, 0.3f); it.lineTo(0.4f, 0.7f); it.lineTo(0.58f, 0.3f); it.lineTo(0.76f, 0.7f); it.lineTo(0.96f, 0.3f) },
        ShapeDef("dots") { it.addCircle(0.15f, 0.5f, 0.12f, Path.Direction.CW); it.addCircle(0.5f, 0.5f, 0.12f, Path.Direction.CW); it.addCircle(0.85f, 0.5f, 0.12f, Path.Direction.CW) },
        ShapeDef("hash") {
            it.addRect(0.3f, 0.06f, 0.4f, 0.94f, Path.Direction.CW); it.addRect(0.6f, 0.06f, 0.7f, 0.94f, Path.Direction.CW)
            it.addRect(0.06f, 0.3f, 0.94f, 0.4f, Path.Direction.CW); it.addRect(0.06f, 0.6f, 0.94f, 0.7f, Path.Direction.CW)
        },
        ShapeDef("infinity", stroke = true) {
            val n = 64
            for (k in 0..n) {
                val t = (k * 2 * PI / n).toFloat(); val d = 1f + sin(t) * sin(t)
                val x = 0.5f + 0.46f * cos(t) / d; val y = 0.5f + 0.46f * sin(t) * cos(t) / d
                if (k == 0) it.moveTo(x, y) else it.lineTo(x, y)
            }
            it.close()
        },
        ShapeDef("arc", stroke = true) { it.addArc(RectF(0.06f, 0.06f, 0.94f, 0.94f), -225f, 270f) },
        ShapeDef("hourglass") { poly(it, 0.15f, 0.05f, 0.85f, 0.05f, 0.5f, 0.5f); poly(it, 0.15f, 0.95f, 0.85f, 0.95f, 0.5f, 0.5f) },

        // ---- Tercera tanda (28): primero cuatro contornos y después 24 siluetas
        ShapeDef("octagon_ring", stroke = true) { ngon(it, 8) },
        ShapeDef("star6_ring", stroke = true) { star(it, 6, 0.28f) },
        ShapeDef("dashed_ring", stroke = true) { for (i in 0 until 12) it.addArc(RectF(0.06f, 0.06f, 0.94f, 0.94f), i * 30f, 18f) },
        ShapeDef("pentagon_ring", stroke = true) { ngon(it, 5) },
        ShapeDef("butterfly") {
            it.addOval(RectF(0.02f, 0.08f, 0.5f, 0.55f), Path.Direction.CW); it.addOval(RectF(0.5f, 0.08f, 0.98f, 0.55f), Path.Direction.CW)
            it.addOval(RectF(0.1f, 0.5f, 0.5f, 0.92f), Path.Direction.CW); it.addOval(RectF(0.5f, 0.5f, 0.9f, 0.92f), Path.Direction.CW)
            it.addRoundRect(RectF(0.46f, 0.2f, 0.54f, 0.88f), 0.04f, 0.04f, Path.Direction.CW)
        },
        ShapeDef("ghost") {
            val a = Path().apply {
                moveTo(0.1f, 0.95f); lineTo(0.1f, 0.4f); arcTo(RectF(0.1f, 0.05f, 0.9f, 0.75f), 180f, 180f); lineTo(0.9f, 0.95f)
                lineTo(0.75f, 0.82f); lineTo(0.62f, 0.95f); lineTo(0.5f, 0.82f); lineTo(0.38f, 0.95f); lineTo(0.25f, 0.82f); close()
            }
            val eyes = Path().apply { addCircle(0.36f, 0.42f, 0.07f, Path.Direction.CW); addCircle(0.64f, 0.42f, 0.07f, Path.Direction.CW) }
            it.op(a, eyes, Path.Op.DIFFERENCE)
        },
        ShapeDef("smiley") {
            val a = Path().apply { addCircle(0.5f, 0.5f, 0.5f, Path.Direction.CW) }
            val holes = Path().apply {
                addCircle(0.34f, 0.38f, 0.07f, Path.Direction.CW); addCircle(0.66f, 0.38f, 0.07f, Path.Direction.CW)
                moveTo(0.25f, 0.6f); cubicTo(0.35f, 0.88f, 0.65f, 0.88f, 0.75f, 0.6f); cubicTo(0.65f, 0.74f, 0.35f, 0.74f, 0.25f, 0.6f); close()
            }
            it.op(a, holes, Path.Op.DIFFERENCE)
        },
        ShapeDef("bell") {
            it.moveTo(0.5f, 0.05f); it.cubicTo(0.8f, 0.05f, 0.82f, 0.45f, 0.85f, 0.7f); it.lineTo(0.95f, 0.8f); it.lineTo(0.05f, 0.8f); it.lineTo(0.15f, 0.7f)
            it.cubicTo(0.18f, 0.45f, 0.2f, 0.05f, 0.5f, 0.05f); it.close(); it.addCircle(0.5f, 0.89f, 0.09f, Path.Direction.CW)
        },
        ShapeDef("key") {
            val a = Path().apply {
                addCircle(0.28f, 0.5f, 0.24f, Path.Direction.CW); addRect(0.46f, 0.45f, 0.98f, 0.55f, Path.Direction.CW)
                addRect(0.8f, 0.55f, 0.87f, 0.72f, Path.Direction.CW); addRect(0.9f, 0.55f, 0.97f, 0.68f, Path.Direction.CW)
            }
            val hole = Path().apply { addCircle(0.28f, 0.5f, 0.1f, Path.Direction.CW) }
            it.op(a, hole, Path.Op.DIFFERENCE)
        },
        ShapeDef("mushroom") {
            val a = Path().apply { moveTo(0.04f, 0.58f); arcTo(RectF(0.04f, 0.04f, 0.96f, 1.12f), 180f, 180f); close(); addRoundRect(RectF(0.34f, 0.56f, 0.66f, 0.96f), 0.1f, 0.1f, Path.Direction.CW) }
            val dots = Path().apply { addCircle(0.3f, 0.38f, 0.07f, Path.Direction.CW); addCircle(0.58f, 0.24f, 0.06f, Path.Direction.CW); addCircle(0.72f, 0.42f, 0.06f, Path.Direction.CW) }
            it.op(a, dots, Path.Op.DIFFERENCE)
        },
        ShapeDef("cat") {
            val a = Path().apply {
                addOval(RectF(0.1f, 0.3f, 0.9f, 0.94f), Path.Direction.CW)
                poly(this, 0.12f, 0.45f, 0.16f, 0.04f, 0.44f, 0.3f); poly(this, 0.88f, 0.45f, 0.84f, 0.04f, 0.56f, 0.3f)
            }
            val eyes = Path().apply { addCircle(0.36f, 0.55f, 0.06f, Path.Direction.CW); addCircle(0.64f, 0.55f, 0.06f, Path.Direction.CW) }
            it.op(a, eyes, Path.Op.DIFFERENCE)
        },
        ShapeDef("fish") {
            val a = Path().apply { addOval(RectF(0.02f, 0.22f, 0.76f, 0.78f), Path.Direction.CW); poly(this, 0.68f, 0.5f, 0.98f, 0.2f, 0.98f, 0.8f) }
            val eye = Path().apply { addCircle(0.22f, 0.45f, 0.05f, Path.Direction.CW) }
            it.op(a, eye, Path.Op.DIFFERENCE)
        },
        ShapeDef("apple") {
            it.addCircle(0.35f, 0.58f, 0.32f, Path.Direction.CW); it.addCircle(0.65f, 0.58f, 0.32f, Path.Direction.CW)
            it.addRect(0.48f, 0.08f, 0.54f, 0.32f, Path.Direction.CW); poly(it, 0.54f, 0.14f, 0.82f, 0.04f, 0.64f, 0.3f)
        },
        ShapeDef("gem") { poly(it, 0.24f, 0.12f, 0.76f, 0.12f, 1f, 0.4f, 0.5f, 0.96f, 0f, 0.4f) },
        ShapeDef("battery") {
            val a = Path().apply { addRoundRect(RectF(0.04f, 0.24f, 0.88f, 0.76f), 0.06f, 0.06f, Path.Direction.CW); addRect(0.88f, 0.4f, 0.97f, 0.6f, Path.Direction.CW) }
            val bar = Path().apply { addRect(0.12f, 0.33f, 0.56f, 0.67f, Path.Direction.CW) }
            it.op(a, bar, Path.Op.DIFFERENCE)
        },
        ShapeDef("wifi", stroke = true) {
            for (r in floatArrayOf(0.48f, 0.33f, 0.18f)) it.addArc(RectF(0.5f - r, 0.9f - r, 0.5f + r, 0.9f + r), -135f, 90f)
            it.addCircle(0.5f, 0.88f, 0.03f, Path.Direction.CW)
        },
        ShapeDef("bar_chart") {
            it.addRect(0.05f, 0.6f, 0.25f, 0.95f, Path.Direction.CW); it.addRect(0.29f, 0.4f, 0.49f, 0.95f, Path.Direction.CW)
            it.addRect(0.53f, 0.22f, 0.73f, 0.95f, Path.Direction.CW); it.addRect(0.77f, 0.05f, 0.97f, 0.95f, Path.Direction.CW)
        },
        ShapeDef("pie") { it.moveTo(0.5f, 0.5f); it.arcTo(RectF(0f, 0f, 1f, 1f), -90f, 270f); it.close() },
        ShapeDef("envelope", stroke = true) {
            it.addRect(0.04f, 0.2f, 0.96f, 0.8f, Path.Direction.CW)
            it.moveTo(0.04f, 0.2f); it.lineTo(0.5f, 0.56f); it.lineTo(0.96f, 0.2f)
        },
        ShapeDef("speech_dots") {
            val a = Path().apply { addRoundRect(RectF(0f, 0.08f, 1f, 0.72f), 0.14f, 0.14f, Path.Direction.CW); poly(this, 0.2f, 0.7f, 0.42f, 0.7f, 0.18f, 0.96f) }
            val dots = Path().apply { for (x in floatArrayOf(0.25f, 0.5f, 0.75f)) addCircle(x, 0.4f, 0.06f, Path.Direction.CW) }
            it.op(a, dots, Path.Op.DIFFERENCE)
        },
        ShapeDef("honeycomb", stroke = true) {
            for ((cx, cy) in listOf(0.3f to 0.34f, 0.7f to 0.34f, 0.5f to 0.7f)) {
                for (i in 0 until 6) {
                    val a = (PI / 6 + i * PI / 3).toFloat(); val x = cx + 0.22f * cos(a); val y = cy + 0.22f * sin(a)
                    if (i == 0) it.moveTo(x, y) else it.lineTo(x, y)
                }
                it.close()
            }
        },
        ShapeDef("sparkle") {
            it.moveTo(0.5f, 0f); it.quadTo(0.5f, 0.5f, 1f, 0.5f); it.quadTo(0.5f, 0.5f, 0.5f, 1f); it.quadTo(0.5f, 0.5f, 0f, 0.5f); it.quadTo(0.5f, 0.5f, 0.5f, 0f); it.close()
        },
        ShapeDef("bubbles", stroke = true) { it.addCircle(0.36f, 0.62f, 0.3f, Path.Direction.CW); it.addCircle(0.74f, 0.3f, 0.2f, Path.Direction.CW); it.addCircle(0.78f, 0.78f, 0.1f, Path.Direction.CW) },
        ShapeDef("refresh", stroke = true) { it.addArc(RectF(0.1f, 0.1f, 0.9f, 0.9f), 30f, 290f); poly(it, 0.62f, 0.02f, 0.94f, 0.18f, 0.66f, 0.4f) },
        ShapeDef("tree") {
            poly(it, 0.5f, 0.02f, 0.85f, 0.4f, 0.15f, 0.4f); poly(it, 0.5f, 0.25f, 0.95f, 0.72f, 0.05f, 0.72f); it.addRect(0.44f, 0.72f, 0.56f, 0.96f, Path.Direction.CW)
        },
        ShapeDef("boat") {
            poly(it, 0.05f, 0.7f, 0.95f, 0.7f, 0.78f, 0.93f, 0.22f, 0.93f); poly(it, 0.46f, 0.05f, 0.46f, 0.62f, 0.14f, 0.62f); poly(it, 0.54f, 0.22f, 0.54f, 0.62f, 0.86f, 0.62f)
        },
        ShapeDef("paper_plane") { poly(it, 0.02f, 0.45f, 0.98f, 0.05f, 0.65f, 0.95f, 0.5f, 0.62f) },
        ShapeDef("bowtie") { poly(it, 0f, 0.2f, 0.5f, 0.5f, 0f, 0.8f); poly(it, 1f, 0.2f, 0.5f, 0.5f, 1f, 0.8f) },
    )

    fun get(id: String): ShapeDef? = all.firstOrNull { it.id == id } ?: ShapeLib.def(id)
}
