package com.screentime.mobile

import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build

object Net {
    /**
     * Whether the phone is on a network the laptop can be on: Wi-Fi or Ethernet, with or without internet
     * (a home network without internet, or the laptop's hotspot, still counts). Mobile data doesn't.
     */
    fun onLocalNetwork(context: Context): Boolean {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return false
        @Suppress("DEPRECATION") // allNetworks also lists Wi-Fi without internet, which activeNetwork may not be
        return cm.allNetworks.any { network ->
            val caps = cm.getNetworkCapabilities(network) ?: return@any false
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) || caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
        }
    }
}

/** Android 17's local network permission, for when this app targets API 37 (see [LocalNetwork]). */
object LocalNetworkAccess {
    /** Whether direct sync needs the permission and doesn't have it. Always false while the app targets 36. */
    fun missing(context: Context): Boolean =
        LocalNetwork.required(Build.VERSION.SDK_INT, context.applicationInfo.targetSdkVersion) &&
            context.checkSelfPermission(LocalNetwork.PERMISSION) != PackageManager.PERMISSION_GRANTED
}
