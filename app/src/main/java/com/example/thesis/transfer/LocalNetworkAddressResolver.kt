package com.example.thesis.transfer

import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.Locale

data class NetworkHostCandidate(
    val interfaceName: String,
    val ipAddress: String,
    val isUp: Boolean,
    val isLoopback: Boolean,
    val isVirtual: Boolean,
    val isSiteLocal: Boolean
)

object LocalNetworkAddressResolver {
    private val WIFI_HOTSPOT_PREFIXES = listOf("wlan", "swlan", "ap", "softap", "wifi")
    private val WIRED_USB_PREFIXES = listOf("eth", "rndis", "usb")

    // Priorizamos interfaces Wi-Fi/Hotspot y filtramos cualquier direccion no privada RFC1918.
    fun selectCandidateIpv4Hosts(
        candidates: List<NetworkHostCandidate>,
        maxHosts: Int = DeviceTransferProtocol.MAX_HOSTS
    ): List<String> {
        if (maxHosts <= 0) return emptyList()
        return candidates
            .asSequence()
            .filter { c ->
                c.isUp &&
                    !c.isLoopback &&
                    !c.isVirtual &&
                    c.isSiteLocal &&
                    DeviceTransferProtocol.isPrivateRfc1918Ipv4(c.ipAddress)
            }
            .sortedBy { interfacePriority(it.interfaceName) }
            .map { it.ipAddress }
            .distinct()
            .take(maxHosts)
            .toList()
    }

    fun resolveLocalIpv4Hosts(): List<String> {
        val candidates = mutableListOf<NetworkHostCandidate>()
        val interfaces = runCatching { NetworkInterface.getNetworkInterfaces() }.getOrNull()
            ?: return emptyList()
        while (interfaces.hasMoreElements()) {
            val iface = interfaces.nextElement()
            val isUp = runCatching { iface.isUp }.getOrDefault(false)
            val isLoopback = runCatching { iface.isLoopback }.getOrDefault(false)
            val isVirtual = runCatching { iface.isVirtual }.getOrDefault(false)
            val name = iface.name.orEmpty()
            val addrs = iface.inetAddresses
            while (addrs.hasMoreElements()) {
                val addr = addrs.nextElement()
                if (addr is Inet4Address) {
                    candidates += NetworkHostCandidate(
                        interfaceName = name,
                        ipAddress = addr.hostAddress.orEmpty(),
                        isUp = isUp,
                        isLoopback = isLoopback || addr.isLoopbackAddress,
                        isVirtual = isVirtual,
                        isSiteLocal = addr.isSiteLocalAddress
                    )
                }
            }
        }
        return selectCandidateIpv4Hosts(candidates)
    }

    private fun interfacePriority(name: String): Int {
        val lower = name.lowercase(Locale.ROOT)
        return when {
            WIFI_HOTSPOT_PREFIXES.any { lower.startsWith(it) } -> 0
            WIRED_USB_PREFIXES.any { lower.startsWith(it) } -> 1
            else -> 2
        }
    }
}
