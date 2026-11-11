package org.epad.launcher

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import java.util.Calendar

/**
 * Luz nocturna con los LED reales: de [start] a [end] la luz encendida pasa a solo cálida y más tenue;
 * al acabar vuelve a los niveles de día. Si la luz está apagada, solo cambia lo que se usará al encenderla.
 */
object NightLight {
    private fun prefs(ctx: Context) = ctx.getSharedPreferences("night", Context.MODE_PRIVATE)

    fun enabled(ctx: Context) = prefs(ctx).getBoolean("on", false)
    fun start(ctx: Context) = prefs(ctx).getInt("start", 21)
    fun end(ctx: Context) = prefs(ctx).getInt("end", 7)
    fun warm(ctx: Context) = prefs(ctx).getInt("warm", 25)

    fun configure(ctx: Context, on: Boolean, start: Int, end: Int, warm: Int) {
        prefs(ctx).edit().putBoolean("on", on).putInt("start", start).putInt("end", end).putInt("warm", warm).apply()
        apply(ctx)
    }

    fun isNight(ctx: Context, now: Calendar = Calendar.getInstance()): Boolean {
        if (!enabled(ctx)) return false
        val h = now.get(Calendar.HOUR_OF_DAY)
        val s = start(ctx); val e = end(ctx)
        return if (s <= e) h in s until e else h >= s || h < e
    }

    /** Aplica el tramo actual (si cambió) y programa el siguiente cambio. */
    fun apply(ctx: Context) {
        val p = prefs(ctx)
        val night = isNight(ctx)
        if (night != p.getBoolean("was_night", false)) {
            p.edit().putBoolean("was_night", night).apply()
            if (Lights.isOn()) Lights.applyLevels(ctx, night)
        }
        schedule(ctx)
    }

    private fun schedule(ctx: Context) {
        val am = ctx.getSystemService(AlarmManager::class.java) ?: return
        val pi = PendingIntent.getBroadcast(ctx, 0, Intent(ctx, NightLightReceiver::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        am.cancel(pi)
        if (!enabled(ctx)) return
        val next = Calendar.getInstance().apply {
            set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
            val target = if (isNight(ctx)) end(ctx) else start(ctx)
            set(Calendar.HOUR_OF_DAY, target)
            if (timeInMillis <= System.currentTimeMillis()) add(Calendar.DAY_OF_MONTH, 1)
        }
        // ventana de un minuto: no hace falta el permiso de alarmas exactas
        am.setWindow(AlarmManager.RTC_WAKEUP, next.timeInMillis, 60_000, pi)
    }
}

/** Cambio de tramo, arranque y cambios de hora. */
class NightLightReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, i: Intent) = NightLight.apply(ctx)
}
