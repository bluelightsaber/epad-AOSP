package org.epad.launcher

import android.accessibilityservice.AccessibilityService
import android.content.ComponentName
import android.content.Context
import android.view.accessibility.AccessibilityEvent
import java.util.concurrent.Executors

/**
 * Modo de tinta por app: al pasar una app a primer plano se pone el modo que tenga elegido (pulsación larga en
 * la lista del inicio) o, si no, el modo por omisión (Mixto). Dentro de la app se puede cambiar con el botón o
 * el azulejo; ese cambio dura hasta la siguiente app.
 */
object InkPolicy {
    private fun prefs(ctx: Context) = ctx.getSharedPreferences("ink_apps", Context.MODE_PRIVATE)

    val defaultMode = EpadHw.Mode.MIXED

    fun forApp(ctx: Context, pkg: String): EpadHw.Mode? = EpadHw.Mode.of(prefs(ctx).getInt(pkg, -1))

    fun setForApp(ctx: Context, pkg: String, m: EpadHw.Mode?) {
        prefs(ctx).edit().apply { if (m == null) remove(pkg) else putInt(pkg, m.code) }.apply()
    }

    /** Pone el modo de [pkg]; solo escribe si cambia (repetir órdenes de modo al T1000 lo puede colgar). */
    fun apply(ctx: Context, pkg: String) {
        val want = forApp(ctx, pkg) ?: defaultMode
        val cur = EpadHw.mode() ?: return
        if (cur != want) EpadHw.setMode(want)
        NavKeys.modeChanged(ctx)   // también al arrancar: SystemUI pudo pedir el icono antes de fijar el modo
    }
}

/** Avisa de qué app pasa a primer plano (solo cambios de ventana; no lee su contenido). */
class InkModeService : AccessibilityService() {
    private var last: String? = null
    // Fuera del hilo principal y en orden: leer las preferencias al arrancar puede tardar más de 5 s (ANR)
    private val worker = Executors.newSingleThreadExecutor()

    private fun applyAsync(pkg: String) = worker.execute { InkPolicy.apply(this, pkg) }

    override fun onServiceConnected() {
        // al arrancar el T1000 está en lectura: se pasa al modo por omisión
        applyAsync(packageName)
        instance = this
        last = packageName
    }

    override fun onAccessibilityEvent(e: AccessibilityEvent) {
        if (e.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val pkg = e.packageName?.toString() ?: return
        val cls = e.className?.toString() ?: return
        if (pkg == last) return
        // solo actividades: diálogos, menús, teclado o la cortina de avisos no cambian el modo
        val activity = runCatching { packageManager.getActivityInfo(ComponentName(pkg, cls), 0) }.isSuccess
        if (!activity) return
        last = pkg
        applyAsync(pkg)
    }

    override fun onInterrupt() {}

    companion object {
        /** Servicio conectado: NavKeys abre el panel de volumen como ventana suya sobre cualquier app. */
        var instance: InkModeService? = null
            private set
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        worker.shutdown()
        super.onDestroy()
    }
}
