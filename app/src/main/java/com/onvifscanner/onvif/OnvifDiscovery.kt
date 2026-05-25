package com.onvifscanner.onvif

import android.content.Context
import android.net.wifi.WifiManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.net.DatagramPacket
import java.net.InetAddress
import java.net.MulticastSocket
import java.net.URI
import java.util.UUID
import java.util.regex.Pattern

class OnvifDiscovery(private val context: Context) {

    companion object {
        private const val WS_DISCOVERY_ADDRESS = "239.255.255.250"
        private const val WS_DISCOVERY_PORT = 3702
        private const val DISCOVERY_TIMEOUT_MS = 5000L
        private const val BUFFER_SIZE = 8192
    }

    private val probeMessage: String
        get() {
            val uuid = UUID.randomUUID().toString()
            return """<?xml version="1.0" encoding="UTF-8"?>
<soap:Envelope xmlns:soap="http://www.w3.org/2003/05/soap-envelope"
               xmlns:wsa="http://schemas.xmlsoap.org/ws/2004/08/addressing"
               xmlns:wsd="http://schemas.xmlsoap.org/ws/2005/04/discovery"
               xmlns:wsdp="http://schemas.xmlsoap.org/ws/2006/02/devprof">
    <soap:Header>
        <wsa:To>urn:schemas-xmlsoap-org:ws:2005:04:discovery</wsa:To>
        <wsa:Action>http://schemas.xmlsoap.org/ws/2005/04/discovery/Probe</wsa:Action>
        <wsa:MessageID>urn:uuid:$uuid</wsa:MessageID>
    </soap:Header>
    <soap:Body>
        <wsd:Probe>
            <wsd:Types>wsdp:Device</wsd:Types>
        </wsd:Probe>
    </soap:Body>
</soap:Envelope>"""
        }

    suspend fun discover(): List<OnvifDevice> = withContext(Dispatchers.IO) {
        val devices = mutableMapOf<String, OnvifDevice>()
        var multicastLock: WifiManager.MulticastLock? = null
        var socket: MulticastSocket? = null

        try {
            // Acquire multicast lock
            val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            multicastLock = wifiManager.createMulticastLock("onvif_discovery")
            multicastLock.setReferenceCounted(true)
            multicastLock.acquire()

            // Create multicast socket
            socket = MulticastSocket(WS_DISCOVERY_PORT)
            socket.reuseAddress = true
            socket.soTimeout = 1000

            val multicastAddress = InetAddress.getByName(WS_DISCOVERY_ADDRESS)
            socket.joinGroup(multicastAddress)

            // Send probe
            val probeBytes = probeMessage.toByteArray(Charsets.UTF_8)
            val sendPacket = DatagramPacket(
                probeBytes,
                probeBytes.size,
                multicastAddress,
                WS_DISCOVERY_PORT
            )
            socket.send(sendPacket)

            // Receive responses
            val buffer = ByteArray(BUFFER_SIZE)
            val startTime = System.currentTimeMillis()

            while (System.currentTimeMillis() - startTime < DISCOVERY_TIMEOUT_MS) {
                try {
                    val receivePacket = DatagramPacket(buffer, buffer.size)
                    socket.receive(receivePacket)

                    val response = String(
                        receivePacket.data,
                        0,
                        receivePacket.length,
                        Charsets.UTF_8
                    )

                    parseProbeMatch(response)?.let { device ->
                        devices[device.id] = device
                    }
                } catch (e: java.net.SocketTimeoutException) {
                    // Expected, continue listening
                }
            }

            socket.leaveGroup(multicastAddress)

        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            socket?.close()
            multicastLock?.release()
        }

        devices.values.toList()
    }

    private fun parseProbeMatch(xml: String): OnvifDevice? {
        try {
            // Extract XAddrs (service addresses)
            val xAddrsPattern = Pattern.compile("<[^:]*:?XAddrs>([^<]+)</[^:]*:?XAddrs>")
            val xAddrsMatcher = xAddrsPattern.matcher(xml)

            if (!xAddrsMatcher.find()) return null

            val xAddrs = xAddrsMatcher.group(1) ?: return null
            val addresses = xAddrs.split("\\s+".toRegex())

            // Find HTTP address (prefer it over HTTPS for local cameras)
            val serviceAddress = addresses.firstOrNull { it.startsWith("http://") }
                ?: addresses.firstOrNull()
                ?: return null

            val uri = URI(serviceAddress)
            val address = uri.host ?: return null
            val port = if (uri.port > 0) uri.port else 80

            // Extract EndpointReference for unique ID
            val eprPattern = Pattern.compile("<[^:]*:?Address>([^<]+)</[^:]*:?Address>")
            val eprMatcher = eprPattern.matcher(xml)
            val id = if (eprMatcher.find()) {
                eprMatcher.group(1)?.substringAfterLast(":") ?: address
            } else {
                address
            }

            return OnvifDevice(
                id = id,
                address = address,
                port = port
            )
        } catch (e: Exception) {
            e.printStackTrace()
            return null
        }
    }
}
