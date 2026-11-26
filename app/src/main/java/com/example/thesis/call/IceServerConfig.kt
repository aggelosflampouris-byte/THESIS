package com.example.thesis.call

import org.webrtc.PeerConnection

data class IceEndpoint(
    val urls: List<String>,
    val username: String? = null,
    val credential: String? = null
)

// Configuracion estrictamente descentralizada de servidores STUN sin estado (RFC 5389) para descubrimiento P2P de NAT.
object IceServerConfig {
    val defaultStunEndpoints: List<IceEndpoint> = listOf(
        IceEndpoint(urls = listOf("stun:stun.l.google.com:19302")),
        IceEndpoint(urls = listOf("stun:stun1.l.google.com:19302")),
        IceEndpoint(urls = listOf("stun:stun.cloudflare.com:3478")),
        IceEndpoint(urls = listOf("stun:stun.stunprotocol.org:3478"))
    )

    fun buildIceServerList(): List<PeerConnection.IceServer> {
        return defaultStunEndpoints.mapNotNull { endpoint ->
            val cleanUrls = endpoint.urls.map { it.trim() }.filter { it.isNotEmpty() }
            if (cleanUrls.isEmpty()) return@mapNotNull null
            PeerConnection.IceServer.builder(cleanUrls).createIceServer()
        }
    }
}
