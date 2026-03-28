package com.steve.mytvbroadcast.util

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.util.Log
import java.net.Inet4Address
import java.net.NetworkInterface

object NetworkUtils {

    private const val TAG = "NetworkUtils"

    fun getDeviceIpAddress(context: Context): String? {
        Log.d(TAG, "Getting device IP address")

        // Try WiFi first
        try {
            val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            val wifiInfo = wifiManager.connectionInfo
            if (wifiInfo != null) {
                val ipAddress = wifiInfo.ipAddress
                if (ipAddress != 0) {
                    val ip = intToIp(ipAddress)
                    Log.d(TAG, "WiFi IP: $ip")
                    return ip
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "WiFi IP failed", e)
        }

        // Check if we have any network connection
        try {
            val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val network = connectivityManager.activeNetwork
            val capabilities = connectivityManager.getNetworkCapabilities(network)
            if (capabilities != null) {
                Log.d(TAG, "Network capabilities: ${capabilities.transportInfo}")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Connectivity check failed", e)
        }

        // Fallback: iterate network interfaces
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val networkInterface = interfaces.nextElement()
                val name = networkInterface.name
                Log.d(TAG, "Network interface: $name")
                val addresses = networkInterface.inetAddresses
                while (addresses.hasMoreElements()) {
                    val address = addresses.nextElement()
                    if (!address.isLoopbackAddress && !address.isLinkLocalAddress) {
                        val hostAddress = address.hostAddress
                        // Prefer IPv4 addresses
                        if (address is Inet4Address && hostAddress != null) {
                            Log.d(TAG, "Found IPv4: $hostAddress on $name")
                            return hostAddress
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Network interface iteration failed", e)
        }

        Log.e(TAG, "Could not get IP address")
        return null
    }

    private fun intToIp(ip: Int): String {
        return "${ip and 0xFF}.${ip shr 8 and 0xFF}.${ip shr 16 and 0xFF}.${ip shr 24 and 0xFF}"
    }
}
