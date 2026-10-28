package com.thyu.passthroughbrowser

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import java.net.Inet4Address
import java.net.InetAddress

/** [discovered] = false means the hard-coded fallback address is used. */
data class Gateway(val address: Inet4Address, val discovered: Boolean)

object GatewayResolver {
    @Suppress("DEPRECATION")
    fun resolve(ctx: Context): Gateway {
        try {
            val cm = ctx.getSystemService(ConnectivityManager::class.java)
            val networks = LinkedHashSet<Network>()
            cm.activeNetwork?.let { networks.add(it) }
            networks.addAll(cm.allNetworks)
            for (n in networks) {
                val lp = cm.getLinkProperties(n) ?: continue
                val candidates = lp.routes.filter { it.isDefaultRoute }.map { it.gateway }
                selectGateway(candidates)?.let { return Gateway(it, discovered = true) }
            }
        } catch (e: SecurityException) {
            // fall through to fallback
        } catch (e: RuntimeException) {
            // fall through to fallback
        }
        return Gateway(InetAddress.getByName(FALLBACK_HOST) as Inet4Address, discovered = false)
    }
}
