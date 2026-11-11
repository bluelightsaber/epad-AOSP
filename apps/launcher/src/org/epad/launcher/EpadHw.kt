package org.epad.launcher

import java.io.File

/**
 * Hardware propio de la E-PAD a través de los drivers del kernel epad:
 * luz frontal fría/cálida (LED PWM) y modos del controlador E-Ink T1000 (eink-t1000).
 * En un Android sin esos drivers (p. ej. el de fábrica) todo es un no-op y [present] es falso.
 */
object EpadHw {
    private const val LEDS = "/sys/class/leds"
    private const val T1000 = "/sys/bus/i2c/devices/1-0006"

    enum class Light(val dir: String) { COLD("frontlight-cold"), WARM("frontlight-warm") }

    /** Modos de refresco del T1000 (mixed25 = 4 cuelga el controlador y no se ofrece nunca). */
    enum class Mode(val code: Int, val label: String) {
        READING(0, "Lectura"), MIXED(1, "Mixto"), BROWSING(2, "Navegación");

        companion object {
            fun of(code: Int) = entries.firstOrNull { it.code == code }
        }
    }

    private const val MODE_GC = 0x11

    val present: Boolean get() = File(T1000).isDirectory || File("$LEDS/${Light.COLD.dir}").isDirectory

    /** Brillo de 0 a 100 %. */
    fun light(l: Light): Int {
        val max = read("$LEDS/${l.dir}/max_brightness")?.toIntOrNull() ?: return 0
        val cur = read("$LEDS/${l.dir}/brightness")?.toIntOrNull() ?: return 0
        return if (max > 0) (cur * 100 + max / 2) / max else 0
    }

    fun setLight(l: Light, percent: Int) {
        val max = read("$LEDS/${l.dir}/max_brightness")?.toIntOrNull() ?: return
        write("$LEDS/${l.dir}/brightness", (percent.coerceIn(0, 100) * max / 100).toString())
    }

    fun mode(): Mode? = read("$T1000/mode")?.toIntOrNull()?.let { Mode.of(it) }

    fun setMode(m: Mode) = write("$T1000/mode", m.code.toString())

    /** Refresco completo (GC) para limpiar los fantasmas de la tinta. */
    fun fullRefresh() = write("$T1000/mode", MODE_GC.toString())

    private fun read(path: String): String? = runCatching { File(path).readText().trim() }.getOrNull()

    private fun write(path: String, value: String): Boolean =
        runCatching { File(path).writeText(value); true }.getOrDefault(false)
}
