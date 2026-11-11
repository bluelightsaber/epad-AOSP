package org.epad.launcher

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import kotlin.math.roundToInt

/**
 * Las pantallas de reposo y de apagado las diseña y dibuja la app Pantallas (org.epad.pantallas). El launcher solo
 * le pasa el tiempo que ya tiene descargado y apaga la luz frontal con la pantalla.
 */
object SleepScreen {
    const val PANTALLAS = "org.epad.pantallas"
    private var last = ""
    private var sentAt = 0L
    private var registered = false

    /** Registrar una vez (contexto de la aplicación): minuto a minuto mira si ha cambiado el tiempo. */
    fun start(ctx: Context) {
        val app = ctx.applicationContext
        if (!registered) {
            registered = true
            app.registerReceiver(object : BroadcastReceiver() {
                override fun onReceive(c: Context, i: Intent) = update(app)
            }, IntentFilter().apply { addAction(Intent.ACTION_TIME_TICK); addAction(Intent.ACTION_DATE_CHANGED) })
            // la luz frontal no se queda encendida con la pantalla apagada (gasta más que todo lo demás)
            app.registerReceiver(object : BroadcastReceiver() {
                override fun onReceive(c: Context, i: Intent) {
                    if (i.action == Intent.ACTION_SCREEN_OFF) Lights.screenOff(app) else Lights.screenOn(app)
                }
            }, IntentFilter().apply { addAction(Intent.ACTION_SCREEN_OFF); addAction(Intent.ACTION_SCREEN_ON) })
        }
        update(app)
    }

    fun update(ctx: Context) {
        val f = Weather(ctx).cached()
        if (f == null) { android.util.Log.i("EpadLauncher", "tiempo: sin previsión guardada"); return }
        val t = "${f.temp.roundToInt()}° ${f.description}".trim()
        // se repite cada media hora por si Pantallas no lo recibió (p. ej. al arrancar las dos a la vez)
        val now = System.currentTimeMillis()
        if (t == last && now - sentAt < 30 * 60_000L) return
        last = t; sentAt = now
        android.util.Log.i("EpadLauncher", "tiempo → Pantallas: $t")
        runCatching { ctx.sendBroadcast(Intent("$PANTALLAS.TIEMPO").setPackage(PANTALLAS).putExtra("texto", t)) }
            .onFailure { android.util.Log.w("EpadLauncher", "tiempo → Pantallas", it) }
    }

    fun open(ctx: Context) {
        runCatching { ctx.startActivity(Intent().setClassName(PANTALLAS, "$PANTALLAS.MainActivity")) }
    }
}
