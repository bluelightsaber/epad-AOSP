package org.epad.launcher

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.graphics.drawable.Icon as TileIcon

/** Mosaicos de Ajustes rápidos propios de la E-PAD, con los mismos iconos de trazo que el inicio. */
abstract class EpadTile(private val icon: Icon) : TileService() {
    abstract fun render(t: Tile)

    override fun onStartListening() = update()

    protected fun update() {
        val t = qsTile ?: return
        t.icon = TileIcon.createWithBitmap(bitmap(icon))
        render(t)
        t.updateTile()
    }

    private fun bitmap(i: Icon): Bitmap {
        val size = (24 * resources.displayMetrics.density).toInt()
        val b = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE; strokeWidth = 2f; color = Color.WHITE
            strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
        }
        Icons.draw(Canvas(b), i, 0f, 0f, size.toFloat(), p)
        return b
    }
}

class LightTile : EpadTile(Icon.SUN) {
    override fun render(t: Tile) {
        t.label = Lights.label()
        t.state = if (Lights.isOn()) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
    }

    override fun onClick() { Lights.toggle(this); update() }
}

class InkModeTile : EpadTile(Icon.PAGE) {
    override fun render(t: Tile) {
        val m = EpadHw.mode()
        t.label = m?.label ?: "Tinta"
        t.state = if (m == null) Tile.STATE_UNAVAILABLE else Tile.STATE_INACTIVE
    }

    override fun onClick() { Lights.toggleMode(this); update() }
}

/** Refresco completo (GC): limpia los fantasmas en cualquier app. */
class RefreshTile : EpadTile(Icon.CLEAR) {
    override fun render(t: Tile) {
        t.label = "Limpiar tinta"
        t.state = if (EpadHw.present) Tile.STATE_INACTIVE else Tile.STATE_UNAVAILABLE
    }

    override fun onClick() { EpadHw.fullRefresh() }
}

class RotateTile : EpadTile(Icon.ROTATE) {
    override fun render(t: Tile) {
        t.label = Rotation.labels[Rotation.get(this)]
        t.state = Tile.STATE_INACTIVE
    }

    override fun onClick() { Rotation.next(this); update() }
}

/** Volumen sin teclas: el mosaico abre el mismo panel que el botón del inicio. */
class VolumeTile : EpadTile(Icon.SPEAKER) {
    override fun render(t: Tile) {
        t.label = "Volumen"
        t.subtitle = Volume.label(this)
        t.state = Tile.STATE_INACTIVE
    }

    override fun onClick() = showDialog(VolumePanel(this) { update() }.apply { setOnDismissListener { update() } })
}
