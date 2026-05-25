package com.onvifscanner.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.onvifscanner.auth.PasswordManager
import com.onvifscanner.onvif.AuthStatus
import com.onvifscanner.onvif.Credentials
import com.onvifscanner.onvif.OnvifClient
import com.onvifscanner.onvif.OnvifDevice
import com.onvifscanner.onvif.OnvifDiscovery
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class CameraUiState(
    val isScanning: Boolean = false,
    val cameras: List<CameraState> = emptyList(),
    val selectedCamera: CameraState? = null,
    val showCredentialsDialog: Boolean = false,
    val credentialsDialogCameraId: String? = null,
    val showAddCameraDialog: Boolean = false,
    val error: String? = null
)

data class CameraState(
    val device: OnvifDevice,
    val index: Int,
    val authStatus: AuthStatus = AuthStatus.UNKNOWN,
    val streamUri: String? = null
)

class CameraViewModel(application: Application) : AndroidViewModel(application) {

    private val discovery = OnvifDiscovery(application)
    private val client = OnvifClient()
    private val passwordManager = PasswordManager(application)

    private val _uiState = MutableStateFlow(CameraUiState())
    val uiState: StateFlow<CameraUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            passwordManager.initialize()
        }
    }

    fun scanForCameras() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isScanning = true, error = null)

            try {
                val devices = discovery.discover()
                val cameraStates = devices.mapIndexed { index, device ->
                    CameraState(device = device, index = index + 1)
                }

                _uiState.value = _uiState.value.copy(
                    isScanning = false,
                    cameras = cameraStates
                )

                // Auto-authenticate discovered cameras
                cameraStates.forEach { camera ->
                    authenticateCamera(camera.device.id)
                }

                // Auto-select first camera if available
                if (cameraStates.isNotEmpty() && _uiState.value.selectedCamera == null) {
                    selectCamera(cameraStates.first().device.id)
                }
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    isScanning = false,
                    error = "Discovery failed: ${e.message}"
                )
            }
        }
    }

    fun selectCamera(deviceId: String) {
        val camera = _uiState.value.cameras.find { it.device.id == deviceId }
        _uiState.value = _uiState.value.copy(selectedCamera = camera)

        // If camera is authenticated, get stream URI
        if (camera?.authStatus == AuthStatus.AUTHENTICATED && camera.streamUri == null) {
            viewModelScope.launch {
                getStreamUri(deviceId)
            }
        }
    }

    private fun authenticateCamera(deviceId: String) {
        viewModelScope.launch {
            updateCameraState(deviceId) { it.copy(authStatus = AuthStatus.AUTHENTICATING) }

            val camera = _uiState.value.cameras.find { it.device.id == deviceId } ?: return@launch
            val device = camera.device

            // First try without auth - some cameras don't require it
            // Also try different ports in case WS-Discovery reported wrong one
            val noAuthResult = client.findWorkingPort(device)
            if (noAuthResult != null) {
                // No auth needed! Update with correct port
                updateCameraState(deviceId) { cam ->
                    cam.copy(
                        device = noAuthResult.copy(isAuthenticated = true),
                        authStatus = AuthStatus.AUTHENTICATED
                    )
                }
                // Get profiles using correct port
                val profiles = client.getProfiles(noAuthResult)
                if (profiles.isNotEmpty()) {
                    updateCameraState(deviceId) { it.copy(device = it.device.copy(profiles = profiles)) }
                }
                return@launch
            }

            // Check for saved credentials
            val savedCredentials = passwordManager.getSavedCredentials(deviceId)
            if (savedCredentials != null) {
                if (client.authenticate(device, savedCredentials)) {
                    onAuthSuccess(deviceId, savedCredentials)
                    return@launch
                }
            }

            // Get MAC address for vendor matching
            val macAddress = client.getNetworkInterfaces(device.copy(credentials = Credentials("admin", "admin"))) ?: ""

            // Try credentials from password file
            val credentialsToTry = passwordManager.getCredentialsToTry(macAddress)
            for (credentials in credentialsToTry) {
                if (client.authenticate(device, credentials)) {
                    onAuthSuccess(deviceId, credentials)
                    return@launch
                }
            }

            // All failed - show error with details
            val errorDetail = client.lastError ?: "Unknown error"
            updateCameraState(deviceId) { it.copy(authStatus = AuthStatus.FAILED) }
            _uiState.value = _uiState.value.copy(
                error = "Auth failed for ${device.address}: $errorDetail"
            )
        }
    }

    private suspend fun onAuthSuccess(deviceId: String, credentials: Credentials) {
        updateCameraState(deviceId) { camera ->
            camera.copy(
                device = camera.device.copy(
                    credentials = credentials,
                    isAuthenticated = true
                ),
                authStatus = AuthStatus.AUTHENTICATED
            )
        }

        // Save credentials for future use
        passwordManager.saveCredentials(deviceId, credentials)

        // Get device information
        val camera = _uiState.value.cameras.find { it.device.id == deviceId } ?: return
        val deviceInfo = client.getDeviceInformation(camera.device.copy(credentials = credentials))
        if (deviceInfo != null) {
            updateCameraState(deviceId) { it.copy(device = deviceInfo.copy(credentials = credentials, isAuthenticated = true)) }
        }

        // Get profiles
        val profiles = client.getProfiles(camera.device.copy(credentials = credentials))
        if (profiles.isNotEmpty()) {
            updateCameraState(deviceId) { it.copy(device = it.device.copy(profiles = profiles)) }
        }
    }

    private suspend fun getStreamUri(deviceId: String) {
        val camera = _uiState.value.cameras.find { it.device.id == deviceId } ?: return
        val device = camera.device

        if (device.profiles.isEmpty()) {
            val profiles = client.getProfiles(device)
            if (profiles.isNotEmpty()) {
                updateCameraState(deviceId) { it.copy(device = it.device.copy(profiles = profiles)) }
            }
        }

        val updatedCamera = _uiState.value.cameras.find { it.device.id == deviceId } ?: return
        val profileToken = updatedCamera.device.profiles.firstOrNull()?.token ?: return

        val streamUri = client.getStreamUri(updatedCamera.device, profileToken)
        if (streamUri != null) {
            // Embed credentials in RTSP URL if needed
            val finalUri = embedCredentialsInUri(streamUri, updatedCamera.device.credentials)
            updateCameraState(deviceId) { it.copy(streamUri = finalUri) }

            // Update selected camera if it's this one
            if (_uiState.value.selectedCamera?.device?.id == deviceId) {
                _uiState.value = _uiState.value.copy(
                    selectedCamera = _uiState.value.cameras.find { it.device.id == deviceId }
                )
            }
        }
    }

    private fun embedCredentialsInUri(uri: String, credentials: Credentials?): String {
        if (credentials == null) return uri

        return try {
            val rtspPattern = Regex("^(rtsp://)([^/]+)(/.*)$")
            val match = rtspPattern.find(uri)
            if (match != null) {
                val (protocol, host, path) = match.destructured
                "$protocol${credentials.username}:${credentials.password}@$host$path"
            } else {
                uri
            }
        } catch (e: Exception) {
            uri
        }
    }

    fun submitCredentials(username: String, password: String) {
        val deviceId = _uiState.value.credentialsDialogCameraId ?: return
        _uiState.value = _uiState.value.copy(showCredentialsDialog = false, credentialsDialogCameraId = null)

        viewModelScope.launch {
            val camera = _uiState.value.cameras.find { it.device.id == deviceId } ?: return@launch
            val credentials = Credentials(username, password)

            updateCameraState(deviceId) { it.copy(authStatus = AuthStatus.AUTHENTICATING) }

            if (client.authenticate(camera.device, credentials)) {
                onAuthSuccess(deviceId, credentials)
            } else {
                updateCameraState(deviceId) { it.copy(authStatus = AuthStatus.FAILED) }
                _uiState.value = _uiState.value.copy(error = "Authentication failed for ${camera.device.displayName}")
            }
        }
    }

    fun dismissCredentialsDialog() {
        _uiState.value = _uiState.value.copy(showCredentialsDialog = false, credentialsDialogCameraId = null)
    }

    fun clearError() {
        _uiState.value = _uiState.value.copy(error = null)
    }

    fun retryAuthentication(deviceId: String) {
        _uiState.value = _uiState.value.copy(
            showCredentialsDialog = true,
            credentialsDialogCameraId = deviceId
        )
    }

    fun showAddCameraDialog() {
        _uiState.value = _uiState.value.copy(showAddCameraDialog = true)
    }

    fun dismissAddCameraDialog() {
        _uiState.value = _uiState.value.copy(showAddCameraDialog = false)
    }

    fun addCameraManually(ipAddress: String, port: Int) {
        _uiState.value = _uiState.value.copy(showAddCameraDialog = false)

        val deviceId = "$ipAddress:$port"
        val device = OnvifDevice(
            id = deviceId,
            address = ipAddress,
            port = port
        )

        val nextIndex = (_uiState.value.cameras.maxOfOrNull { it.index } ?: 0) + 1
        val cameraState = CameraState(device = device, index = nextIndex)

        _uiState.value = _uiState.value.copy(
            cameras = _uiState.value.cameras + cameraState
        )

        // Authenticate the new camera
        authenticateCamera(deviceId)
    }

    private fun updateCameraState(deviceId: String, update: (CameraState) -> CameraState) {
        _uiState.value = _uiState.value.copy(
            cameras = _uiState.value.cameras.map { camera ->
                if (camera.device.id == deviceId) update(camera) else camera
            }
        )
    }
}
