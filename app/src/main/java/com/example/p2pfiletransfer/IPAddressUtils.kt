package com.example.p2pfiletransfer

import android.util.Log
import java.net.Inet4Address
import java.net.NetworkInterface

object IPAddressUtils {

    private const val TAG = "IPAddressUtils"

    /**
     * Retrieves the first non-loopback IPv4 address.
     */
    fun getLocalIpAddress(): String? {
        return try {
            NetworkInterface.getNetworkInterfaces().asSequence()
                .flatMap { it.inetAddresses.asSequence() }
                .filter { !it.isLoopbackAddress && it is Inet4Address }
                .map { it.hostAddress }
                .firstOrNull()
        } catch (ex: Exception) {
            Log.e(TAG, "Error getting local IP address", ex)
            null
        }
    }

    /**
     * Retrieves all non-loopback IPv4 addresses.
     */
    fun getAllLocalIpAddresses(): List<String> {
        return try {
            NetworkInterface.getNetworkInterfaces().asSequence()
                .flatMap { it.inetAddresses.asSequence() }
                .filter { !it.isLoopbackAddress && it is Inet4Address }
                .map { it.hostAddress }
                .toList()
        } catch (ex: Exception) {
            Log.e(TAG, "Error getting local IP addresses", ex)
            emptyList()
        }
    }
}
