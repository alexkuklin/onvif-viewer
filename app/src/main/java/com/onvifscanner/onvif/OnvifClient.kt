package com.onvifscanner.onvif

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Base64
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.regex.Pattern

class OnvifClient {

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.SECONDS)
        .build()

    private val soapMediaType = "application/soap+xml; charset=utf-8".toMediaType()

    suspend fun getDeviceInformation(device: OnvifDevice): OnvifDevice? = withContext(Dispatchers.IO) {
        val soapBody = """
            <GetDeviceInformation xmlns="http://www.onvif.org/ver10/device/wsdl"/>
        """.trimIndent()

        val response = sendSoapRequest(device, soapBody) ?: return@withContext null

        val manufacturer = extractTag(response, "Manufacturer") ?: ""
        val model = extractTag(response, "Model") ?: ""
        val firmwareVersion = extractTag(response, "FirmwareVersion") ?: ""

        device.copy(
            manufacturer = manufacturer,
            model = model,
            firmwareVersion = firmwareVersion
        )
    }

    suspend fun getNetworkInterfaces(device: OnvifDevice): String? = withContext(Dispatchers.IO) {
        val soapBody = """
            <GetNetworkInterfaces xmlns="http://www.onvif.org/ver10/device/wsdl"/>
        """.trimIndent()

        val response = sendSoapRequest(device, soapBody) ?: return@withContext null

        // Extract MAC address - look for HwAddress tag
        extractTag(response, "HwAddress")
    }

    suspend fun getProfiles(device: OnvifDevice): List<MediaProfile> = withContext(Dispatchers.IO) {
        val soapBody = """
            <GetProfiles xmlns="http://www.onvif.org/ver10/media/wsdl"/>
        """.trimIndent()

        val mediaServiceUrl = device.serviceUrl.replace("device_service", "media_service")
        val response = sendSoapRequest(device.copy(), soapBody, mediaServiceUrl) ?: return@withContext emptyList()

        parseProfiles(response)
    }

    suspend fun getStreamUri(device: OnvifDevice, profileToken: String): String? = withContext(Dispatchers.IO) {
        val soapBody = """
            <GetStreamUri xmlns="http://www.onvif.org/ver10/media/wsdl">
                <StreamSetup>
                    <Stream xmlns="http://www.onvif.org/ver10/schema">RTP-Unicast</Stream>
                    <Transport xmlns="http://www.onvif.org/ver10/schema">
                        <Protocol>RTSP</Protocol>
                    </Transport>
                </StreamSetup>
                <ProfileToken>$profileToken</ProfileToken>
            </GetStreamUri>
        """.trimIndent()

        val mediaServiceUrl = device.serviceUrl.replace("device_service", "media_service")
        val response = sendSoapRequest(device, soapBody, mediaServiceUrl) ?: return@withContext null

        extractTag(response, "Uri")
    }

    suspend fun authenticate(device: OnvifDevice, credentials: Credentials): Boolean = withContext(Dispatchers.IO) {
        val testDevice = device.copy(credentials = credentials)
        val result = getDeviceInformation(testDevice)
        result != null
    }

    private fun sendSoapRequest(device: OnvifDevice, body: String, serviceUrl: String? = null): String? {
        val url = serviceUrl ?: device.serviceUrl
        val envelope = buildSoapEnvelope(body, device.credentials)

        val request = Request.Builder()
            .url(url)
            .post(envelope.toRequestBody(soapMediaType))
            .header("Content-Type", "application/soap+xml; charset=utf-8")
            .build()

        return try {
            httpClient.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    response.body?.string()
                } else {
                    null
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    private fun buildSoapEnvelope(body: String, credentials: Credentials?): String {
        val securityHeader = credentials?.let { buildSecurityHeader(it) } ?: ""

        return """<?xml version="1.0" encoding="UTF-8"?>
<soap:Envelope xmlns:soap="http://www.w3.org/2003/05/soap-envelope"
               xmlns:wsse="http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-wssecurity-secext-1.0.xsd"
               xmlns:wsu="http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-wssecurity-utility-1.0.xsd">
    <soap:Header>
        $securityHeader
    </soap:Header>
    <soap:Body>
        $body
    </soap:Body>
</soap:Envelope>"""
    }

    private fun buildSecurityHeader(credentials: Credentials): String {
        val nonce = ByteArray(20).also { java.security.SecureRandom().nextBytes(it) }
        val nonceBase64 = Base64.getEncoder().encodeToString(nonce)

        val dateFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
        dateFormat.timeZone = TimeZone.getTimeZone("UTC")
        val created = dateFormat.format(Date())

        // Calculate password digest: Base64(SHA1(nonce + created + password))
        val md = MessageDigest.getInstance("SHA-1")
        md.update(nonce)
        md.update(created.toByteArray(Charsets.UTF_8))
        md.update(credentials.password.toByteArray(Charsets.UTF_8))
        val digest = Base64.getEncoder().encodeToString(md.digest())

        return """
        <wsse:Security soap:mustUnderstand="true">
            <wsse:UsernameToken>
                <wsse:Username>${credentials.username}</wsse:Username>
                <wsse:Password Type="http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-username-token-profile-1.0#PasswordDigest">$digest</wsse:Password>
                <wsse:Nonce EncodingType="http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-soap-message-security-1.0#Base64Binary">$nonceBase64</wsse:Nonce>
                <wsu:Created>$created</wsu:Created>
            </wsse:UsernameToken>
        </wsse:Security>
        """.trimIndent()
    }

    private fun extractTag(xml: String, tagName: String): String? {
        val pattern = Pattern.compile("<[^:]*:?$tagName[^>]*>([^<]*)</[^:]*:?$tagName>")
        val matcher = pattern.matcher(xml)
        return if (matcher.find()) matcher.group(1) else null
    }

    private fun parseProfiles(xml: String): List<MediaProfile> {
        val profiles = mutableListOf<MediaProfile>()

        // Simple regex parsing for profiles
        val profilePattern = Pattern.compile(
            "<[^:]*:?Profiles[^>]*token=\"([^\"]+)\"[^>]*>.*?<[^:]*:?Name>([^<]*)</[^:]*:?Name>",
            Pattern.DOTALL
        )
        val matcher = profilePattern.matcher(xml)

        while (matcher.find()) {
            val token = matcher.group(1) ?: continue
            val name = matcher.group(2) ?: token

            profiles.add(MediaProfile(token = token, name = name))
        }

        // Fallback: try simpler pattern if nothing found
        if (profiles.isEmpty()) {
            val simplePattern = Pattern.compile("token=\"([^\"]+)\"")
            val simpleMatcher = simplePattern.matcher(xml)
            var index = 0
            while (simpleMatcher.find() && index < 5) {
                val token = simpleMatcher.group(1) ?: continue
                if (token.contains("Profile") || token.contains("profile") || token.contains("MainStream") || token.contains("SubStream")) {
                    profiles.add(MediaProfile(token = token, name = "Profile ${index + 1}"))
                    index++
                }
            }
        }

        return profiles
    }
}
