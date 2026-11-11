package org.epad.launcher

import android.app.Activity
import android.os.Build
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController

/**
 * Pantallas de borde a borde (obligatorio con targetSdk ≥ 35): el contenido se aparta de la barra de estado y de
 * la de navegación (si no, los tres botones tapaban la fila de abajo, p. ej. el engranaje), y ambas barras quedan
 * blancas con iconos negros, sin el velo gris que Android pone detrás de los botones.
 */
fun Activity.fitSystemBars(root: View) {
    if (Build.VERSION.SDK_INT < 30) return  // Android 7 de fábrica: sin borde a borde
    window.isNavigationBarContrastEnforced = false
    window.isStatusBarContrastEnforced = false
    val light = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or
        WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
    window.insetsController?.setSystemBarsAppearance(light, light)
    root.setOnApplyWindowInsetsListener { v, insets ->
        val b = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
        v.setPadding(b.left, b.top, b.right, b.bottom)
        WindowInsets.CONSUMED
    }
    root.requestApplyInsets()
}
