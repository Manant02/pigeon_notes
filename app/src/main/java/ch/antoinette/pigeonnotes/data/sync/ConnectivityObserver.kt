package ch.antoinette.pigeonnotes.data.sync

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest

/**
 * Kicks a sync whenever a network with internet access appears. Whether the server is actually
 * reachable (Tailscale up?) is only known once the sync tries, so this is a hint, not a guarantee.
 */
class ConnectivityObserver(context: Context, private val onAvailable: () -> Unit) {
    private val cm = context.getSystemService(ConnectivityManager::class.java)

    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) = onAvailable()
    }

    fun start() {
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        cm.registerNetworkCallback(request, callback)
    }

    val isOnline: Boolean
        get() = cm.getNetworkCapabilities(cm.activeNetwork)
            ?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
}
