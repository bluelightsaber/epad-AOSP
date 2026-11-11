package org.epad.launcher

import android.content.Context
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.view.View
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.random.Random

/**
 * El tiempo como fondo muy tenue de toda la pantalla (mockup E): icono grande de trazo fino, temperatura grande,
 * y abajo la curva de temperatura (continua), la probabilidad de lluvia (discontinua) y la lluvia en mm (barras)
 * de las próximas horas, con sus valores. Un gris claro en E-Ink queda como marca de agua.
 *
 * Animación opcional ([play]): «stop motion» de [FRAMES] fotogramas de [FRAME_MS] (lo que tarda en pintarse la
 * tinta en modo rápido). Primero se trazan las curvas de izquierda a derecha y crecen las barras; luego el cielo se
 * mueve según el tiempo (lluvia que salpica en la gráfica, nieve que oscila, rayos que giran, nubes, niebla,
 * relámpago). Mientras dura, todo va en gris oscuro (el modo rápido es casi blanco y negro); al acabar vuelve el
 * gris tenue: la tinta «se seca».
 */
class WeatherBackground(ctx: Context) : View(ctx) {
    var forecast: Forecast? = null
        set(v) { field = v; invalidate() }

    /** Banda vertical (en px de la vista) donde va el gráfico, para que no la tape la barra de avisos. */
    var chartTop = 0f
    var chartBottom = 0f
    /** Borde derecho de la gráfica en vertical (el índice de letras); 0 = ancho completo. */
    var chartRight = 0f

    /** Apaisado: hueco junto al reloj donde va todo el tiempo en pequeño; null = composición de pantalla completa. */
    var landscapeBox: RectF? = null
        set(v) { if (v != field) { field = v; invalidate() } }

    private val faint = 0xFFE0E0E0.toInt()
    private val faintText = 0xFF8C8C8C.toInt()
    private val ink = 0xFF505050.toInt()
    private var tint = faint
    private var tintText = faintText

    private val ui = Handler(Looper.getMainLooper())
    private var frame = -1          // -1: sin animación (dibujo estático)
    private var onEnd: (() -> Unit)? = null
    val playing get() = frame >= 0
    private val dp = resources.displayMetrics.density

    private val textPath = Path()

