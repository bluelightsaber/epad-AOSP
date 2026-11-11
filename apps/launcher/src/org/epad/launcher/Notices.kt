package org.epad.launcher

import android.app.Activity
import android.app.Notification
import android.content.ComponentName
import android.content.Context
import android.graphics.Color
import android.os.Bundle
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.text.format.DateFormat
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

/** Un aviso tal como lo muestra el launcher. */
data class Notice(val key: String, val pkg: String, val app: String, val title: String, val text: String, val time: Long)

/**
 * Escucha las notificaciones (el usuario lo autoriza una vez; en nuestro Android viene concedido de fábrica) y
 * las deja en [Notices] para el inicio (contadores) y la página de avisos.
 */
class NoticeListener : NotificationListenerService() {
    override fun onListenerConnected() {
        instance = this
        refresh()
    }

    override fun onListenerDisconnected() {
        instance = null
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) = refresh()
    override fun onNotificationRemoved(sbn: StatusBarNotification) = refresh()

    private fun refresh() {
        val pm = packageManager
        Notices.update(runCatching { activeNotifications.toList() }.getOrDefault(emptyList())
            .filter { it.isClearable && it.notification.flags and Notification.FLAG_GROUP_SUMMARY == 0 }
            .map { sbn ->
                val ex = sbn.notification.extras
                val app = runCatching {
                    pm.getApplicationLabel(pm.getApplicationInfo(sbn.packageName, 0)).toString()
                }.getOrDefault(sbn.packageName)
                Notice(sbn.key, sbn.packageName, app,
                    ex.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty(),
                    ex.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty(),
                    sbn.postTime)
            }
            .sortedByDescending { it.time })
    }

    companion object {
        var instance: NoticeListener? = null
            private set

        fun dismiss(key: String) = instance?.cancelNotification(key)
        fun dismissAll() = instance?.cancelAllNotifications()

        fun enabled(ctx: Context): Boolean {
            val me = ComponentName(ctx, NoticeListener::class.java).flattenToString()
            return Settings.Secure.getString(ctx.contentResolver, "enabled_notification_listeners")
                ?.split(':')?.contains(me) == true
        }
    }
}

object Notices {
    var list: List<Notice> = emptyList()
        private set
    private val watchers = mutableSetOf<() -> Unit>()

    fun update(l: List<Notice>) {
        list = l
        watchers.toList().forEach { it() }
    }

    fun watch(f: () -> Unit) = watchers.add(f)
    fun unwatch(f: () -> Unit) = watchers.remove(f)

    fun countFor(pkg: String) = list.count { it.pkg == pkg }

    /** «4 avisos · Correo (3), Calendario (1)» */
    fun summary(): String {
        if (list.isEmpty()) return "Sin avisos"
        val byApp = list.groupBy { it.app }.entries.sortedByDescending { it.value.size }
        val n = list.size
        return "$n ${if (n == 1) "aviso" else "avisos"} · " +
            byApp.take(3).joinToString(", ") { "${it.key} (${it.value.size})" }
    }
}

/** Página de avisos, paginada (sin desplazamiento: cada paso de scroll sería un refresco de la tinta). */
class NoticesActivity : Activity() {
    private var page = 0
    private val perPage = 6
    private lateinit var listBox: LinearLayout
    private lateinit var pager: TextView
    private val onChange: () -> Unit = { runOnUiThread { render() } }

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        val d = resources.displayMetrics.density
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(Color.WHITE) }
        val head = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding((40 * d).toInt(), (40 * d).toInt(), (40 * d).toInt(), (20 * d).toInt())
        }
        head.addView(TextView(this).apply { text = "Avisos"; textSize = 40f; setTextColor(Color.BLACK) },
            LinearLayout.LayoutParams(0, -2, 1f))
        head.addView(Button(this).apply { text = "Borrar todo"; setOnClickListener { NoticeListener.dismissAll() } })
        root.addView(head)
        listBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(listBox, LinearLayout.LayoutParams(-1, 0, 1f))
        val foot = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding((40 * d).toInt(), (16 * d).toInt(), (40 * d).toInt(), (28 * d).toInt())
        }
        foot.addView(Button(this).apply { text = "‹ Inicio"; setOnClickListener { finish() } })
        pager = TextView(this).apply { textSize = 20f; gravity = Gravity.CENTER; setTextColor(Color.BLACK) }
        foot.addView(pager, LinearLayout.LayoutParams(0, -2, 1f))
        foot.addView(Button(this).apply { text = "‹"; setOnClickListener { page--; render() } })
        foot.addView(Button(this).apply { text = "›"; setOnClickListener { page++; render() } })
        root.addView(foot)
        setContentView(root)
        fitSystemBars(root)
        if (!NoticeListener.enabled(this)) {
            startActivity(android.content.Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS"))
        }
    }

    override fun onResume() {
        super.onResume()
        Notices.watch(onChange)
        render()
    }

    override fun onPause() {
        Notices.unwatch(onChange)
        super.onPause()
    }

    private fun render() {
        val d = resources.displayMetrics.density
        val all = Notices.list
        val pages = maxOf(1, (all.size + perPage - 1) / perPage)
        page = page.coerceIn(0, pages - 1)
        pager.text = "${page + 1} / $pages"
        listBox.removeAllViews()
        all.drop(page * perPage).take(perPage).forEach { n ->
            val row = LinearLayout(this).apply {
                setPadding((40 * d).toInt(), (20 * d).toInt(), (40 * d).toInt(), (20 * d).toInt())
                gravity = Gravity.CENTER_VERTICAL
            }
            val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            col.addView(TextView(this).apply {
                text = "${n.app} · ${DateFormat.getTimeFormat(context).format(n.time)}"
                textSize = 18f; setTextColor(0xFF333333.toInt())
            })
            if (n.title.isNotEmpty()) col.addView(TextView(this).apply {
                text = n.title; textSize = 24f; setTextColor(Color.BLACK); paint.isFakeBoldText = true
            })
            if (n.text.isNotEmpty()) col.addView(TextView(this).apply {
                text = n.text; textSize = 20f; setTextColor(0xFF222222.toInt()); maxLines = 3
            })
            row.addView(col, LinearLayout.LayoutParams(0, -2, 1f))
            row.addView(Button(this).apply {
                text = "×"; contentDescription = "Descartar aviso"
                setOnClickListener { NoticeListener.dismiss(n.key) }
            })
            listBox.addView(row)
            listBox.addView(View(this).apply { setBackgroundColor(0xFF999999.toInt()) },
                LinearLayout.LayoutParams(-1, maxOf(1, d.toInt())))
        }
    }
}
