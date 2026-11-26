package com.example.thesis.call

import org.webrtc.PeerConnection

data class IceEndpoint(
    val urls: List<String>,
    val username: String? = null,
    val credential: String? = null
)

// Configuracion estrictamente descentralizada de servidores STUN sin estado (RFC 5389) para descubrimiento P2P de NAT,
// sin relés TURN de terceros preconfigurados; solo se usan relés si el usuario o un nodo propio los aporta en tiempo de ejecucion.
object IceServerConfig {
    val defaultStunEndpoints: List<IceEndpoint> = listOf(
        IceEndpoint(urls = listOf("stun:stun.l.google.com:19302")),
        IceEndpoint(urls = listOf("stun:stun1.l.google.com:19302")),
        IceEndpoint(urls = listOf("stun:stun.cloudflare.com:3478")),
        IceEndpoint(urls = listOf("stun:stun.stunprotocol.org:3478"))
    )

    fun buildIceServerList(turnServers: List<IceEndpoint> = emptyList()): List<PeerConnection.IceServer> {
        val all = defaultStunEndpoints + turnServers
        return all.mapNotNull { endpoint ->
            val cleanUrls = endpoint.urls.map { it.trim() }.filter { it.isNotEmpty() }
            if (cleanUrls.isEmpty()) return@mapNotNull null
            val builder = PeerConnection.IceServer.builder(cleanUrls)
            if (!endpoint.username.isNullOrBlank()) {
                builder.setUsername(endpoint.username.trim())
            }
            if (!endpoint.credential.isNullOrBlank()) {
                builder.setPassword(endpoint.credential)
            }
            builder.createIceServer()
        }
    }
}
