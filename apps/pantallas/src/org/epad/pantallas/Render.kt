package org.epad.pantallas

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.text.format.DateFormat
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import java.util.Date

/** Datos del momento para los elementos dinámicos. */
data class Live(val now: Long, val battery: Int, val charging: Boolean, val weather: String?)

/**
 * Dibuja una pantalla en vertical (1404 × 1872, como se sostiene la tablet) y la pasa a las coordenadas del panel
 * (1872 × 1404 apaisado, arriba = columna 0) en grises de 8 bits, que es lo que esperan sleep_image y poweroff_image.
 */
object Render {
    const val W = 1404
    const val H = 1872
    private const val PW = 1872
    private const val PH = 1404
    private const val GAP = 28f

    fun portrait(ctx: Context, s: Screen, d: Design, live: Live): Bitmap {
        val bmp = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawColor(Color.WHITE)
        if (d.hasImage) background(ctx, s, d.fill)?.let { c.drawBitmap(it, 0f, 0f, null) }
        for (pos in Pos.values()) {
            val items = d.elements.filter { it.pos == pos }.map { it to prepare(ctx, it, live) }.filter { it.second != null }
            if (items.isEmpty()) continue
            val total = items.sumOf { it.second!!.height.toDouble() }.toFloat() + GAP * (items.size - 1)
            var y = when (pos) {
                Pos.ARRIBA -> H * 0.06f
                Pos.CENTRO -> (H - total) / 2f
                Pos.ABAJO -> H * 0.95f - total
            }
            for ((e, p) in items) {
                p!!.draw(c, y, e.box)
                y += p.height + GAP
            }
        }
        return bmp
    }

    fun panel(portrait: Bitmap): ByteArray {
        val bmp = Bitmap.createBitmap(PW, PH, Bitmap.Config.ARGB_8888)
        Canvas(bmp).apply {
            translate(0f, PH.toFloat())
            rotate(-90f)
            drawBitmap(portrait, 0f, 0f, null)
        }
        val px = IntArray(PW * PH)
        bmp.getPixels(px, 0, PW, 0, 0, PW, PH)
        bmp.recycle()
        return ByteArray(px.size) {
            val p = px[it]
            (((p shr 16 and 0xff) * 77 + (p shr 8 and 0xff) * 150 + (p and 0xff) * 29) shr 8).toByte()
        }
    }

