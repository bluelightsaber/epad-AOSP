package org.epad.pantallas

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.util.concurrent.Executors

/**
 * Editor: arriba se elige la pantalla (reposo o apagado), a la izquierda la vista previa y a la derecha el fondo y
 * la lista de elementos. Sin ruedas ni animaciones (en tinta refrescan a cada paso): botones que van cambiando.
 */
class MainActivity : Activity() {
    private val io = Executors.newSingleThreadExecutor()
    private val ui = Handler(Looper.getMainLooper())
    private var screen = Screen.REPOSO
    private lateinit var design: Design
    private lateinit var preview: ImageView
    private lateinit var controls: LinearLayout
    private lateinit var tabs: LinearLayout
    private val d get() = resources.displayMetrics.density
    private var gen = 0

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        screen = enumOr(b?.getString("screen") ?: "", Screen.REPOSO)
        design = Store.load(this, screen)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.WHITE)
            val p = (20 * d).toInt(); setPadding(p, p, p, p)
        }
        tabs = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        root.addView(tabs)
        val body = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        preview = ImageView(this).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            adjustViewBounds = true
            background = GradientDrawable().apply { setColor(Color.WHITE); setStroke((2 * d).toInt(), Color.BLACK) }
            val p = (2 * d).toInt(); setPadding(p, p, p, p)
        }
        body.addView(preview, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 0.42f).apply {
            topMargin = (12 * d).toInt()
        })
        controls = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val p = (16 * d).toInt(); setPadding(p, 0, 0, p)
        }
        body.addView(ScrollView(this).apply { addView(controls) },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 0.58f))
        root.addView(body, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)
        root.setOnApplyWindowInsetsListener { v, ins ->
            val p = (20 * d).toInt()
            v.setPadding(p, p + ins.systemWindowInsetTop, p, p + ins.systemWindowInsetBottom); ins
        }
        build()
    }

    override fun onSaveInstanceState(out: Bundle) {
        super.onSaveInstanceState(out)
        out.putString("screen", screen.name)
    }

    private fun build() {
        tabs.removeAllViews()
        tabs.addView(TextView(this).apply {
            text = "Pantallas"; textSize = 30f; setTextColor(Color.BLACK); paint.isFakeBoldText = true
            setPadding(0, 0, (24 * d).toInt(), 0)
        })
        for (s in Screen.values()) tabs.addView(button(if (s == screen) "■ ${s.label}" else s.label) {
            if (s != screen) { screen = s; design = Store.load(this, s); build() }
        })
        controls.removeAllViews()
        controls.addView(text(when (screen) {
            Screen.REPOSO -> "Lo que se queda en la tinta al apagar la pantalla."
            Screen.APAGADO -> "Lo que se queda en la tinta al apagar la tablet."
        }))
        if (screen == Screen.REPOSO) controls.addView(CheckBox(this).apply {
            text = "Mostrar pantalla de reposo"; textSize = 20f; isChecked = design.enabled
            setOnCheckedChangeListener { _, v -> design.enabled = v; changed() }
        })

        controls.addView(title("Fondo"))
        controls.addView(row(
            button(if (design.hasImage) "Cambiar imagen" else "Elegir imagen") { pick() },
            button("Quitar") { design.hasImage = false; Store.image(this, screen).delete(); changed(rebuild = true) }
                .apply { isEnabled = design.hasImage },
            button(if (design.fill) "Llenar pantalla" else "Encajar entera") {
                design.fill = !design.fill; changed(rebuild = true)
            }.apply { isEnabled = design.hasImage },
        ))

        controls.addView(title("Elementos"))
        controls.addView(text("En cada zona (arriba, centro, abajo) se apilan en este orden."))
        design.elements.forEachIndexed { i, e -> controls.addView(card(i, e)) }
        controls.addView(row(
            button("Añadir elemento") { add() },
            button("Restablecer") {
                AlertDialog.Builder(this).setMessage("¿Volver al diseño inicial de ${screen.label.lowercase()}?")
                    .setPositiveButton("Restablecer") { _, _ ->
                        Store.image(this, screen).delete(); design = Design.default(screen); changed(rebuild = true)
                    }.setNegativeButton("Cancelar", null).show()
            },
        ))
        renderPreview()
    }

    private fun card(i: Int, e: Element): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        val p = (12 * d).toInt(); setPadding(p, p, p, p)
        background = GradientDrawable().apply { setColor(Color.WHITE); setStroke((1.5f * d).toInt(), Color.BLACK); cornerRadius = 8 * d }
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            .apply { topMargin = (10 * d).toInt() }
        addView(row(
            TextView(context).apply {
                text = e.kind.label; textSize = 22f; setTextColor(Color.BLACK); paint.isFakeBoldText = true
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            },
            button("↑") { move(i, -1) }.apply { isEnabled = i > 0 },
            button("↓") { move(i, 1) }.apply { isEnabled = i < design.elements.lastIndex },
            button("✕") { design.elements.removeAt(i); changed(rebuild = true) },
        ))
        when (e.kind) {
            Kind.TEXTO, Kind.QR, Kind.BATERIA -> addView(EditText(context).apply {
                setText(e.text); textSize = 20f
                hint = when (e.kind) { Kind.QR -> "texto, enlace o teléfono del código"; Kind.BATERIA -> "prefijo (por omisión «batería»)"; else -> "texto" }
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
                if (e.kind == Kind.BATERIA) inputType = InputType.TYPE_CLASS_TEXT
                addTextChangedListener(object : TextWatcher {
                    override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                    override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                    override fun afterTextChanged(s: Editable?) { e.text = s.toString(); changed(delay = 900) }
                })
            })
            Kind.ICONO -> addView(button("Icono: ${e.icon.label}") {
                e.icon = Icon.values()[(e.icon.ordinal + 1) % Icon.values().size]; changed(rebuild = true)
            })
            else -> {}
        }
        addView(row(
            button("Zona: ${e.pos.label}") { e.pos = Pos.values()[(e.pos.ordinal + 1) % Pos.values().size]; changed(rebuild = true) },
            button("Tamaño: ${e.size.label}") { e.size = Size.values()[(e.size.ordinal + 1) % Size.values().size]; changed(rebuild = true) },
        ))
        if (e.kind != Kind.ICONO && e.kind != Kind.QR) addView(CheckBox(context).apply {
            text = "Recuadro blanco (legible sobre una imagen)"; textSize = 18f; isChecked = e.box
            setOnCheckedChangeListener { _, v -> e.box = v; changed() }
        })
    }

    private fun move(i: Int, by: Int) {
        val j = i + by
        if (j !in design.elements.indices) return
        val e = design.elements.removeAt(i); design.elements.add(j, e); changed(rebuild = true)
    }

    private fun add() {
        val kinds = Kind.values()
        AlertDialog.Builder(this).setTitle("Añadir elemento")
            .setItems(kinds.map { it.label }.toTypedArray()) { _, w ->
                val k = kinds[w]
                design.elements.add(Element(k, text = if (k == Kind.TEXTO) "Texto" else "",
                    size = if (k == Kind.TEXTO || k == Kind.HORA) Size.M else Size.P))
                changed(rebuild = true)
            }.show()
    }

    private fun pick() {
        startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("image/*"), 1)
    }

    @Deprecated("Activity sin AndroidX")
    override fun onActivityResult(req: Int, res: Int, data: Intent?) {
        super.onActivityResult(req, res, data)
        val uri = data?.data
        if (req != 1 || res != RESULT_OK || uri == null) return
        val s = screen
        io.execute {
            val ok = runCatching {
                contentResolver.openInputStream(uri)!!.use { i -> Store.image(this, s).outputStream().use { i.copyTo(it) } }
            }.isSuccess
            ui.post {
                if (!ok) Toast.makeText(this, "No se pudo leer la imagen", Toast.LENGTH_SHORT).show()
                else if (s == screen) { design.hasImage = true; changed(rebuild = true) }
            }
        }
    }

    /** Guardar, mandar al driver y actualizar la vista previa (con retardo al escribir texto). */
    private fun changed(rebuild: Boolean = false, delay: Long = 0) {
        Store.save(this, screen, design)
        val g = ++gen
        val run = Runnable {
            if (g != gen) return@Runnable
            Updater.refresh(this, force = true)
            if (rebuild) build() else renderPreview()
        }
        if (delay > 0) ui.postDelayed(run, delay) else run.run()
    }

    private fun renderPreview() {
        val s = screen; val dz = design; val g = gen
        io.execute {
            val bmp = runCatching { Render.portrait(this, s, dz, Updater.live(this)) }.getOrNull()
            ui.post { if (g == gen && s == screen && bmp != null) preview.setImageBitmap(bmp) }
        }
    }

    private fun row(vararg v: View) = LinearLayout(this).apply {
        gravity = Gravity.CENTER_VERTICAL
        v.forEach { addView(it) }
    }

    private fun button(t: String, f: () -> Unit) = Button(this).apply {
        text = t; isAllCaps = false; textSize = 18f; setOnClickListener { f() }
    }

    private fun title(s: String) = TextView(this).apply {
        text = s; textSize = 26f; setTextColor(Color.BLACK); paint.isFakeBoldText = true
        setPadding(0, (20 * d).toInt(), 0, (4 * d).toInt())
    }

    private fun text(s: String) = TextView(this).apply { text = s; textSize = 18f; setTextColor(Color.BLACK) }
}
