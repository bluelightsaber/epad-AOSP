package org.epad.launcher

import android.content.Context
import android.location.LocationManager
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Locale

/** Una hora de previsión. */
data class Hour(val hour: Int, val temp: Double, val rainProb: Int, val rainMm: Double)

data class Forecast(
    val place: String,
    val temp: Double,
    val humidity: Int,
    val code: Int,
    val max: Double,
    val min: Double,
    val rainProb: Int,
    val windKmh: Double,
    val hours: List<Hour>,
    val fetchedAt: Long,
) {
    val description: String get() = describe(code)

    companion object {
        /** Códigos WMO de Open-Meteo, resumidos. */
        fun describe(code: Int) = when (code) {
            0 -> "despejado"
            1, 2 -> "intervalos nubosos"
            3 -> "cubierto"
            45, 48 -> "niebla"
            in 51..57 -> "llovizna"
            in 61..67, in 80..82 -> "lluvia"
            in 71..77, 85, 86 -> "nieve"
            in 95..99 -> "tormenta"
            else -> ""
        }
    }
}

/**
 * Open-Meteo (https://open-meteo.com): sin cuenta ni clave, por latitud y longitud. Ubicación: la última conocida
 * del sistema si la hay; si no, la ciudad elegida en los ajustes del launcher; y si tampoco, la aproximada por
 * la IP (ipapi.co, sin clave; se guarda un día) para que el tiempo salga sin configurar nada.
 */
class Weather(private val ctx: Context) {
    private val prefs = ctx.getSharedPreferences("weather", Context.MODE_PRIVATE)

    var place: Triple<String, Double, Double>?
        get() {
            val name = prefs.getString("place", null) ?: return null
            return Triple(name, prefs.getFloat("lat", 0f).toDouble(), prefs.getFloat("lon", 0f).toDouble())
        }
        set(v) {
            prefs.edit().apply {
                if (v == null) remove("place") else putString("place", v.first)
                    .putFloat("lat", v.second.toFloat()).putFloat("lon", v.third.toFloat())
            }.apply()
        }

    /** La última previsión guardada (se dibuja al instante; la red solo la refresca). */
    fun cached(): Forecast? = prefs.getString("json", null)?.let {
        runCatching { parse(JSONObject(it), prefs.getString("json_place", "") ?: "", prefs.getLong("at", 0)) }
            .getOrNull()
    }

    /** Descarga una previsión nueva. Llamar fuera del hilo principal. */
    fun fetch(): Forecast? {
        val (name, lat, lon) = location() ?: return null
        val url = "https://api.open-meteo.com/v1/forecast?latitude=$lat&longitude=$lon" +
            "&current=temperature_2m,relative_humidity_2m,weather_code,wind_speed_10m" +
            "&hourly=temperature_2m,precipitation_probability,precipitation" +
            "&daily=temperature_2m_max,temperature_2m_min,precipitation_probability_max" +
            "&timezone=auto&forecast_days=2"
        val body = get(url) ?: return null
        val now = System.currentTimeMillis()
        prefs.edit().putString("json", body).putString("json_place", name).putLong("at", now).apply()
        return runCatching { parse(JSONObject(body), name, now) }.getOrNull()
    }

    /** Busca ciudades por nombre (geocodificación de Open-Meteo). */
    fun search(query: String): List<Triple<String, Double, Double>> {
        val q = URLEncoder.encode(query, "UTF-8")
        val lang = Locale.getDefault().language
        val body = get("https://geocoding-api.open-meteo.com/v1/search?name=$q&count=8&language=$lang")
            ?: return emptyList()
        val results = JSONObject(body).optJSONArray("results") ?: return emptyList()
        return (0 until results.length()).map {
            val r = results.getJSONObject(it)
            val label = listOf(r.optString("name"), r.optString("admin1"), r.optString("country"))
                .filter { s -> s.isNotEmpty() }.joinToString(", ")
            Triple(label, r.getDouble("latitude"), r.getDouble("longitude"))
        }
    }

    private fun location(): Triple<String, Double, Double>? {
        val lm = ctx.getSystemService(LocationManager::class.java)
        val last = runCatching {
            lm.getProviders(true).mapNotNull { lm.getLastKnownLocation(it) }.maxByOrNull { it.time }
        }.getOrNull()
        val chosen = place
        if (last != null) return Triple(chosen?.first ?: "", last.latitude, last.longitude)
        return chosen ?: ipLocation()
    }

    /** Ubicación aproximada por la IP, guardada un día. */
    private fun ipLocation(): Triple<String, Double, Double>? {
        val now = System.currentTimeMillis()
        val name = prefs.getString("ip_place", null)
        if (name != null && now - prefs.getLong("ip_at", 0) < 24 * 3600_000L)
            return Triple(name, prefs.getFloat("ip_lat", 0f).toDouble(), prefs.getFloat("ip_lon", 0f).toDouble())
        val j = get("https://ipapi.co/json/")?.let { runCatching { JSONObject(it) }.getOrNull() }
        if (j == null || !j.has("latitude")) {
            return name?.let { Triple(it, prefs.getFloat("ip_lat", 0f).toDouble(), prefs.getFloat("ip_lon", 0f).toDouble()) }
        }
        val r = Triple(j.optString("city"), j.getDouble("latitude"), j.getDouble("longitude"))
        prefs.edit().putString("ip_place", r.first).putFloat("ip_lat", r.second.toFloat())
            .putFloat("ip_lon", r.third.toFloat()).putLong("ip_at", now).apply()
        return r
    }

    private fun parse(j: JSONObject, name: String, at: Long): Forecast {
        val cur = j.getJSONObject("current")
        val daily = j.getJSONObject("daily")
        val hourly = j.getJSONObject("hourly")
        val times = hourly.getJSONArray("time")
        val curTime = cur.getString("time").take(13) // «2026-10-29T10»
        var start = (0 until times.length()).firstOrNull { times.getString(it).startsWith(curTime) } ?: 0
        start += 1
        val hours = (start until minOf(start + 15, times.length()) step 3).map {
            Hour(
                times.getString(it).substring(11, 13).toInt(),
                hourly.getJSONArray("temperature_2m").getDouble(it),
                hourly.getJSONArray("precipitation_probability").optInt(it, 0),
                hourly.getJSONArray("precipitation").optDouble(it, 0.0),
            )
        }
        return Forecast(
            place = name,
            temp = cur.getDouble("temperature_2m"),
            humidity = cur.optInt("relative_humidity_2m", -1),
            code = cur.getInt("weather_code"),
            max = daily.getJSONArray("temperature_2m_max").getDouble(0),
            min = daily.getJSONArray("temperature_2m_min").getDouble(0),
            rainProb = daily.getJSONArray("precipitation_probability_max").optInt(0, 0),
            windKmh = cur.getDouble("wind_speed_10m"),
            hours = hours,
            fetchedAt = at,
        )
    }

    private fun get(url: String): String? = runCatching {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 10_000
        c.readTimeout = 10_000
        c.setRequestProperty("User-Agent", "EpadLauncher/1.0")
        try {
            if (c.responseCode == 200) c.inputStream.bufferedReader().readText() else null
        } finally {
            c.disconnect()
        }
    }.getOrNull()
}
