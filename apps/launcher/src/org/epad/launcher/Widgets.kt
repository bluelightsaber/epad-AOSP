package org.epad.launcher

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.text.TextPaint
import android.text.TextUtils
import android.view.MotionEvent
import android.view.View

private val Context.dp get() = resources.displayMetrics.density

/** Botón cuadrado del inicio (Luz, modo, Wi-Fi, BT): negro = activo. Toque y pulsación larga separados. */
class QuickButton(ctx: Context, private val icon: Icon) : View(ctx) {
    var label = ""
        set(v) { field = v; contentDescription = v; invalidate() }
    var active = false
        set(v) { field = v; invalidate() }

    private val d = ctx.dp
    private val box = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeWidth = 2 * d }
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
    }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER; textSize = 16 * d }

    init {
        isClickable = true
        isLongClickable = true
    }

    override fun onDraw(c: Canvas) {
        val fg = if (active) Color.WHITE else Color.BLACK
        val r = RectF(d, d, width - d, height - d)
        box.style = Paint.Style.FILL
        box.color = if (active) Color.BLACK else Color.WHITE
        c.drawRoundRect(r, 14 * d, 14 * d, box)
        box.style = Paint.Style.STROKE
        box.color = Color.BLACK
        c.drawRoundRect(r, 14 * d, 14 * d, box)
        val s = 28 * d
        line.color = fg
        line.strokeWidth = 2 * d * 24 / s
        Icons.draw(c, icon, (width - s) / 2, height / 2f - s + 2 * d, s, line)
        text.color = fg
        c.drawText(label, width / 2f, height / 2f + 22 * d, text)
    }
}

/**
 * Índice de apps: «★» (favoritas) y grupos de letras con apps (como mucho [Apps.MAX_GROUPS], con un número
 * parecido de apps cada uno). Botones altos: con las 27 letras cada una medía ~30 dp y costaba acertar.
 */
class AppIndex(ctx: Context) : View(ctx) {
    var groups: List<AppGroup> = emptyList()
        set(v) { field = v; invalidate() }
    /** -1 = favoritas; si no, posición en [groups]. */
    var selected = -1
        set(v) { field = v; invalidate() }
    var onSelect: (Int) -> Unit = {}

    private val d = ctx.dp
    private val border = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 2 * d }
    private val sep = Paint().apply { strokeWidth = 1 * d; color = 0xFF9A9A9A.toInt() }
    private val pill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER; typeface = Typeface.DEFAULT_BOLD
    }

    private val labels get() = listOf("★") + groups.map { it.short }

    override fun onDraw(c: Canvas) {
        val r = RectF(d, d, width - d, height - d)
        c.drawRoundRect(r, 18 * d, 18 * d, border)
        val l = labels
        val step = (height - 8 * d) / l.size
        // el texto cabe en el ancho («Ñ–R») y no pasa de 26 dp
        text.textSize = minOf(26 * d, step * 0.42f, (width - 12 * d) / 3.2f)
        l.forEachIndexed { i, label ->
            val top = 4 * d + step * i
            val cy = top + step / 2
            if (i > 0) c.drawLine(10 * d, top, width - 10 * d, top, sep)
            if (i - 1 == selected) {
                c.drawRoundRect(5 * d, top + 3 * d, width - 5 * d, top + step - 3 * d, 14 * d, 14 * d, pill)
                text.color = Color.WHITE
            } else {
                text.color = Color.BLACK
            }
            c.drawText(label, width / 2f, cy + text.textSize / 3, text)
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        // E-Ink: se elige al levantar el dedo, no en cada movimiento (cada cambio sería un refresco)
        if (e.actionMasked == MotionEvent.ACTION_UP) {
            val n = labels.size
            val i = ((e.y - 4 * d) / ((height - 8 * d) / n)).toInt().coerceIn(0, n - 1)
            onSelect(i - 1)
        }
        return true
    }
}

/** Fila de app: icono de trazo (o inicial) en un cuadrado, nombre y contador de avisos. */
class AppRow(ctx: Context) : View(ctx) {
    var app: App? = null
        set(v) { field = v; contentDescription = v?.label; invalidate() }
    var icon: android.graphics.drawable.Drawable? = null
        set(v) { field = v; invalidate() }
    var count = 0
        set(v) { field = v; invalidate() }

    private val d = ctx.dp
    private val box = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 2 * d }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val name = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 25 * d }
    private val initial = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 22 * d; typeface = Typeface.DEFAULT_BOLD; textAlign = Paint.Align.CENTER
    }
    private val badge = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE; textSize = 19 * d; typeface = Typeface.DEFAULT_BOLD; textAlign = Paint.Align.CENTER
    }

    init {
        isClickable = true
        isLongClickable = true
    }

    override fun onMeasure(w: Int, h: Int) = setMeasuredDimension(MeasureSpec.getSize(w), (HEIGHT_DP * d).toInt())

    override fun onDraw(c: Canvas) {
        val a = app ?: return
        val s = 48 * d
        val top = (height - s) / 2
        fill.color = Color.WHITE
        c.drawRoundRect(d, top, s, top + s, 12 * d, 12 * d, fill)
        c.drawRoundRect(d, top, s, top + s, 12 * d, 12 * d, box)
        val ic = icon
        if (ic != null) {
            val pad = (8 * d).toInt()
            ic.setBounds(pad, (top + pad).toInt(), (s - pad).toInt(), (top + s - pad).toInt())
            ic.setTint(Color.BLACK)
            ic.draw(c)
        } else {
            c.drawText(a.label.take(1).uppercase(), s / 2, top + s / 2 + initial.textSize / 3, initial)
        }
        val t = if (count > 99) "99+" else count.toString()
        val bw = if (count > 0) maxOf(38 * d, badge.measureText(t) + 20 * d) else 0f
        // en columnas estrechas el nombre se corta con «…» en vez de pisar la columna de al lado
        val room = width - s - 18 * d - bw - 8 * d
        val label = TextUtils.ellipsize(a.label, TextPaint(name), room, TextUtils.TruncateAt.END)
        c.drawText(label, 0, label.length, s + 18 * d, height / 2f + name.textSize / 3, name)
        if (count > 0) {
            fill.color = Color.BLACK
            c.drawRoundRect(width - bw - 4 * d, height / 2f - 19 * d, width - 4 * d, height / 2f + 19 * d,
                19 * d, 19 * d, fill)
            c.drawText(t, width - bw / 2 - 4 * d, height / 2f + badge.textSize / 3, badge)
        }
    }

    companion object {
        const val HEIGHT_DP = 60
    }
}
