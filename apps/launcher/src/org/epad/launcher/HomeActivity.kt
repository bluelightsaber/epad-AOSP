package org.epad.launcher

import android.app.Activity
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.os.Bundle
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.format.DateFormat
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.TextView
import java.util.Date
import java.util.concurrent.Executors
import kotlin.math.roundToInt

/**
 * Inicio de la E-PAD (mockup E): reloj, cuatro botones (toque = alternar, mantener = ajustes), tiempo de fondo,
 * favoritas con contadores de avisos, índice por grupos de letras en lugar de cajón de apps, resumen de avisos y engranaje.
 * Sin animaciones ni desplazamiento: en E-Ink cada fotograma es un refresco.
 */
class HomeActivity : Activity() {
    private lateinit var apps: Apps
    private lateinit var weather: Weather
    private val io = Executors.newSingleThreadExecutor()
    private val ui = Handler(Looper.getMainLooper())
    // el modo también cambia desde la barra de navegación y los ajustes rápidos
    private val modeObserver = object : ContentObserver(ui) {
        override fun onChange(self: Boolean) = renderButtons()
    }

    private lateinit var bg: WeatherBackground
    private lateinit var clock: TextView
    private lateinit var quick: View
    private lateinit var date: TextView
    private lateinit var weatherLine: TextView
    private lateinit var light: QuickButton
    private lateinit var mode: QuickButton
    private lateinit var wifi: QuickButton
    private lateinit var bt: QuickButton
    private lateinit var rotate: QuickButton
    private lateinit var volume: QuickButton
    private lateinit var listBox: LinearLayout
    private lateinit var index: AppIndex
    private lateinit var bottom: LinearLayout
    private lateinit var summary: TextView

