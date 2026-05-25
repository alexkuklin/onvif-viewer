package com.onvifscanner.auth

import android.content.Context
import android.os.Environment
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.onvifscanner.onvif.Credentials
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.io.File

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "camera_credentials")

class PasswordManager(private val context: Context) {

    private var passwordEntries: List<PasswordEntry> = emptyList()

    suspend fun initialize() {
        passwordEntries = loadPasswordFile()
    }

    private fun loadPasswordFile(): List<PasswordEntry> {
        val entries = mutableListOf<PasswordEntry>()

        // Try external file first
        val externalFile = File(
            Environment.getExternalStorageDirectory(),
            "onvif_passwords.txt"
        )
        if (externalFile.exists() && externalFile.canRead()) {
            externalFile.readLines().mapNotNull { PasswordEntry.parse(it) }.let {
                entries.addAll(it)
            }
        }

        // Always load bundled defaults (as fallback)
        try {
            context.assets.open("default_passwords.txt").bufferedReader().useLines { lines ->
                lines.mapNotNull { PasswordEntry.parse(it) }.toList().let {
                    entries.addAll(it)
                }
            }
        } catch (e: Exception) {
            // Asset file not found, add hardcoded defaults
            entries.addAll(getHardcodedDefaults())
        }

        return entries
    }

    private fun getHardcodedDefaults(): List<PasswordEntry> = listOf(
        PasswordEntry("*", "admin", "admin"),
        PasswordEntry("*", "admin", "12345"),
        PasswordEntry("*", "admin", ""),
        PasswordEntry("*", "root", "root"),
        PasswordEntry("*", "service", "service")
    )

    fun getCredentialsToTry(macAddress: String): List<Credentials> {
        val credentials = mutableListOf<Credentials>()

        // First, add MAC-specific entries
        passwordEntries
            .filter { !it.isWildcard && it.matchesMac(macAddress) }
            .forEach { credentials.add(Credentials(it.username, it.password)) }

        // Then add wildcard entries
        passwordEntries
            .filter { it.isWildcard }
            .forEach { credentials.add(Credentials(it.username, it.password)) }

        return credentials.distinctBy { "${it.username}:${it.password}" }
    }

    suspend fun saveCredentials(deviceId: String, credentials: Credentials) {
        val key = stringPreferencesKey("cred_$deviceId")
        context.dataStore.edit { prefs ->
            prefs[key] = "${credentials.username}:${credentials.password}"
        }
    }

    suspend fun getSavedCredentials(deviceId: String): Credentials? {
        val key = stringPreferencesKey("cred_$deviceId")
        val stored = context.dataStore.data.map { prefs ->
            prefs[key]
        }.first()

        return stored?.let {
            val parts = it.split(":", limit = 2)
            if (parts.size == 2) {
                Credentials(parts[0], parts[1])
            } else null
        }
    }

    suspend fun clearSavedCredentials(deviceId: String) {
        val key = stringPreferencesKey("cred_$deviceId")
        context.dataStore.edit { prefs ->
            prefs.remove(key)
        }
    }
}
