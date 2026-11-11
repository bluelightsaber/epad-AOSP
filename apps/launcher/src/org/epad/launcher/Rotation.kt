package org.epad.launcher

import android.content.Context
import android.provider.Settings

/** Giro manual (no hay acelerómetro en el kernel): 0 vertical, 1-3 en pasos de 90°. */
object Rotation {
    val labels = listOf("Vertical", "Apaisado", "Invertido", "Apaisado 2")

    fun get(ctx: Context): Int =
        Settings.System.getInt(ctx.contentResolver, Settings.System.USER_ROTATION, 0).coerceIn(0, 3)

    fun set(ctx: Context, r: Int): Boolean = runCatching {
        Settings.System.putInt(ctx.contentResolver, Settings.System.ACCELEROMETER_ROTATION, 0)
        Settings.System.putInt(ctx.contentResolver, Settings.System.USER_ROTATION, r)
    }.isSuccess

    /** Giro siguiente en orden inverso: vertical → apaisado 2 → invertido → apaisado. */
    fun nextValue(ctx: Context) = (get(ctx) + 3) % 4

    fun next(ctx: Context) = set(ctx, nextValue(ctx))
}