    /** -1 = favoritas; si no, posición en [groups]. */
    private var group = -1
    private var groups: List<AppGroup> = emptyList()
    private var page = 0
    private var battery = ""
    private var resumed = false
    private val d get() = resources.displayMetrics.density
    private val onNotices: () -> Unit = { ui.post { renderList(); summary.text = Notices.summary() } }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            when (i.action) {
                Intent.ACTION_BATTERY_CHANGED -> {
                    val lvl = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
                    val scale = i.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
                    battery = if (lvl >= 0) "${lvl * 100 / scale}%" else ""
                    renderClock()
                }
                Intent.ACTION_PACKAGE_ADDED, Intent.ACTION_PACKAGE_REMOVED -> { apps.reload(); renderList() }
                else -> { renderClock(); renderButtons() }
            }
        }
    }

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        Defaults.apply(this)
        NightLight.apply(this)
        SleepScreen.start(this)
        apps = Apps(this)
        weather = Weather(this)
        apps.reload()
        val root = build()
        setContentView(root)
        fitSystemBars(root)
        weather.cached()?.let { showWeather(it) }
    }

    override fun onResume() {
        super.onResume()
        registerReceiver(receiver, IntentFilter().apply {
            addAction(Intent.ACTION_TIME_TICK); addAction(Intent.ACTION_TIME_CHANGED)
            addAction(Intent.ACTION_TIMEZONE_CHANGED); addAction(Intent.ACTION_BATTERY_CHANGED)
            addAction(WifiManager.WIFI_STATE_CHANGED_ACTION); addAction(BluetoothAdapter.ACTION_STATE_CHANGED)
        })
        registerReceiver(receiver, IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED); addAction(Intent.ACTION_PACKAGE_REMOVED); addDataScheme("package")
        })
        contentResolver.registerContentObserver(NavKeys.MODE_URI, false, modeObserver)
        resumed = true
        Notices.watch(onNotices)
        renderClock(); renderButtons(); renderList()
        summary.text = Notices.summary()
        refreshWeather()
        maybeAnimateWeather(false)
        SleepScreen.update(this)
    }

    override fun onPause() {
        resumed = false
        bg.stop()
        unregisterReceiver(receiver)
        contentResolver.unregisterContentObserver(modeObserver)
        Notices.unwatch(onNotices)
        super.onPause()
    }

    /** Volver al inicio (botón Inicio o atrás) deja las favoritas, como un launcher normal. */
    override fun onNewIntent(i: Intent) {
        super.onNewIntent(i)
        showGroup(-1)
    }

    @Deprecated("Atrás en el inicio: salir de la letra")
    override fun onBackPressed() = showGroup(-1)

    private fun build(): View {
        val root = FrameLayout(this).apply { setBackgroundColor(Color.WHITE) }
        bg = WeatherBackground(this)
        root.addView(bg, FrameLayout.LayoutParams(-1, -1))

        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(col, FrameLayout.LayoutParams(-1, -1))

        // arriba: reloj y fecha a la izquierda, botones a la derecha
        val top = LinearLayout(this).apply {
            setPadding(px(48), px(52), px(40), 0)
        }
        val left = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        clock = text(112f).apply { includeFontPadding = false; letterSpacing = -0.02f }
        date = text(24f)
        weatherLine = text(20f)
        left.addView(clock); left.addView(date); left.addView(weatherLine)
        top.addView(left, LinearLayout.LayoutParams(0, -2, 1f))

        val grid = GridLayout(this).apply { columnCount = 2 }
        light = QuickButton(this, Icon.SUN)
        mode = QuickButton(this, Icon.PAGE)
        wifi = QuickButton(this, Icon.WIFI)
        bt = QuickButton(this, Icon.BT)
        rotate = QuickButton(this, Icon.ROTATE)
        volume = QuickButton(this, Icon.SPEAKER)
        for (q in listOf(light, mode, wifi, bt, rotate, volume)) {
            grid.addView(q, GridLayout.LayoutParams().apply {
                width = px(105); height = px(84); setMargins(px(5), px(5), px(5), px(5))
            })
        }
        light.setOnClickListener { Lights.toggle(this); renderButtons() }
        mode.setOnClickListener { Lights.toggleMode(this); renderButtons() }
        wifi.setOnClickListener { toggleWifi() }
        bt.setOnClickListener { toggleBluetooth() }
        rotate.setOnClickListener { setRotation(Rotation.nextValue(this)) }
        light.setOnLongClickListener { openPanel(); true }
        mode.setOnLongClickListener { openPanel(); true }
        wifi.setOnLongClickListener { open(Intent(Settings.ACTION_WIFI_SETTINGS)); true }
        bt.setOnLongClickListener { open(Intent(Settings.ACTION_BLUETOOTH_SETTINGS)); true }
        rotate.setOnLongClickListener { setRotation(0); true }
        volume.setOnClickListener { openVolume() }
        volume.setOnLongClickListener { open(Intent(Settings.ACTION_SOUND_SETTINGS)); true }
        val right = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL }
        quick = right
        right.addView(grid)
        right.addView(text(13f).apply { text = "toque: activar · mantener: ajustes"; setTextColor(0xFF333333.toInt()) })
        top.addView(right)
        col.addView(top)

        // centro: lista (favoritas o letra) e índice A-Z
        val mid = LinearLayout(this)
        listBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(px(48), px(30), px(16), 0) }
        mid.addView(listBox, LinearLayout.LayoutParams(0, -1, 1f))
        index = AppIndex(this).apply { onSelect = { showGroup(it) } }
        mid.addView(index, LinearLayout.LayoutParams(px(88), -1).apply { setMargins(0, px(30), px(12), px(12)) })
        col.addView(mid, LinearLayout.LayoutParams(-1, 0, 1f))
        // la lista sabe cuántas filas caben cuando ya tiene tamaño
        listBox.addOnLayoutChangeListener { _, _, t, _, bm, _, ot, _, ob -> if (bm - t != ob - ot) ui.post { renderList() } }

        // abajo: avisos y engranaje
        bottom = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(Color.WHITE)
            setPadding(px(48), px(14), px(24), px(22))
        }
        summary = text(21f).apply { setOnClickListener { open(Intent(this@HomeActivity, NoticesActivity::class.java)) } }
        bottom.addView(summary, LinearLayout.LayoutParams(0, -2, 1f))
        val gear = QuickButton(this, Icon.GEAR).apply {
            contentDescription = "Ajustes"
            setOnClickListener { open(Apps.settingsIntent()) }
            setOnLongClickListener { open(Intent(this@HomeActivity, LauncherSettingsActivity::class.java)); true }
        }
        bottom.addView(gear, LinearLayout.LayoutParams(px(56), px(56)))
        col.addView(View(this).apply { setBackgroundColor(Color.BLACK) }, LinearLayout.LayoutParams(-1, px(2)))
        col.addView(bottom)

        // el gráfico del tiempo va justo encima de la barra de avisos
        root.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            bg.chartBottom = bottom.top - px(40).toFloat()
            bg.chartTop = bg.chartBottom - px(150).toFloat()
            bg.landscapeBox = if (root.width > root.height) weatherBox(root) else null
            // la gráfica no pasa por debajo del índice de letras
            val r = IntArray(2).also { root.getLocationInWindow(it) }
            val i = IntArray(2).also { index.getLocationInWindow(it) }
            bg.chartRight = (i[0] - r[0] - px(20)).toFloat()
        }
        // pulsación larga en el fondo: ajustes del launcher
        root.setOnLongClickListener { open(Intent(this, LauncherSettingsActivity::class.java)); true }
        return root
    }

    /** Apaisado: hueco entre el reloj y los botones rápidos, a la altura del reloj. */
    private fun weatherBox(root: View): android.graphics.RectF? {
        val r = IntArray(2).also { root.getLocationInWindow(it) }
        val c = IntArray(2).also { clock.getLocationInWindow(it) }
        val q = IntArray(2).also { quick.getLocationInWindow(it) }
        // la vista del reloj ocupa toda la columna: cuenta el ancho del texto («00:00», para que no baile)
        val left = c[0] - r[0] + clock.paddingLeft + clock.paint.measureText("00:00").toInt() + px(40)
        val right = q[0] - r[0] - px(40)
        val top = c[1] - r[1] + clock.height * 0.12f
        val bottom = c[1] - r[1] + clock.height * 0.88f
        return if (right - left < px(160)) null
        else android.graphics.RectF(left.toFloat(), top, right.toFloat(), bottom)
    }

    private fun renderClock() {
        clock.text = DateFormat.getTimeFormat(this).format(Date())
        val day = DateFormat.format("EEE, d MMM", Date()).toString()
        date.text = if (battery.isEmpty()) day else "$day · $battery"
    }

    private fun renderButtons() {
        light.active = Lights.isOn()
        light.label = Lights.label()
        val m = EpadHw.mode()
        mode.label = m?.label ?: "Pantalla"
        mode.active = false
        wifi.active = getSystemService(WifiManager::class.java)?.isWifiEnabled == true
        wifi.label = "Wi-Fi"
        bt.active = bluetooth()?.isEnabled == true
        bt.label = if (bt.active) "BT" else "BT apagado"
        rotate.active = false
        rotate.label = Rotation.labels[Rotation.get(this)]
        volume.active = false
        volume.label = Volume.label(this)
    }

    /** Favoritas, o las apps de la letra elegida, en páginas que caben enteras (sin scroll). */
    private fun renderList() {
        if (listBox.height == 0) return
        // primera entrada del índice: todas las apps (cajón por páginas)
        groups = listOf(AppGroup("Todas", emptySet(), "≡")) + apps.groups()
        if (group >= groups.size) group = -1
        val g = groups.getOrNull(group)
        val items = g?.let { apps.inGroup(it) } ?: apps.favorites
        // filas más bajas y, si hay ancho (vertical: ~650 dp), varias columnas: antes cabían muy pocas apps
        val rowH = px(AppRow.HEIGHT_DP) + px(8)
        val header = if (g != null) px(70) else 0
        val cols = maxOf(1, (listBox.width - listBox.paddingLeft - listBox.paddingRight) / px(280))
        val rows = maxOf(1, (listBox.height - listBox.paddingTop - header - px(68)) / rowH)
        val perPage = rows * cols
        val pages = maxOf(1, (items.size + perPage - 1) / perPage)
        page = page.coerceIn(0, pages - 1)
        listBox.removeAllViews()
        g?.let { gr ->
            val head = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
            head.addView(text(56f).apply { text = gr.label; paint.isFakeBoldText = true },
                LinearLayout.LayoutParams(0, -2, 1f))
            head.addView(text(19f).apply { text = "× Favoritas"; setOnClickListener { showGroup(-1) } })
            listBox.addView(head)
        }
        val shown = items.drop(page * perPage).take(perPage)
        // por columnas: se lee de arriba abajo y luego la columna siguiente
        val grid = LinearLayout(this)
        for (c in 0 until cols) {
            val column = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            grid.addView(column, LinearLayout.LayoutParams(0, -2, 1f).apply { if (c > 0) leftMargin = px(16) })
            for (a in shown.drop(c * rows).take(rows)) column.addView(appRow(a),
                LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = px(8) })
        }
        listBox.addView(grid)
        if (pages > 1) {
            // botones grandes: las flechas de texto eran difíciles de acertar
            // de ancho fijo y centrados: a todo lo ancho, en apaisado ocupaban media pantalla
            val nav = LinearLayout(this).apply { gravity = Gravity.CENTER }
            nav.addView(pageButton("‹  Anterior", page > 0) { page--; renderList() }, LinearLayout.LayoutParams(px(200), px(60)))
            nav.addView(text(20f).apply { text = "${page + 1} / $pages"; gravity = Gravity.CENTER },
                LinearLayout.LayoutParams(px(90), -2))
            nav.addView(pageButton("Siguiente  ›", page < pages - 1) { page++; renderList() }, LinearLayout.LayoutParams(px(200), px(60)))
            listBox.addView(nav)
        }
        index.groups = groups
        index.selected = group
    }

    private fun appRow(a: App) = AppRow(this).apply {
        app = a
        icon = apps.monochromeIcon(a)
        count = Notices.countFor(a.component.packageName)
        setOnClickListener { apps.launch(a) }
        setOnLongClickListener { appMenu(this, a); true }
    }

    private fun showGroup(i: Int) {
        group = i
        page = 0
        renderList()
    }

    private fun appMenu(anchor: View, a: App) {
        val favs = apps.favorites
        PopupMenu(this, anchor).apply {
            val isFav = favs.any { it.key == a.key }
            menu.add(if (isFav) "Quitar de favoritas" else "Añadir a favoritas").setOnMenuItemClickListener {
                apps.favorites = if (isFav) favs.filter { it.key != a.key } else favs + a
                renderList(); true
            }
            // modo de tinta al abrir esta app (InkModeService); dentro se puede cambiar con el botón o el azulejo
            val pkg = a.component.packageName
            val chosen = InkPolicy.forApp(this@HomeActivity, pkg)
            val ink = menu.addSubMenu("Tinta: " + (chosen?.label ?: "por omisión (${InkPolicy.defaultMode.label})"))
            ink.add("Por omisión (${InkPolicy.defaultMode.label})").setOnMenuItemClickListener {
                InkPolicy.setForApp(this@HomeActivity, pkg, null); true
            }
            for (m in EpadHw.Mode.entries) ink.add(m.label).setOnMenuItemClickListener {
                InkPolicy.setForApp(this@HomeActivity, pkg, m); true
            }
            menu.add("Información de la app").setOnMenuItemClickListener { apps.openDetails(a); true }
            show()
        }
    }

    private fun openVolume() = VolumePanel(this) { renderButtons() }.apply {
        setOnDismissListener { renderButtons() }
    }.show()

    private fun openPanel() = LightPanel(this) { renderButtons() }.apply {
        setOnDismissListener { renderButtons() }
    }.show()

    private fun setRotation(r: Int) {
        if (!Rotation.set(this, r)) open(Intent(Settings.ACTION_DISPLAY_SETTINGS))
        renderButtons()
    }

    private fun bluetooth(): BluetoothAdapter? = getSystemService(BluetoothManager::class.java)?.adapter

    @Suppress("DEPRECATION")
    private fun toggleWifi() {
        val wm = getSystemService(WifiManager::class.java) ?: return
        // como app de sistema se puede alternar directamente; si no, se abren los ajustes
        if (!wm.setWifiEnabled(!wm.isWifiEnabled)) open(Intent(Settings.ACTION_WIFI_SETTINGS))
    }

    @Suppress("DEPRECATION", "MissingPermission")
    private fun toggleBluetooth() {
        val a = bluetooth() ?: return
        val ok = runCatching { if (a.isEnabled) a.disable() else a.enable() }.getOrDefault(false)
        if (!ok) open(Intent(Settings.ACTION_BLUETOOTH_SETTINGS))
    }

    private fun refreshWeather() {
        val c = weather.cached()
        if (c != null && System.currentTimeMillis() - c.fetchedAt < 30 * 60_000) return
        if (c == null) weatherLine.text = "Tiempo: buscando… (ciudad: mantener pulsado el fondo)"
        io.execute {
            val f = weather.fetch()
            ui.post {
                if (f != null) showWeather(f)
                // sin red todavía (p. ej. recién encendida): otro intento en un minuto si sigue a la vista
                else ui.postDelayed({ if (resumed) refreshWeather() }, 60_000)
            }
        }
    }

    /**
     * Tiempo animado (opción de los ajustes del launcher): al volver al inicio como mucho cada 10 min, o con una
     * previsión nueva. Pasa la tinta a modo rápido mientras dura y al acabar vuelve al modo anterior con un GC.
     */
    private fun maybeAnimateWeather(fresh: Boolean) {
        val p = getSharedPreferences("launcher", MODE_PRIVATE)
        if (!p.getBoolean(ANIM_KEY, true) || bg.playing) return
        val now = System.currentTimeMillis()
        if (!fresh && now - p.getLong("anim_last", 0) < 10 * 60_000) return
        p.edit().putLong("anim_last", now).apply()
        val prev = EpadHw.mode()
        if (prev != null && prev != EpadHw.Mode.BROWSING) EpadHw.setMode(EpadHw.Mode.BROWSING)
        bg.play {
            if (prev != null && prev != EpadHw.Mode.BROWSING) EpadHw.setMode(prev)
            ui.postDelayed({ EpadHw.fullRefresh() }, 300)
        }
    }

    private fun showWeather(f: Forecast) {
        val fresh = bg.forecast?.fetchedAt != f.fetchedAt
        bg.forecast = f
        val place = if (f.place.isNotEmpty()) "${f.place.substringBefore(',')} · " else ""
        val hum = if (f.humidity >= 0) " · humedad ${f.humidity}%" else ""
        weatherLine.text = "$place${f.temp.roundToInt()}°$hum · ${f.description} · " +
            "máx ${f.max.roundToInt()}° mín ${f.min.roundToInt()}° · lluvia ${f.rainProb}%"
        if (fresh && hasWindowFocus()) maybeAnimateWeather(true)
    }

    private fun open(i: Intent) {
        runCatching { startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }

    private fun text(sp: Float) = TextView(this).apply { textSize = sp; setTextColor(Color.BLACK) }

    private fun pageButton(label: String, enabled: Boolean, go: () -> Unit) = text(20f).apply {
        text = label
        gravity = Gravity.CENTER
        background = android.graphics.drawable.GradientDrawable().apply {
            cornerRadius = px(14).toFloat(); setStroke(px(2), Color.BLACK); setColor(Color.WHITE)
        }
        // desactivado: transparencia, no texto gris (el texto de alto contraste lo pinta blanco con contorno negro)
        if (enabled) setOnClickListener { go() } else alpha = 0.35f
    }

    private fun px(dp: Int) = (dp * d).roundToInt()

    companion object {
        const val ANIM_KEY = "anim_weather"
    }
}
