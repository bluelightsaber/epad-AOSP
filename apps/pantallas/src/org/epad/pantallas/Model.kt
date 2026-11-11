package org.epad.pantallas

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Las dos pantallas que se quedan en la tinta y el atributo del driver eink-t1000 que recibe cada una. */
enum class Screen(val key: String, val label: String, val attr: String) {
    REPOSO("reposo", "Reposo", "sleep_image"),
    APAGADO("apagado", "Apagado", "poweroff_image"),
}

/** Tipos de elemento; los dinámicos se regeneran cuando cambia su dato. */
enum class Kind(val label: String, val dynamic: Boolean) {
    TEXTO("Texto", false),
    HORA("Hora", true),
    FECHA("Fecha", true),
    BATERIA("Batería", true),
    TIEMPO("Tiempo", true),
    QR("Código QR", false),
    ICONO("Icono", false),
}

enum class Pos(val label: String) { ARRIBA("arriba"), CENTRO("centro"), ABAJO("abajo") }

enum class Size(val label: String, val text: Float, val icon: Int, val qr: Int) {
    P("pequeño", 40f, 160, 260),
    M("mediano", 64f, 280, 400),
    G("grande", 110f, 420, 560),
}

enum class Icon(val label: String) { ENCENDIDO("encendido"), LUNA("luna y estrellas"), LIBRO("libro") }

class Element(
    var kind: Kind,
    var text: String = "",
    var pos: Pos = Pos.CENTRO,
    var size: Size = Size.M,
    var icon: Icon = Icon.ENCENDIDO,
    var box: Boolean = false,
) {
    fun toJson(): JSONObject = JSONObject().put("kind", kind.name).put("text", text).put("pos", pos.name)
        .put("size", size.name).put("icon", icon.name).put("box", box)

    companion object {
        fun fromJson(j: JSONObject) = Element(
            enumOr(j.optString("kind"), Kind.TEXTO), j.optString("text"), enumOr(j.optString("pos"), Pos.CENTRO),
            enumOr(j.optString("size"), Size.M), enumOr(j.optString("icon"), Icon.ENCENDIDO), j.optBoolean("box"),
        )
    }
}

inline fun <reified T : Enum<T>> enumOr(name: String, def: T): T =
    runCatching { enumValueOf<T>(name) }.getOrDefault(def)

class Design(
    var enabled: Boolean = true,	// solo reposo: con false se queda lo último que había en pantalla
    var fill: Boolean = true,	// imagen de fondo: llenar la pantalla (recortando) o encajarla entera
    var hasImage: Boolean = false,
    val elements: MutableList<Element> = mutableListOf(),
) {
    fun toJson(): JSONObject = JSONObject().put("enabled", enabled).put("fill", fill).put("hasImage", hasImage)
        .put("elements", JSONArray().apply { elements.forEach { put(it.toJson()) } })

    companion object {
        fun fromJson(j: JSONObject) = Design(
            j.optBoolean("enabled", true), j.optBoolean("fill", true), j.optBoolean("hasImage"),
            j.optJSONArray("elements")?.let { a -> (0 until a.length()).map { Element.fromJson(a.getJSONObject(it)) } }
                ?.toMutableList() ?: mutableListOf(),
        )

        /** Diseños iniciales: los que dibujaba el launcher. */
        fun default(s: Screen) = when (s) {
            Screen.REPOSO -> Design(elements = mutableListOf(
                Element(Kind.ICONO, icon = Icon.LUNA, size = Size.G),
                Element(Kind.TEXTO, "En reposo", size = Size.G),
                Element(Kind.FECHA, size = Size.M),
                Element(Kind.BATERIA, size = Size.P),
                Element(Kind.TIEMPO, size = Size.P),
                Element(Kind.TEXTO, "pulsa el botón de encendido para volver", Pos.ABAJO, Size.P),
            ))
            Screen.APAGADO -> Design(elements = mutableListOf(
                Element(Kind.ICONO, icon = Icon.ENCENDIDO, size = Size.G),
                Element(Kind.TEXTO, "Apagada", size = Size.G),
                Element(Kind.TEXTO, "mantén pulsado el botón de encendido para encenderla", Pos.ABAJO, Size.P),
            ))
        }
    }
}

object Store {
    private fun prefs(ctx: Context) = ctx.getSharedPreferences("pantallas", Context.MODE_PRIVATE)

    fun load(ctx: Context, s: Screen): Design = prefs(ctx).getString(s.key, null)
        ?.let { runCatching { Design.fromJson(JSONObject(it)) }.getOrNull() } ?: Design.default(s)

    fun save(ctx: Context, s: Screen, d: Design) {
        prefs(ctx).edit().putString(s.key, d.toJson().toString()).apply()
    }

    /** Imagen de fondo tal cual la eligió el usuario (se reduce y se pasa a grises al dibujar). */
    fun image(ctx: Context, s: Screen) = File(ctx.filesDir, "fondo-${s.key}")

    var weatherText: String? = null
    fun weather(ctx: Context): String? = weatherText ?: prefs(ctx).getString("tiempo", null).also { weatherText = it }
    fun setWeather(ctx: Context, t: String) {
        weatherText = t
        prefs(ctx).edit().putString("tiempo", t).apply()
    }
}
