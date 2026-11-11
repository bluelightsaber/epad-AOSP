package org.epad.launcher

import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.provider.Settings
import android.os.Handler
import android.os.Looper
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.util.concurrent.Executors

/** Ajustes del launcher (pulsación larga en el engranaje o en el fondo): tiempo, luz nocturna y favoritas. */
class LauncherSettingsActivity : Activity() {
    private val io = Executors.newSingleThreadExecutor()
    private val ui = Handler(Looper.getMainLooper())

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        val d = resources.displayMetrics.density
        val apps = Apps(this).apply { reload() }
        val weather = Weather(this)
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.WHITE)
            val p = (32 * d).toInt()
            setPadding(p, p, p, p)
        }

        box.addView(title("Tiempo"))
        val current = text(weather.place?.first?.let { "Ciudad: $it" } ?: "Ciudad: ubicación del sistema")
        box.addView(current)
        val query = EditText(this).apply { hint = "Buscar ciudad"; textSize = 22f }
        box.addView(query)
        val results = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        box.addView(Button(this).apply {
            text = "Buscar"
            setOnClickListener {
                val q = query.text.toString().trim()
                if (q.isNotEmpty()) io.execute {
                    val found = weather.search(q)
                    ui.post {
                        results.removeAllViews()
                        if (found.isEmpty()) results.addView(text("Sin resultados"))
                        found.forEach { place ->
                            results.addView(Button(this@LauncherSettingsActivity).apply {
                                text = place.first
                                setOnClickListener {
                                    weather.place = place
                                    current.text = "Ciudad: ${place.first}"
                                    results.removeAllViews()
                                }
                            })
                        }
                    }
                }
            }
        })
        box.addView(results)

        val lp = getSharedPreferences("launcher", MODE_PRIVATE)
        box.addView(CheckBox(this).apply {
            text = "Tiempo animado (modo rápido de la tinta mientras dura)"; textSize = 22f
            isChecked = lp.getBoolean(HomeActivity.ANIM_KEY, true)
            setOnCheckedChangeListener { _, v -> lp.edit().putBoolean(HomeActivity.ANIM_KEY, v).putLong("anim_last", 0).apply() }
        })

        box.addView(title("Luz nocturna"))
        box.addView(text("En ese horario la luz encendida pasa a solo cálida y más tenue."))
        var on = NightLight.enabled(this); var start = NightLight.start(this)
        var end = NightLight.end(this); var warm = NightLight.warm(this)
        val save = { NightLight.configure(this, on, start, end, warm) }
        box.addView(CheckBox(this).apply {
            text = "Activada"; textSize = 22f; isChecked = on
            setOnCheckedChangeListener { _, v -> on = v; save() }
        })
        box.addView(stepper("Desde", { "%02d:00".format(start) }) { start = (start + it + 24) % 24; save() })
        box.addView(stepper("Hasta", { "%02d:00".format(end) }) { end = (end + it + 24) % 24; save() })
        box.addView(stepper("Cálida", { "$warm %" }) { warm = (warm + 5 * it).coerceIn(5, 100); save() })

        box.addView(title("Pantallas de reposo y apagado"))
        box.addView(text("Lo que se queda en la tinta al apagar la pantalla o la tablet: imagen, textos, hora, batería, tiempo…"))
        box.addView(Button(this).apply {
            text = "Abrir Pantallas"; setOnClickListener { SleepScreen.open(this@LauncherSettingsActivity) }
        })

        // El tiempo de apagado está en Ajustes › Pantalla, escondido bajo «Pantalla de bloqueo»: también aquí
        val timeouts = listOf(1, 2, 5, 10, 15, 30, 60)
        var tIdx = timeouts.indexOfFirst { it * 60000 >= Settings.System.getInt(contentResolver,
            Settings.System.SCREEN_OFF_TIMEOUT, 600000) }.let { if (it < 0) timeouts.lastIndex else it }
        box.addView(stepper("Apagar tras", { "${timeouts[tIdx]} min" }) {
            tIdx = (tIdx + it).coerceIn(0, timeouts.lastIndex)
            runCatching { Settings.System.putInt(contentResolver, Settings.System.SCREEN_OFF_TIMEOUT, timeouts[tIdx] * 60000) }
        })

        box.addView(title("Batería"))
        box.addView(BatteryProtect.selector(this))

        box.addView(title("Favoritas"))
        box.addView(text("Se muestran en el inicio en este orden; el resto, desde el índice A-Z."))
        val favs = apps.favorites.map { it.key }.toMutableList()
        for (a in apps.all) {
            box.addView(CheckBox(this).apply {
                text = a.label
                textSize = 22f
                isChecked = a.key in favs
                setOnCheckedChangeListener { _, on ->
                    if (on) favs.add(a.key) else favs.remove(a.key)
                    apps.favorites = favs.mapNotNull { k -> apps.all.firstOrNull { it.key == k } }
                }
            })
        }
        val scroll = ScrollView(this).apply { addView(box) }
        setContentView(scroll)
        fitSystemBars(scroll)
    }

    /** Fila «Nombre  [−]  valor  [+]» sin teclado ni ruedas (que en tinta refrescan a cada paso). */
    private fun stepper(name: String, value: () -> String, change: (Int) -> Unit) = LinearLayout(this).apply {
        gravity = android.view.Gravity.CENTER_VERTICAL
        val v = text(value()).apply { textSize = 22f; minWidth = (110 * resources.displayMetrics.density).toInt() }
        addView(text(name).apply { textSize = 22f; minWidth = (110 * resources.displayMetrics.density).toInt() })
        addView(Button(context).apply { text = "−"; setOnClickListener { change(-1); v.text = value() } })
        addView(v.apply { gravity = android.view.Gravity.CENTER })
        addView(Button(context).apply { text = "+"; setOnClickListener { change(1); v.text = value() } })
    }

    private fun title(s: String) = TextView(this).apply {
        text = s; textSize = 30f; setTextColor(Color.BLACK); paint.isFakeBoldText = true
        setPadding(0, (24 * resources.displayMetrics.density).toInt(), 0, 0)
    }

    private fun text(s: String) = TextView(this).apply { text = s; textSize = 20f; setTextColor(Color.BLACK) }
}
