package org.epad.launcher

import android.content.ComponentName
import android.content.Context
import android.provider.Settings

/** Ajustes del sistema pensados para la tinta, aplicados una sola vez (luego manda el usuario). */
object Defaults {
    private const val VERSION = 2

    fun apply(ctx: Context) {
        val prefs = ctx.getSharedPreferences("defaults", Context.MODE_PRIVATE)
        val done = prefs.getInt("applied", 0)
        if (done >= VERSION) return
        val cr = ctx.contentResolver
        runCatching {
            if (done < 1) {
                // texto de alto contraste: contorno negro/blanco en todo el sistema, se lee mejor en gris de tinta
                Settings.Secure.putInt(cr, "high_text_contrast_enabled", 1)
                // sin animaciones de duración (barras de progreso, transiciones de vistas)
                Settings.Global.putFloat(cr, Settings.Global.ANIMATOR_DURATION_SCALE, 0f)
            }
            if (done < 2) {
                // modo de tinta por app (InkModeService)
                val me = ComponentName(ctx, InkModeService::class.java).flattenToString()
                val cur = Settings.Secure.getString(cr, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES).orEmpty()
                if (me !in cur) Settings.Secure.putString(cr, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
                    if (cur.isEmpty()) me else "$cur:$me")
                Settings.Secure.putInt(cr, Settings.Secure.ACCESSIBILITY_ENABLED, 1)
            }
        }.onSuccess { prefs.edit().putInt("applied", VERSION).apply() }
    }
}
