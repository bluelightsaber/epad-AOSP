package org.epad.launcher

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF

/** Iconos de trazo dibujados en una rejilla de 24×24 (como los de los mockups), sin recursos. */
enum class Icon { SUN, PAGE, MIXED, GLOBE, WIFI, BT, ROTATE, SPEAKER, BELL, GEAR, CLOUD, RAIN, SNOW, FOG, STORM, CLEAR }

object Icons {
    fun draw(c: Canvas, icon: Icon, x: Float, y: Float, size: Float, paint: Paint) {
        c.save()
        c.translate(x, y)
        c.scale(size / 24f, size / 24f)
        val p = Path()
        when (icon) {
            Icon.SUN, Icon.CLEAR -> {
                p.addCircle(12f, 12f, 4f, Path.Direction.CW)
                rays(p, 12f, 12f, 7f, 10f)
            }
            Icon.PAGE -> {
                p.addRoundRect(RectF(4f, 3f, 20f, 21f), 2f, 2f, Path.Direction.CW)
                line(p, 8f, 8f, 16f, 8f); line(p, 8f, 12f, 16f, 12f); line(p, 8f, 16f, 13f, 16f)
            }
            Icon.MIXED -> {
                p.addRoundRect(RectF(4f, 3f, 20f, 21f), 2f, 2f, Path.Direction.CW)
                line(p, 7.5f, 8f, 11f, 8f); line(p, 7.5f, 12f, 11f, 12f); line(p, 7.5f, 16f, 11f, 16f)
                p.addCircle(15f, 8f, 1.3f, Path.Direction.CW)
                p.moveTo(13f, 17f); p.lineTo(15f, 12f); p.lineTo(17f, 17f); p.close()
            }
            Icon.GLOBE -> {
                p.addCircle(12f, 12f, 9f, Path.Direction.CW)
                p.addOval(RectF(8f, 3f, 16f, 21f), Path.Direction.CW)
                line(p, 3f, 12f, 21f, 12f)
            }
            Icon.WIFI -> {
                p.addArc(RectF(2f, 4f, 22f, 24f), 225f, 90f)
                p.addArc(RectF(5f, 7.5f, 19f, 21.5f), 225f, 90f)
                p.addArc(RectF(8.5f, 11f, 15.5f, 18f), 225f, 90f)
                p.addCircle(12f, 19.5f, 1f, Path.Direction.CW)
            }
            Icon.BT -> {
                p.moveTo(7f, 7f); p.lineTo(17f, 17f); p.lineTo(12f, 22f); p.lineTo(12f, 2f)
                p.lineTo(17f, 7f); p.lineTo(7f, 17f)
            }
            Icon.ROTATE -> {
                p.addArc(RectF(4f, 4f, 20f, 20f), -60f, 300f)
                p.moveTo(16f, 2.5f); p.lineTo(16.5f, 7.5f); p.lineTo(21.5f, 6.5f)
            }
            Icon.SPEAKER -> {
                p.moveTo(3f, 9f); p.lineTo(7f, 9f); p.lineTo(12f, 4f); p.lineTo(12f, 20f); p.lineTo(7f, 15f)
                p.lineTo(3f, 15f); p.close()
                p.addArc(RectF(10f, 8f, 18f, 16f), -50f, 100f)
                p.addArc(RectF(8f, 4f, 22f, 20f), -50f, 100f)
            }
            Icon.BELL -> {
                p.moveTo(6f, 17f); p.lineTo(6f, 9f); p.arcTo(RectF(6f, 3f, 18f, 15f), 180f, 180f)
                p.lineTo(18f, 17f); p.lineTo(20f, 18f); p.lineTo(4f, 18f); p.close()
                p.addArc(RectF(10f, 19f, 14f, 23f), 0f, 180f)
            }
            Icon.GEAR -> {
                p.addCircle(12f, 12f, 3f, Path.Direction.CW)
                p.addCircle(12f, 12f, 7f, Path.Direction.CW)
                rays(p, 12f, 12f, 7f, 10f)
            }
            Icon.CLOUD -> cloud(p)
            Icon.RAIN -> { cloud(p); line(p, 9f, 20f, 8f, 23f); line(p, 13f, 20f, 12f, 23f); line(p, 17f, 20f, 16f, 23f) }
            Icon.SNOW -> { cloud(p); dot(p, 9f, 21f); dot(p, 13f, 22f); dot(p, 17f, 21f) }
            Icon.FOG -> { line(p, 3f, 9f, 21f, 9f); line(p, 5f, 13f, 19f, 13f); line(p, 3f, 17f, 21f, 17f) }
            Icon.STORM -> { cloud(p); p.moveTo(13f, 18f); p.lineTo(10f, 22f); p.lineTo(14f, 22f); p.lineTo(11f, 26f) }
        }
        c.drawPath(p, paint)
        c.restore()
    }

    fun forWeather(code: Int): Icon = when (code) {
        0 -> Icon.CLEAR
        1, 2, 3 -> Icon.CLOUD
        45, 48 -> Icon.FOG
        in 51..67, in 80..82 -> Icon.RAIN
        in 71..77, 85, 86 -> Icon.SNOW
        in 95..99 -> Icon.STORM
        else -> Icon.CLOUD
    }

    private fun line(p: Path, x1: Float, y1: Float, x2: Float, y2: Float) {
        p.moveTo(x1, y1); p.lineTo(x2, y2)
    }

    private fun dot(p: Path, x: Float, y: Float) = p.addCircle(x, y, 0.8f, Path.Direction.CW)

    private fun rays(p: Path, cx: Float, cy: Float, r1: Float, r2: Float) {
        for (i in 0 until 8) {
            val a = Math.toRadians(i * 45.0)
            val (s, co) = Math.sin(a).toFloat() to Math.cos(a).toFloat()
            line(p, cx + co * r1, cy + s * r1, cx + co * r2, cy + s * r2)
        }
    }

    private fun cloud(p: Path) {
        p.moveTo(7f, 19f)
        p.arcTo(RectF(3f, 11f, 11f, 19f), 90f, 180f)
        p.arcTo(RectF(6f, 5f, 17f, 16f), 200f, 150f)
        p.arcTo(RectF(13f, 9f, 21f, 19f), 270f, 180f)
        p.close()
    }
}