    /**
     * Texto como trazado: con el «texto de alto contraste» del sistema (activado por omisión para la tinta),
     * drawText pinta el texto claro en blanco con contorno negro; en la temperatura gigante ese contorno era un
     * halo negro gruesísimo. Los trazados no se tocan.
     */
    private fun Canvas.text(s: String, x: Float, y: Float, p: Paint) {
        p.getTextPath(s, 0, s.length, x, y, textPath)
        drawPath(textPath, p)
    }

    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; color = tint; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
    }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = tint }
    private val bigText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = tint; typeface = Typeface.create("sans-serif-thin", Typeface.NORMAL); textAlign = Paint.Align.RIGHT
    }
    private val small = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = tintText; textAlign = Paint.Align.CENTER }

    /** Reproduce la animación una vez; [end] se llama al terminar o al pararla. */
    fun play(end: () -> Unit) {
        if (forecast == null || width == 0) { end(); return }
        stop()
        onEnd = end
        frame = 0
        setInk(true)
        ui.post(step)
    }

    fun stop() {
        ui.removeCallbacks(step)
        if (frame >= 0) {
            frame = -1
            setInk(false)
            invalidate()
            onEnd?.invoke()
            onEnd = null
        }
    }

    private val step = object : Runnable {
        override fun run() {
            if (frame >= FRAMES) { stop(); return }
            invalidate()
            frame++
            ui.postDelayed(this, FRAME_MS)
        }
    }

    private fun setInk(on: Boolean) {
        tint = if (on) ink else faint
        tintText = if (on) ink else faintText
        stroke.color = tint; fill.color = tint; bigText.color = tint; small.color = tintText
    }

    /** Avance del trazado de curvas (0..1) en los primeros fotogramas. */
    private val trace get() = if (frame < 0) 1f else (frame / TRACE_FRAMES.toFloat()).coerceAtMost(1f)

    override fun onDraw(c: Canvas) {
        val f = forecast ?: return
        val w = width.toFloat()
        val h = height.toFloat()
        if (frame >= 0) sky(c, f.code, w, h)

        val box = landscapeBox
        // el icono se dibuja en unidades de 24: el trazo se compensa para que mida ~3 dp
        val (ix, iy, iconSize) = iconBox(w, h)
        stroke.pathEffect = null
        stroke.strokeWidth = 3f * dp * 24f / iconSize
        Icons.draw(c, Icons.forWeather(f.code), ix, iy, iconSize, stroke)

        // banda de la gráfica: en vertical sobre la barra de avisos, con valores; en apaisado, mini gráfica sin
        // valores en el hueco junto al reloj (a pantalla completa tapaba la lista y no aportaba)
        var left = 30f * dp
        var right = if (chartRight > 0f) chartRight else w - 30f * dp
        var cTop = chartTop
        var cBot = chartBottom
        val labels = box == null
        if (box != null) {
            bigText.textSize = box.height() * 0.62f
            bigText.textAlign = Paint.Align.LEFT
            val t = "${f.temp.roundToInt()}°"
            val tx = ix + iconSize + 12f * dp
            c.text(t, tx, box.top + box.height() * 0.70f, bigText)
            bigText.textAlign = Paint.Align.RIGHT
            left = tx + bigText.measureText(t) + 30f * dp
            right = box.right
            cTop = box.top + box.height() * 0.05f
            cBot = box.bottom + box.height() * 0.15f
        } else {
            bigText.textSize = w * 0.30f
            c.text("${f.temp.roundToInt()}°", w - 20f * dp, h * 0.58f, bigText)
            if (f.humidity >= 0) {
                bigText.textSize = w * 0.07f
                c.text("humedad ${f.humidity}%", w - 28f * dp, h * 0.58f + w * 0.08f, bigText)
            }
        }

        if (f.hours.size < 2 || cBot <= cTop || right - left < 80f * dp) return
        val xs = f.hours.indices.map { left + (right - left) * it / (f.hours.size - 1) }
        val band = cBot - cTop
        small.textSize = 14f * dp
        // trazado progresivo: se recorta la gráfica hasta donde ha llegado la «pluma»
        val pen = left + (right - left) * trace
        c.save()
        c.clipRect(0f, cTop - 40f * dp, pen + 8f * dp, cBot + 40f * dp)

        // temperatura: tercio superior de la banda
        val tMin = f.hours.minOf { it.temp }
        val tMax = f.hours.maxOf { it.temp }
        val tTop = cTop + band * 0.12f
        val tBot = cTop + band * 0.38f
        val ty = f.hours.map { tBot - (tBot - tTop) * ((it.temp - tMin) / (tMax - tMin).coerceAtLeast(1.0)).toFloat() }
        stroke.strokeWidth = 2f * dp
        c.drawPath(smooth(xs, ty), stroke)
        xs.indices.forEach { c.drawCircle(xs[it], ty[it], 4f * dp, fill) }
        if (labels) xs.indices.forEach {
            val hr = f.hours[it]
            c.text("${hr.hour} h ${hr.temp.roundToInt()}°", xs[it].coerceIn(left + 30 * dp, right - 30 * dp),
                tTop - 6f * dp, small)
        }

        // lluvia: probabilidad (discontinua) y mm (barras) en la parte baja
        val rTop = cTop + band * 0.55f
        val rBot = cTop + band * 0.85f
        val ry = f.hours.map { rBot - (rBot - rTop) * it.rainProb / 100f }
        stroke.pathEffect = DashPathEffect(floatArrayOf(8f * dp, 7f * dp), 0f)
        c.drawPath(smooth(xs, ry), stroke)
        stroke.pathEffect = null
        val mmMax = f.hours.maxOf { it.rainMm }.coerceAtLeast(2.0)
        xs.indices.forEach {
            val bh = ((rBot - rTop) * f.hours[it].rainMm / mmMax).toFloat()
            if (bh > 0.5f) c.drawRoundRect(xs[it] - 6 * dp, rBot - bh, xs[it] + 6 * dp, rBot, 2 * dp, 2 * dp, stroke)
        }
        if (labels) xs.indices.forEach {
            val hr = f.hours[it]
            val mm = if (hr.rainMm < 0.05) "0" else String.format("%.1f", hr.rainMm)
            c.text("${hr.rainProb}% · $mm mm", xs[it].coerceIn(left + 40 * dp, right - 40 * dp),
                rBot + 18f * dp, small)
        }
        c.restore()
        if (trace < 1f) c.drawCircle(pen, ty[0] + (ty.last() - ty[0]) * trace, 6f * dp, fill)
    }

    // ---- cielo animado ----

    private fun sky(c: Canvas, code: Int, w: Float, h: Float) {
        val rnd = Random(code * 7919)
        when (code) {
            0 -> sun(c, w, h)
            1, 2, 3 -> clouds(c, w, h)
            45, 48 -> fog(c, w, h)
            in 51..67, in 80..82 -> rain(c, rnd, w, h, 36)
            in 71..77, 85, 86 -> snow(c, rnd, w, h)
            in 95..99 -> { rain(c, rnd, w, h, 48); if (frame == FRAMES / 2 || frame == FRAMES / 2 + 1) bolt(c, w, h) }
            else -> clouds(c, w, h)
        }
    }

    /** Gotas inclinadas que caen; al llegar a la gráfica, salpican en arco. */
    private fun rain(c: Canvas, rnd: Random, w: Float, h: Float, n: Int) {
        val floor = if (chartBottom > 0) chartBottom else h
        stroke.strokeWidth = 2f * dp
        repeat(n) {
            val x0 = rnd.nextFloat() * w
            val y0 = rnd.nextFloat() * floor
            val speed = floor * (0.10f + rnd.nextFloat() * 0.06f)
            val y = (y0 + frame * speed) % floor
            val len = 22f * dp + rnd.nextFloat() * 18f * dp
            val x = x0 - (y - y0) * 0.18f
            if (y + len >= floor - speed) {
                // salpicadura: dos arcos pequeños sobre el «suelo»
                val r = 7f * dp
                c.drawArc(x - r, floor - r, x + r, floor + r, 200f, 50f, false, stroke)
                c.drawArc(x - r, floor - r, x + r, floor + r, 290f, 50f, false, stroke)
            } else {
                c.drawLine(x, y, x - len * 0.18f, y + len, stroke)
            }
        }
    }

    /** Copos de seis brazos que bajan despacio oscilando. */
    private fun snow(c: Canvas, rnd: Random, w: Float, h: Float) {
        stroke.strokeWidth = 1.5f * dp
        repeat(30) {
            val x0 = rnd.nextFloat() * w
            val y0 = rnd.nextFloat() * h
            val r = 5f * dp + rnd.nextFloat() * 6f * dp
            val y = (y0 + frame * h * 0.035f) % h
            val x = x0 + sin(frame * 0.9f + it).toFloat() * 14f * dp
            val turn = frame * 0.3f + it
            for (k in 0 until 3) {
                val a = turn + k * PI.toFloat() / 3f
                c.drawLine(x - cos(a) * r, y - sin(a) * r, x + cos(a) * r, y + sin(a) * r, stroke)
            }
        }
    }

    /** Rayos largos alrededor del sol que giran y respiran. */
    private fun sun(c: Canvas, w: Float, h: Float) {
        val (x, y, size) = iconBox(w, h)
        val cx = x + size / 2
        val cy = y + size / 2
        val r1 = size * 0.50f
        val breathe = if (frame % 2 == 0) 1f else 1.08f
        stroke.strokeWidth = 2.5f * dp
        for (i in 0 until 16) {
            val a = (i * 22.5f + frame * 4f) * PI.toFloat() / 180f
            val r2 = r1 + size * (if (i % 2 == 0) 0.16f else 0.09f) * breathe
            c.drawLine(cx + cos(a) * r1, cy + sin(a) * r1, cx + cos(a) * r2, cy + sin(a) * r2, stroke)
        }
    }

    /** Nubes que cruzan a distintas velocidades (paralaje). */
    private fun clouds(c: Canvas, w: Float, h: Float) {
        val specs = listOf(Triple(0.10f, 0.22f, 0.030f), Triple(0.55f, 0.12f, 0.018f), Triple(0.30f, 0.40f, 0.045f))
        stroke.strokeWidth = 2f * dp
        for ((i, s) in specs.withIndex()) {
            val size = w * (0.16f + i * 0.05f)
            val x = ((s.first + frame * s.third) % 1.2f - 0.2f) * w
            stroke.strokeWidth = 2f * dp * 24f / size
            Icons.draw(c, Icon.CLOUD, x, h * s.second, size, stroke)
        }
        stroke.strokeWidth = 2f * dp
    }

    /** Bandas de niebla onduladas que se deslizan. */
    private fun fog(c: Canvas, w: Float, h: Float) {
        stroke.strokeWidth = 3f * dp
        for (i in 0 until 7) {
            val y = h * (0.15f + i * 0.09f)
            val p = Path()
            var x = -w * 0.1f
            p.moveTo(x, y)
            while (x < w * 1.1f) {
                val nx = x + w * 0.08f
                val dy = sin((nx / w * 6f + frame * 0.6f + i).toDouble()).toFloat() * 8f * dp
                p.quadTo(x + w * 0.04f, y + dy * 2, nx, y + dy)
                x = nx
            }
            c.drawPath(p, stroke)
        }
    }

    /** Relámpago: zigzag grueso de arriba a la gráfica. */
    private fun bolt(c: Canvas, w: Float, h: Float) {
        val p = Path()
        var x = w * 0.62f
        var y = 0f
        p.moveTo(x, y)
        val floor = if (chartTop > 0) chartTop else h * 0.7f
        var k = 0
        while (y < floor) {
            x += if (k % 2 == 0) -w * 0.05f else w * 0.035f
            y += floor / 7f
            p.lineTo(x, y)
            k++
        }
        stroke.strokeWidth = 6f * dp
        c.drawPath(p, stroke)
    }

    /** Posición (x, y) y tamaño del icono grande. */
    private fun iconBox(w: Float, h: Float): Triple<Float, Float, Float> =
        landscapeBox?.let { Triple(it.left, it.top, it.height()) } ?: Triple(w * 0.08f, h * 0.30f, w * 0.55f)

    companion object {
        const val FRAMES = 10
        const val TRACE_FRAMES = 4
        const val FRAME_MS = 600L
    }

    private fun smooth(xs: List<Float>, ys: List<Float>): Path {
        val p = Path()
        p.moveTo(xs[0], ys[0])
        for (i in 1 until xs.size) {
            val mx = (xs[i - 1] + xs[i]) / 2
            p.cubicTo(mx, ys[i - 1], mx, ys[i], xs[i], ys[i])
        }
        return p
    }
}
