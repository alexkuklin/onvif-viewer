package com.onvifscanner.auth

data class PasswordEntry(
    val macPrefix: String,  // e.g., "D4:6D:C8" or "*" for wildcard
    val username: String,
    val password: String
) {
    val isWildcard: Boolean
        get() = macPrefix == "*"

    fun matchesMac(macAddress: String): Boolean {
        if (isWildcard) return true
        val normalizedMac = macAddress.uppercase().replace("-", ":")
        val normalizedPrefix = macPrefix.uppercase().replace("-", ":")
        return normalizedMac.startsWith(normalizedPrefix)
    }

    companion object {
        fun parse(line: String): PasswordEntry? {
            val trimmed = line.trim()
            if (trimmed.isEmpty() || trimmed.startsWith("#")) return null

            val parts = trimmed.split(",").map { it.trim() }
            if (parts.size < 3) return null

            return PasswordEntry(
                macPrefix = parts[0],
                username = parts[1],
                password = parts[2]
            )
        }
    }
}
