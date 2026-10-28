package com.thyu.passthroughbrowser

import android.content.Context

object Prefs {
    private const val FILE = "settings"
    private const val KEY_PORT = "port"

    private fun prefs(ctx: Context) =
        ctx.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun getPort(ctx: Context): Int {
        val p = prefs(ctx).getInt(KEY_PORT, DEFAULT_PORT)
        return if (p in 1..65535) p else DEFAULT_PORT
    }

    fun setPort(ctx: Context, port: Int) {
        require(port in 1..65535)
        prefs(ctx).edit().putInt(KEY_PORT, port).apply()
    }
}