    /** Un elemento listo para dibujar: su alto y cómo pintarlo a partir de una y. */
    private class Piece(val height: Float, val width: Float, val paint: (Canvas, Float) -> Unit) {
        fun draw(c: Canvas, y: Float, box: Boolean) {
            if (box) {
                val pad = 24f
                val r = RectF((W - width) / 2f - pad, y - pad, (W + width) / 2f + pad, y + height + pad)
                c.drawRoundRect(r, 20f, 20f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE })
                c.drawRoundRect(r, 20f, 20f, Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = Color.BLACK; style = Paint.Style.STROKE; strokeWidth = 3f
                })
            }
            paint(c, y)
        }
    }

    fun textOf(ctx: Context, e: Element, live: Live): String? = when (e.kind) {
        Kind.TEXTO -> e.text
        Kind.HORA -> DateFormat.getTimeFormat(ctx).format(Date(live.now))
        Kind.FECHA -> DateFormat.format("EEEE, d MMMM", Date(live.now)).toString().replaceFirstChar { it.uppercase() }
        Kind.BATERIA -> if (live.battery < 0) null else
            (if (e.text.isNotBlank()) e.text.trim() + " " else "batería ") + "${live.battery} %" +
                (if (live.charging) " · cargando" else "")
        Kind.TIEMPO -> live.weather
        else -> null
    }?.takeIf { it.isNotBlank() }

    private fun prepare(ctx: Context, e: Element, live: Live): Piece? = when (e.kind) {
        Kind.ICONO -> {
            val s = e.size.icon.toFloat()
            Piece(s, s) { c, y -> icon(c, e.icon, W / 2f, y, s) }
        }
        Kind.QR -> qr(e.text, e.size.qr)?.let { m -> Piece(m.height.toFloat(), m.width.toFloat()) { c, y -> c.drawBitmap(m, (W - m.width) / 2f, y, null) } }
        else -> textOf(ctx, e, live)?.let { t ->
            val tp = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.BLACK
                textSize = e.size.text
                typeface = Typeface.create(if (e.size == Size.P) "sans-serif" else "sans-serif-light", Typeface.NORMAL)
            }
            val l = StaticLayout.Builder.obtain(t, 0, t.length, tp, (W * 0.86f).toInt())
                .setAlignment(Layout.Alignment.ALIGN_CENTER).build()
            val wmax = (0 until l.lineCount).maxOf { l.getLineWidth(it) }
            Piece(l.height.toFloat(), wmax) { c, y ->
                c.save(); c.translate((W - l.width) / 2f, y); l.draw(c); c.restore()
            }
        }
    }

    private fun qr(text: String, size: Int): Bitmap? {
        if (text.isBlank()) return null
        val m = runCatching {
            QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, 0, 0,
                mapOf(EncodeHintType.MARGIN to 1, EncodeHintType.CHARACTER_SET to "UTF-8"))
        }.getOrNull() ?: return null
        val cell = maxOf(1, size / m.width)
        val n = m.width * cell
        val bmp = Bitmap.createBitmap(n, n, Bitmap.Config.ARGB_8888)
        bmp.eraseColor(Color.WHITE)
        val c = Canvas(bmp)
        val p = Paint().apply { color = Color.BLACK }
        for (y in 0 until m.height) for (x in 0 until m.width)
            if (m.get(x, y)) c.drawRect((x * cell).toFloat(), (y * cell).toFloat(), ((x + 1) * cell).toFloat(), ((y + 1) * cell).toFloat(), p)
        return bmp
    }

    /** Iconos de trazo (como la pantalla de reposo original), dentro de un cuadrado de lado s con el borde de arriba en y. */
    private fun icon(c: Canvas, i: Icon, cx: Float, y: Float, s: Float) {
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE; color = Color.BLACK; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
            strokeWidth = maxOf(4f, s / 45f)
        }
        val cy = y + s / 2f
        when (i) {
            Icon.ENCENDIDO -> {
                val r = s * 0.42f
                c.drawArc(RectF(cx - r, cy - r, cx + r, cy + r), -60f, 300f, false, p)
                c.drawLine(cx, cy - r * 1.12f, cx, cy - r * 0.12f, p)
            }
            Icon.LUNA -> {
                val r = s * 0.32f
                val moon = Path().apply {
                    addCircle(cx, cy, r, Path.Direction.CW)
                    op(Path().apply { addCircle(cx + r * 0.45f, cy - r * 0.3f, r * 0.85f, Path.Direction.CW) }, Path.Op.DIFFERENCE)
                }
                c.drawPath(moon, p)
                p.strokeWidth = maxOf(3f, s / 70f)
                for ((dx, dy, k) in listOf(Triple(-0.42f, -0.36f, 0.05f), Triple(0.40f, -0.30f, 0.07f), Triple(0.44f, 0.24f, 0.04f), Triple(-0.36f, 0.26f, 0.035f))) {
                    val x = cx + s * dx; val yy = cy + s * dy; val a = s * k
                    c.drawLine(x - a, yy, x + a, yy, p); c.drawLine(x, yy - a, x, yy + a, p)
                }
            }
            Icon.LIBRO -> {
                val w = s * 0.42f; val h = s * 0.30f; val top = cy - h; val bot = cy + h
                // dos páginas abiertas con el lomo en el centro
                val left = Path().apply { moveTo(cx, top + h * 0.15f); quadTo(cx - w * 0.5f, top - h * 0.1f, cx - w, top + h * 0.05f)
                    lineTo(cx - w, bot); quadTo(cx - w * 0.5f, bot - h * 0.15f, cx, bot); close() }
                val right = Path().apply { moveTo(cx, top + h * 0.15f); quadTo(cx + w * 0.5f, top - h * 0.1f, cx + w, top + h * 0.05f)
                    lineTo(cx + w, bot); quadTo(cx + w * 0.5f, bot - h * 0.15f, cx, bot); close() }
                c.drawPath(left, p); c.drawPath(right, p)
            }
        }
    }

    // --- fondo: reducido, en grises y tramado a los 16 niveles de la tinta; se guarda el último en memoria

    private var cacheKey = ""
    private var cache: Bitmap? = null

    @Synchronized
    private fun background(ctx: Context, s: Screen, fill: Boolean): Bitmap? {
        val f = Store.image(ctx, s)
        if (!f.exists()) return null
        val key = "${f.path}|${f.lastModified()}|${f.length()}|$fill"
        if (key == cacheKey) cache?.let { return it }
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(f.path, o)
        if (o.outWidth <= 0 || o.outHeight <= 0) return null
        var sample = 1
        while (o.outWidth / (sample * 2) >= W && o.outHeight / (sample * 2) >= H) sample *= 2
        val src = BitmapFactory.decodeFile(f.path, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return null
        val scale = if (fill) maxOf(W / src.width.toFloat(), H / src.height.toFloat())
        else minOf(W / src.width.toFloat(), H / src.height.toFloat())
        val out = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888)
        Canvas(out).apply {
            drawColor(Color.WHITE)
            val dw = src.width * scale; val dh = src.height * scale
            drawBitmap(src, null, RectF((W - dw) / 2f, (H - dh) / 2f, (W + dw) / 2f, (H + dh) / 2f),
                Paint(Paint.FILTER_BITMAP_FLAG))
        }
        src.recycle()
        dither(out)
        cache = out; cacheKey = key	// sin recycle(): la vista previa puede estar dibujando con la anterior
        return out
    }

    /** Floyd-Steinberg a 16 grises. */
    private fun dither(b: Bitmap) {
        val px = IntArray(W * H)
        b.getPixels(px, 0, W, 0, 0, W, H)
        val g = FloatArray(px.size) { val p = px[it]; ((p shr 16 and 0xff) * 0.299f + (p shr 8 and 0xff) * 0.587f + (p and 0xff) * 0.114f) }
        for (y in 0 until H) for (x in 0 until W) {
            val i = y * W + x
            val old = g[i].coerceIn(0f, 255f)
            val q = (Math.round(old * 15f / 255f) * 17).toFloat()
            val err = old - q
            px[i] = Color.rgb(q.toInt(), q.toInt(), q.toInt())
            if (x + 1 < W) g[i + 1] += err * 7 / 16
            if (y + 1 < H) {
                if (x > 0) g[i + W - 1] += err * 3 / 16
                g[i + W] += err * 5 / 16
                if (x + 1 < W) g[i + W + 1] += err / 16
            }
        }
        b.setPixels(px, 0, W, 0, 0, W, H)
    }
}
