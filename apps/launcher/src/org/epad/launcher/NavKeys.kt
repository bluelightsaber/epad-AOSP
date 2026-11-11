package org.epad.launcher

import android.content.BroadcastReceiver
import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.util.Log
import android.view.KeyEvent
import android.view.WindowManager
import java.io.File

/**
 * Botones fijos de la barra de navegación (tinta, luz, giro, volumen), para usarlos dentro de cualquier app.
 * SystemUI los pinta como teclas propias: «key(CÓDIGO:content://org.epad.launcher.navicons/NOMBRE)» en
 * config_navBarLayout. Con config_navBarCustomKeyReceiver = este paquete, al pulsarlos no inyecta la tecla
 * (no pasaría por la accesibilidad y llegaría a la app) sino que avisa con NAV_BAR_CUSTOM_KEY a NavKeyReceiver.
 */
object NavKeys {
    // Botones de mando 1–4: ninguna app de la E-PAD los espera
    val icons = mapOf(
        KeyEvent.KEYCODE_BUTTON_1 to Icon.PAGE,
        KeyEvent.KEYCODE_BUTTON_2 to Icon.SUN,
        KeyEvent.KEYCODE_BUTTON_3 to Icon.ROTATE,
        KeyEvent.KEYCODE_BUTTON_4 to Icon.SPEAKER,
    )

    fun handle(ctx: Context, keyCode: Int) {
        when (icons[keyCode]) {
            Icon.PAGE -> Lights.toggleMode(ctx)
            Icon.SUN -> Lights.toggle(ctx)
            Icon.ROTATE -> Rotation.next(ctx)
            Icon.SPEAKER -> showVolume()
            else -> Log.w("EpadNav", "tecla desconocida $keyCode")
        }
    }

    /** El botón de tinta enseña el modo actual: SystemUI lo recarga al avisar del cambio en su URI. */
    val MODE_URI: Uri = Uri.parse("content://org.epad.launcher.navicons/page")

    fun modeChanged(ctx: Context) = ctx.contentResolver.notifyChange(MODE_URI, null)

    fun modeIcon() = when (EpadHw.mode()) {
        EpadHw.Mode.MIXED -> Icon.MIXED
        EpadHw.Mode.BROWSING -> Icon.GLOBE
        else -> Icon.PAGE
    }

    /** El panel sale sobre cualquier app como ventana de accesibilidad de InkModeService (no hace falta permiso). */
    private fun showVolume() {
        val svc = InkModeService.instance ?: run { Log.w("EpadNav", "sin servicio de accesibilidad"); return }
        runCatching {
            VolumePanel(svc).apply { window?.setType(WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY) }.show()
        }.onFailure { Log.w("EpadNav", "panel de volumen", it) }
    }
}

/** Pulsaciones de los botones fijos; solo SystemUI (STATUS_BAR_SERVICE, de firma) puede enviarlas. */
class NavKeyReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) = NavKeys.handle(ctx, intent.getIntExtra("keycode", 0))
}

/** Iconos PNG de los botones para SystemUI, con los mismos trazos que el inicio. Solo sirve estas imágenes. */
class NavIconProvider : ContentProvider() {
    override fun onCreate() = true

    override fun getType(uri: Uri) = "image/png"

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor? {
        val ctx = context ?: return null
        val named = Icon.entries.firstOrNull { it.name.equals(uri.lastPathSegment, true) } ?: return null
        val icon = if (named == Icon.PAGE) NavKeys.modeIcon() else named
        val f = File(ctx.createDeviceProtectedStorageContext().cacheDir, "nav-${icon.name}.png")
        if (!f.exists()) {
            val size = (24 * ctx.resources.displayMetrics.density).toInt()
            val b = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
            val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE; strokeWidth = 2f; color = Color.BLACK
                strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
            }
            Icons.draw(Canvas(b), icon, 0f, 0f, size.toFloat(), p)
            f.outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
        return ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    override fun query(u: Uri, p: Array<String>?, s: String?, a: Array<String>?, o: String?) = null
    override fun insert(u: Uri, v: ContentValues?) = null
    override fun delete(u: Uri, s: String?, a: Array<String>?) = 0
    override fun update(u: Uri, v: ContentValues?, s: String?, a: Array<String>?) = 0
}
