package com.thyu.passthroughbrowser

import android.app.Activity
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Toast

/** Receives http(s) links, forwards them to the host server and finishes without UI. */
class LinkActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val app = applicationContext
        val url = intent?.takeIf { it.action == Intent.ACTION_VIEW }?.dataString

        if (url == null || !isForwardableUrl(url)) {
            Toast.makeText(
                app,
                app.getString(R.string.toast_prefix, app.getString(R.string.unsupported_link)),
                Toast.LENGTH_SHORT,
            ).show()
            finishQuietly()
            return
        }

        val gw = GatewayResolver.resolve(app)
        val port = Prefs.getPort(app)
        Thread({
            val r = ServerClient(gw.address, port).open(url)
            val target = "${gw.address.hostAddress}:$port"
            ServerResponses.openFailureMessage(app, r, target)?.let { msg ->
                Handler(Looper.getMainLooper()).post {
                    Toast.makeText(app, msg, Toast.LENGTH_LONG).show()
                }
            }
        }, "pb-forward").start()
        finishQuietly()
    }

    private fun finishQuietly() {
        if (Build.VERSION.SDK_INT >= 34) {
            overrideActivityTransition(OVERRIDE_TRANSITION_CLOSE, 0, 0)
        }
        finish()
        if (Build.VERSION.SDK_INT < 34) {
            @Suppress("DEPRECATION")
            overridePendingTransition(0, 0)
        }
    }
}
