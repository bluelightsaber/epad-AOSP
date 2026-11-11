package org.epad.launcher

import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.media.AudioManager
import android.media.ToneGenerator
import android.view.Gravity
import android.view.Window
import android.widget.Button
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView

/**
 * Panel de volumen (botón Volumen del inicio y mosaico de Ajustes rápidos): la E-PAD no tiene teclas de volumen.
 * Botones grandes de bajar/subir por paso y barra para saltos; el cambio se aplica sin sonar (puede ser de noche),
 * y «Probar» da un pitido corto al volumen de multimedia.
 */
class VolumePanel(ctx: Context, private val onChanged: () -> Unit = {}) : Dialog(ctx) {
    private val d = ctx.resources.displayMetrics.density
    private val am = ctx.getSystemService(AudioManager::class.java)

    init {
        requestWindowFeature(Window.FEATURE_NO_TITLE)
        window?.setBackgroundDrawable(GradientDrawable().apply {
            setColor(Color.WHITE); setStroke((3 * d).toInt(), Color.BLACK); cornerRadius = 18 * d
        })
        window?.setWindowAnimations(0)
        val box = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            val p = (28 * d).toInt()
            setPadding(p, p, p, p)
        }
        box.addView(title("Volumen"))
        for ((stream, name) in Volume.streams) box.addView(row(stream, name))
        box.addView(Button(ctx).apply {
            text = "Probar sonido"; textSize = 20f
            setOnClickListener {
                val tg = ToneGenerator(AudioManager.STREAM_MUSIC, 60)
                tg.startTone(ToneGenerator.TONE_PROP_BEEP, 200)
                postDelayed({ tg.release() }, 500)
            }
        })
        box.addView(Button(ctx).apply { text = "Cerrar"; textSize = 20f; setOnClickListener { dismiss() } })
        setContentView(box)
        window?.setLayout((ctx.resources.displayMetrics.widthPixels * 0.9).toInt(), -2)
        window?.setGravity(Gravity.CENTER)
    }

    private fun row(stream: Int, name: String): LinearLayout {
        val col = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; setPadding(0, (8 * d).toInt(), 0, 0) }
        val label = TextView(context).apply { textSize = 22f; setTextColor(Color.BLACK) }
        val max = am.getStreamMaxVolume(stream)
        val bar = SeekBar(context).apply { this.max = max; contentDescription = "Volumen de $name" }
        fun show(v: Int) {
            label.text = if (v == 0) "$name: sin sonido" else "$name: $v de $max"
            bar.progress = v
        }
        fun set(v: Int) {
            Volume.set(am, stream, v.coerceIn(0, max))
            show(am.getStreamVolume(stream)); onChanged()
        }
        bar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar, v: Int, user: Boolean) {
                if (user) label.text = if (v == 0) "$name: sin sonido" else "$name: $v de $max"
            }
            override fun onStartTrackingTouch(s: SeekBar) {}
            override fun onStopTrackingTouch(s: SeekBar) = set(s.progress)
        })
        val line = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val big = LinearLayout.LayoutParams((84 * d).toInt(), (72 * d).toInt())
        line.addView(Button(context).apply {
            text = "−"; textSize = 32f; contentDescription = "Bajar $name"
            setOnClickListener { set(am.getStreamVolume(stream) - 1) }
        }, big)
        line.addView(bar, LinearLayout.LayoutParams(0, (56 * d).toInt(), 1f))
        line.addView(Button(context).apply {
            text = "+"; textSize = 32f; contentDescription = "Subir $name"
            setOnClickListener { set(am.getStreamVolume(stream) + 1) }
        }, big)
        show(am.getStreamVolume(stream))
        col.addView(label); col.addView(line)
        return col
    }

    private fun title(s: String) = TextView(context).apply {
        text = s; textSize = 28f; setTextColor(Color.BLACK); paint.isFakeBoldText = true
        setPadding(0, (12 * d).toInt(), 0, (8 * d).toInt())
    }
}

object Volume {
    val streams = listOf(
        AudioManager.STREAM_MUSIC to "Multimedia",
        AudioManager.STREAM_NOTIFICATION to "Avisos",
        AudioManager.STREAM_ALARM to "Alarma",
    )

    /** Sin FLAG_SHOW_UI ni FLAG_PLAY_SOUND; bajar avisos a 0 puede pedir permiso de No molestar: se ignora. */
    fun set(am: AudioManager, stream: Int, v: Int) {
        try { am.setStreamVolume(stream, v, 0) } catch (_: SecurityException) {}
    }

    fun label(ctx: Context): String {
        val am = ctx.getSystemService(AudioManager::class.java)
        val v = am.getStreamVolume(AudioManager.STREAM_MUSIC)
        return if (v == 0) "Silencio" else "Vol $v/${am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)}"
    }
}
