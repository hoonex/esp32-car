package io.github.hoonex.esp32car.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import java.net.Inet4Address
import java.net.InetAddress

/** Resolves the Android Wi-Fi Network and IPv4 address that can reach an ESP32. */
object AndroidNetworkRoute {
    data class Route(val network: Network, val localAddress: Inet4Address)

    @Volatile
    private var appContext: Context? = null

    fun initialize(context: Context) {
        appContext = context.applicationContext
    }

    fun findWifiRoute(remoteHost: String): Route? {
        val context = appContext ?: return null
        val manager = context.getSystemService(ConnectivityManager::class.java) ?: return null
        val remote = runCatching {
            InetAddress.getByName(remoteHost.substringBefore(':')) as? Inet4Address
        }.getOrNull() ?: return null

        data class Candidate(
            val route: Route,
            val prefixLength: Int
        )

        val candidates = manager.allNetworks.flatMap { network ->
            val caps = manager.getNetworkCapabilities(network) ?: return@flatMap emptyList()
            if (!caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) return@flatMap emptyList()
            manager.getLinkProperties(network)
                ?.linkAddresses
                .orEmpty()
                .mapNotNull { link ->
                    val address = link.address as? Inet4Address ?: return@mapNotNull null
                    if (address.isLoopbackAddress || address.isAnyLocalAddress) return@mapNotNull null
                    Candidate(Route(network, address), link.prefixLength.coerceIn(0, 32))
                }
        }

        return candidates.firstOrNull { sameSubnet(it.route.localAddress, remote, it.prefixLength) }?.route
            ?: candidates.firstOrNull()?.route
    }

    private fun sameSubnet(a: Inet4Address, b: Inet4Address, prefixLength: Int): Boolean {
        if (prefixLength == 0) return true
        val aa = a.address
        val bb = b.address
        var bits = prefixLength
        for (index in 0..3) {
            if (bits <= 0) return true
            val take = minOf(8, bits)
            val mask = (0xFF shl (8 - take)) and 0xFF
            if ((aa[index].toInt() and mask) != (bb[index].toInt() and mask)) return false
            bits -= take
        }
        return true
    }
}
