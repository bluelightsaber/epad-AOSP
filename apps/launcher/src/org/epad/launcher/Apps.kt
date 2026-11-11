package org.epad.launcher

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.LauncherApps
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.Drawable
import android.os.Build
import android.os.Process
import java.text.Collator
import java.text.Normalizer
import java.util.Locale

data class App(val label: String, val component: ComponentName) {
    /** Letra del índice: sin tildes y con la Ñ aparte, como en el índice A-Z. */
    val letter: Char = indexLetter(label)

    val key: String get() = component.flattenToShortString()

    companion object {
        fun indexLetter(s: String): Char {
            val c = s.trim().firstOrNull()?.uppercaseChar() ?: return '#'
            if (c == 'Ñ') return c
            val base = Normalizer.normalize(c.toString(), Normalizer.Form.NFD).first()
            return if (base in 'A'..'Z') base else '#'
        }
    }
}

/** Grupo del índice: etiqueta («A–C») y sus letras. */
/** Grupo del índice; sin letras = todas las apps. [short] es lo que cabe en el índice. */
data class AppGroup(val label: String, val letters: Set<Char>, val short: String = label)

/** Aplicaciones instaladas y favoritas (en orden, guardadas como componentes). */
class Apps(private val ctx: Context) {
    private val launcherApps = ctx.getSystemService(LauncherApps::class.java)
    private val prefs = ctx.getSharedPreferences("apps", Context.MODE_PRIVATE)
    private val collator = Collator.getInstance(Locale.getDefault())

    var all: List<App> = emptyList()
        private set

    fun reload() {
        all = launcherApps.getActivityList(null, Process.myUserHandle())
            .filter { it.componentName.packageName != ctx.packageName }
            .map { App(it.label.toString(), it.componentName) }
            .sortedWith { a, b -> collator.compare(a.label, b.label) }
    }

    var favorites: List<App>
        get() {
            val byKey = all.associateBy { it.key }
            val keys = prefs.getString("favorites", null)?.split('\n') ?: defaultFavorites()
            return keys.mapNotNull { byKey[it] }
        }
        set(v) = prefs.edit().putString("favorites", v.joinToString("\n") { it.key }).apply()

    fun inGroup(g: AppGroup): List<App> = if (g.letters.isEmpty()) all else all.filter { it.letter in g.letters }

    /** Letras con apps, en orden, juntadas en como mucho [MAX_GROUPS] grupos de tamaño parecido. */
    fun groups(): List<AppGroup> {
        val counts = all.groupingBy { it.letter }.eachCount()
        val letters = counts.keys.sortedBy { ORDER.indexOf(it).let { i -> if (i < 0) ORDER.length else i } }
        if (letters.size <= MAX_GROUPS) return letters.map { AppGroup(it.toString(), setOf(it)) }
        val target = all.size.toDouble() / MAX_GROUPS
        val out = mutableListOf<MutableList<Char>>()
        var acc = 0
        letters.forEach { c ->
            // nuevo grupo si el actual ya va lleno y quedan letras suficientes para los que faltan
            if (out.isEmpty() || (acc >= target && out.size < MAX_GROUPS)) { out.add(mutableListOf()); acc = 0 }
            out.last().add(c)
            acc += counts.getValue(c)
        }
        return out.map { g -> AppGroup(if (g.size == 1) "${g[0]}" else "${g.first()}–${g.last()}", g.toSet()) }
    }

    fun launch(app: App) {
        launcherApps.startMainActivity(app.component, Process.myUserHandle(), null, null)
    }

    fun openDetails(app: App) {
        launcherApps.startAppDetailsActivity(app.component, Process.myUserHandle(), null, null)
    }

    /**
     * Icono de trazo: la capa monocroma del icono adaptativo (iconos temáticos, Android 13+); si la app no la
     * trae, null y se dibuja la inicial. (Pendiente: pack Lawnicons como segundo recurso.)
     */
    fun monochromeIcon(app: App): Drawable? {
        if (Build.VERSION.SDK_INT < 33) return null
        val icon = launcherApps.getActivityList(app.component.packageName, Process.myUserHandle())
            .firstOrNull { it.componentName == app.component }?.getIcon(0)
        return (icon as? AdaptiveIconDrawable)?.monochrome
    }

    private fun defaultFavorites(): List<String> {
        val wanted = listOf("koreader", "note", "fennec", "browser", "chrome", "mail", "calendar", "files", "org.fdroid.fdroid")
        return wanted.mapNotNull { w -> all.firstOrNull { it.component.packageName.contains(w) }?.key }.distinct()
    }

    companion object {
        const val MAX_GROUPS = 8
        private const val ORDER = "#ABCDEFGHIJKLMNÑOPQRSTUVWXYZ"

        fun settingsIntent() = Intent(android.provider.Settings.ACTION_SETTINGS)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
}
