package org.epad.launcher

import android.content.Context
import android.os.SystemProperties
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.TextView

/** Modos de carga: init los aplica al cargador (bq24190) y al driver de batería según la propiedad. */
object BatteryProtect {
    private const val PROP = "persist.vendor.epad.charge_mode"

    enum class Mode(val prop: String, val title: String, val help: String) {
        NORMAL("normal", "Normal", "Carga completa al 100 %."),
        LIMIT80("limit80", "Hasta 80 %",
            "La batería se degrada menos si no pasa mucho tiempo llena. Desactívalo antes de un viaje."),
        PLUGGED("plugged", "Siempre enchufada (~50 %)",
            "Para usarla fija con el cable: mantiene la batería entre 45 y 55 % y, mientras tanto, la tablet " +
                "se alimenta directamente del cable. Es lo que menos la desgasta.");
    }

    fun mode(): Mode = SystemProperties.get(PROP, "normal").let { p -> Mode.entries.firstOrNull { it.prop == p } } ?: Mode.NORMAL

    fun set(m: Mode) = runCatching { SystemProperties.set(PROP, m.prop) }.isSuccess

    /** Selector de modo para los ajustes del launcher (en Ajustes > Batería lo pone el parche de Settings). */
    fun selector(ctx: Context): LinearLayout = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        val help = TextView(ctx).apply { textSize = 18f; setTextColor(0xFF333333.toInt()) }
        val group = RadioGroup(ctx)
        Mode.entries.forEachIndexed { i, m ->
            group.addView(RadioButton(ctx).apply { id = i + 1; text = m.title; textSize = 22f })
        }
        val cur = mode()
        group.check(cur.ordinal + 1)
        help.text = cur.help
        group.setOnCheckedChangeListener { g, id ->
            val m = Mode.entries[id - 1]
            if (set(m)) help.text = m.help else g.check(mode().ordinal + 1)
        }
        addView(group)
        addView(help)
    }
}
