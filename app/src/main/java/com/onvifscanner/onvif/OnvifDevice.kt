package com.onvifscanner.onvif

data class OnvifDevice(
    val id: String,
    val address: String,
    val port: Int = 80,
    val name: String = "",
    val manufacturer: String = "",
    val model: String = "",
    val firmwareVersion: String = "",
    val macAddress: String = "",
    val profiles: List<MediaProfile> = emptyList(),
    val streamUri: String = "",
    val isAuthenticated: Boolean = false,
    val credentials: Credentials? = null
) {
    val displayName: String
        get() = name.ifEmpty { model.ifEmpty { "$address:$port" } }

    val serviceUrl: String
        get() = "http://$address:$port/onvif/device_service"
}

data class MediaProfile(
    val token: String,
    val name: String,
    val videoEncoderToken: String = "",
    val videoSourceToken: String = ""
)

data class Credentials(
    val username: String,
    val password: String
)

enum class AuthStatus {
    UNKNOWN,
    AUTHENTICATING,
    AUTHENTICATED,
    FAILED,
    NEEDS_CREDENTIALS
}
