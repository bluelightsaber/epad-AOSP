package org.epad.pantallas

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.util.Log
import java.io.File
import java.util.concurrent.Executors

/** Proceso persistente: mantiene al día en el driver las dos imágenes, porque ni el reposo ni el apagado esperan. */
class App : Application() {
    override fun onCreate() {
        super.onCreate()
        Updater.start(this)
    }
}

class WeatherReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        val t = i.getStringExtra("texto") ?: return
        Log.i("Pantallas", "tiempo: $t")
        if (t == Store.weather(c)) return
        Store.setWeather(c, t)
        Updater.refresh(c)
    }
}

object Updater {
    private const val T1000 = "/sys/bus/i2c/devices/1-0006"
    private val io = Executors.newSingleThreadExecutor()
    private val last = HashMap<Screen, String>()
    @Volatile private var battery = -1
    @Volatile private var charging = false
    private var started = false

    fun start(ctx: Context) {
        val app = ctx.applicationContext
        if (started) return
        started = true
        app.registerReceiver(object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) {
                if (i.action == Intent.ACTION_BATTERY_CHANGED) {
                    val scale = i.getIntExtra(BatteryManager.EXTRA_SCALE, 100).coerceAtLeast(1)
                    battery = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) * 100 / scale
                    charging = i.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0
                }
                refresh(app)
            }
        }, IntentFilter().apply {
            addAction(Intent.ACTION_TIME_TICK); addAction(Intent.ACTION_TIME_CHANGED)
            addAction(Intent.ACTION_TIMEZONE_CHANGED); addAction(Intent.ACTION_DATE_CHANGED)
            addAction(Intent.ACTION_LOCALE_CHANGED); addAction(Intent.ACTION_BATTERY_CHANGED)
        })
        refresh(app, force = true)
    }

    fun live(ctx: Context) = Live(System.currentTimeMillis(), battery, charging, Store.weather(ctx))

    /** Qué datos usa el diseño: solo si cambia alguno hace falta volver a dibujar. */
    private fun key(ctx: Context, d: Design, l: Live): String {
        val used = d.elements.map { it.kind }.toSet()
        return buildString {
            if (Kind.HORA in used) append("h").append(Render.textOf(ctx, Element(Kind.HORA), l))
            if (Kind.FECHA in used) append("f").append(Render.textOf(ctx, Element(Kind.FECHA), l))
            if (Kind.BATERIA in used) append("b").append(l.battery).append(l.charging)
            if (Kind.TIEMPO in used) append("t").append(l.weather)
        }
    }

    /** Vuelve a dibujar lo que haga falta; force = tras cambiar un diseño. */
    fun refresh(ctx: Context, force: Boolean = false) {
        val app = ctx.applicationContext
        io.execute {
            for (s in Screen.values()) runCatching {
                val d = Store.load(app, s)
                if (s == Screen.REPOSO && !d.enabled) {
                    if (force || last[s] != "off") write("sleep_screen", "0".toByteArray())
                    last[s] = "off"
                    return@runCatching
                }
                val l = live(app)
                val k = key(app, d, l)
                if (!force && last[s] == k) return@runCatching
                val bmp = Render.portrait(app, s, d, l)
                val bytes = Render.panel(bmp)
                bmp.recycle()
                if (write(s.attr, bytes)) last[s] = k
            }.onFailure { Log.w("Pantallas", "no se pudo preparar ${s.key}", it) }
        }
    }

    private fun write(attr: String, data: ByteArray): Boolean {
        val f = File("$T1000/$attr")
        if (!f.exists()) return false
        return runCatching { f.outputStream().use { it.write(data) } }
            .onFailure { Log.w("Pantallas", "escribir $attr", it) }.isSuccess
    }
}
