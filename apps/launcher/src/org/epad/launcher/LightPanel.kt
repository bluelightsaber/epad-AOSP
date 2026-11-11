package org.epad.launcher

import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.Window
import android.widget.Button
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView

/**
 * Panel de luz y pantalla (pulsación larga en Luz o en el modo): luz fría y cálida, encendido, modo del T1000 y
 * refresco completo. Los cambios de luz se aplican al soltar el control, no en cada paso (E-Ink y PWM).
 */
class LightPanel(ctx: Context, private val onChanged: () -> Unit) : Dialog(ctx) {
    private val d = ctx.resources.displayMetrics.density

    init {
        requestWindowFeature(Window.FEATURE_NO_TITLE)
        window?.setBackgroundDrawable(GradientDrawable().apply {
            setColor(Color.WHITE); setStroke((3 * d).toInt(), Color.BLACK); cornerRadius = 18 * d
        })
        window?.setWindowAnimations(0)
        val box = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            val p = (28 * d).toInt()
            setPadding(p, p, p, p)
        }
        box.addView(title("Luz frontal"))
        val toggle = Button(ctx)
        fun refreshToggle() {
            val on = EpadHw.light(EpadHw.Light.COLD) + EpadHw.light(EpadHw.Light.WARM) > 0
            toggle.text = if (on) "Apagar" else "Encender"
        }
        toggle.setOnClickListener { Lights.toggle(ctx); refreshToggle(); onChanged() }
        refreshToggle()
        box.addView(toggle)
        for (l in EpadHw.Light.entries) {
            val name = if (l == EpadHw.Light.COLD) "Fría" else "Cálida"
            val label = text("$name ${EpadHw.light(l)}%")
            box.addView(label)
            box.addView(SeekBar(ctx).apply {
                max = 100
                progress = EpadHw.light(l)
                setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(s: SeekBar, v: Int, user: Boolean) { label.text = "$name $v%" }
                    override fun onStartTrackingTouch(s: SeekBar) {}
                    override fun onStopTrackingTouch(s: SeekBar) {
                        EpadHw.setLight(l, s.progress); refreshToggle(); onChanged()
                    }
                })
            }, LinearLayout.LayoutParams(-1, (48 * d).toInt()))
        }
        box.addView(title("Pantalla (T1000)"))
        val modes = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        val buttons = EpadHw.Mode.entries.map { m ->
            Button(ctx).apply { text = m.label }.also { modes.addView(it, LinearLayout.LayoutParams(0, -2, 1f)) }
        }
        fun refreshModes() {
            val cur = EpadHw.mode()
            EpadHw.Mode.entries.forEachIndexed { i, m ->
                buttons[i].isSelected = m == cur
                buttons[i].setTextColor(if (m == cur) Color.WHITE else Color.BLACK)
                buttons[i].setBackgroundColor(if (m == cur) Color.BLACK else Color.WHITE)
            }
        }
        EpadHw.Mode.entries.forEachIndexed { i, m ->
            buttons[i].setOnClickListener { Lights.setMode(ctx, m); refreshModes(); onChanged() }
        }
        refreshModes()
        box.addView(modes)
        box.addView(text("Lectura: máxima calidad. Mixto: texto rápido con imágenes. " +
            "Navegación: lo más rápido, con más fantasmas.").apply { textSize = 16f })
        box.addView(Button(ctx).apply {
            text = "Refrescar pantalla ahora"
            setOnClickListener { dismiss(); EpadHw.fullRefresh() }
        })
        setContentView(box)
        window?.setLayout((ctx.resources.displayMetrics.widthPixels * 0.9).toInt(), -2)
        window?.setGravity(Gravity.CENTER)
    }

    private fun title(s: String) = TextView(context).apply {
        text = s; textSize = 28f; setTextColor(Color.BLACK); paint.isFakeBoldText = true
        setPadding(0, (12 * d).toInt(), 0, (8 * d).toInt())
    }

    private fun text(s: String) = TextView(context).apply { text = s; textSize = 20f; setTextColor(Color.BLACK) }
}

/** Alternancias de un toque de los botones del inicio, con memoria del último estado. */
object Lights {
    private fun prefs(ctx: Context) = ctx.getSharedPreferences("hw", Context.MODE_PRIVATE)

    fun isOn() = EpadHw.light(EpadHw.Light.COLD) + EpadHw.light(EpadHw.Light.WARM) > 0

    /** Luz: apagar recordando los niveles, o encender con los últimos (30 % por omisión). */
    fun toggle(ctx: Context) {
        val p = prefs(ctx)
        if (isOn()) {
            if (!NightLight.isNight(ctx)) p.edit().putInt("cold", EpadHw.light(EpadHw.Light.COLD))
                .putInt("warm", EpadHw.light(EpadHw.Light.WARM)).apply()
            EpadHw.setLight(EpadHw.Light.COLD, 0)
            EpadHw.setLight(EpadHw.Light.WARM, 0)
        } else {
            applyLevels(ctx, NightLight.isNight(ctx))
        }
    }

    /** Enciende con los niveles de día guardados o, de noche, solo la cálida al nivel nocturno. */
    fun applyLevels(ctx: Context, night: Boolean) {
        val p = prefs(ctx)
        if (night) {
            EpadHw.setLight(EpadHw.Light.COLD, 0)
            EpadHw.setLight(EpadHw.Light.WARM, NightLight.warm(ctx))
        } else {
            EpadHw.setLight(EpadHw.Light.COLD, p.getInt("cold", 30))
            EpadHw.setLight(EpadHw.Light.WARM, p.getInt("warm", 30))
        }
    }

    /** Pantalla apagada: apagar la luz recordando que estaba encendida (y sus niveles, como el botón). */
    fun screenOff(ctx: Context) {
        if (!isOn()) return
        prefs(ctx).edit().putBoolean("off_by_screen", true).apply()
        toggle(ctx)
    }

    /** Pantalla encendida: volver a encender la luz si la apagó screenOff(). */
    fun screenOn(ctx: Context) {
        val p = prefs(ctx)
        if (!p.getBoolean("off_by_screen", false)) return
        p.edit().putBoolean("off_by_screen", false).apply()
        if (!isOn()) toggle(ctx)
    }

    /** Modo: de Lectura al último otro modo usado (Mixto por omisión) y de cualquiera a Lectura. */
    fun toggleMode(ctx: Context) {
        val cur = EpadHw.mode() ?: return
        if (cur == EpadHw.Mode.READING) {
            setMode(ctx, EpadHw.Mode.of(prefs(ctx).getInt("other", EpadHw.Mode.MIXED.code)) ?: EpadHw.Mode.MIXED)
        } else {
            setMode(ctx, EpadHw.Mode.READING)
        }
    }

    fun setMode(ctx: Context, m: EpadHw.Mode) {
        if (m != EpadHw.Mode.READING) prefs(ctx).edit().putInt("other", m.code).apply()
        EpadHw.setMode(m)
        NavKeys.modeChanged(ctx)
    }

    fun label(): String {
        val pct = maxOf(EpadHw.light(EpadHw.Light.COLD), EpadHw.light(EpadHw.Light.WARM))
        return if (pct > 0) "Luz $pct%" else "Luz"
    }
}
