package com.octo4a.ui.fragments

import android.Manifest
import android.content.Context.CAMERA_SERVICE
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.camera2.CameraManager
import android.os.Bundle
import android.text.InputType
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.core.content.ContextCompat
import androidx.preference.*
import com.octo4a.R
import com.octo4a.camera.*
import com.octo4a.repository.OctoPrintHandlerRepository
import com.octo4a.service.CameraService
import com.octo4a.utils.isServiceRunning
import com.octo4a.utils.preferences.MainPreferences
import org.koin.android.ext.android.inject

class SettingsFragment : PreferenceFragmentCompat() {
    private val manager by lazy { context?.getSystemService(CAMERA_SERVICE) as CameraManager }
    private val prefs: MainPreferences by inject()
    private val octoprintHandler: OctoPrintHandlerRepository by inject()

    // Camera permission request
    private val hasCameraPermission: Boolean
        get() = ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

    // Preferences
    private val enableCameraPref by lazy { findPreference<SwitchPreferenceCompat>("enableCameraServer") }
    private val enableSSH by lazy { findPreference<SwitchPreferenceCompat>("enableSSH") }
    private val selectedCameraPref by lazy { findPreference<ListPreference>("selectedCamera") }
    private val selectedCameraResolution by lazy { findPreference<ListPreference>("selectedResolution") }
    private val sshPasswordPref by lazy { findPreference<EditTextPreference>("changeSSHPassword") }

    private val requestCameraPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted ->
            enableCameraPref?.isChecked = isGranted
            if (isGranted) {
                octoprintHandler.isCameraServerRunning = true
                initCameraConfig()
            }
        }
    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        setPreferencesFromResource(R.xml.main_preferences, rootKey)
        setupCameraSettings()

        findPreference<EditTextPreference>("serverPort")?.apply {
            setOnBindEditTextListener {
                it.inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_SIGNED
            }
            setDefaultValue("5000")
        }

        setupSSHSettings()
    }

    private fun setupSSHSettings() {
        enableSSH?.setOnPreferenceChangeListener {
                _, newValue ->
            if (newValue as Boolean) {
                if (octoprintHandler.isSSHConfigured) {
                    octoprintHandler.startSSH()
                } else {
                    enableSSH?.isChecked = false
                    preferenceManager.showDialog(sshPasswordPref)
                }
            } else {
                octoprintHandler.stopSSH()
            }
            true
        }

        sshPasswordPref?.apply {
            summary = "*".repeat(prefs.changeSSHPassword?.length ?: 0)
            setOnBindEditTextListener {
                it.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                sshPasswordPref?.summaryProvider = Preference.SummaryProvider<EditTextPreference> {
                        pref ->
                    "*".repeat(pref?.text?.length ?: 0)
                }
            }
            setOnPreferenceChangeListener { _, newValue ->
                octoprintHandler.stopSSH()
                octoprintHandler.resetSSHPassword(newValue as String)
                octoprintHandler.startSSH()
                enableSSH?.isChecked = true
                true
            }
        }
    }

    private fun setupCameraSettings() {
        val enableCameraPref = findPreference<SwitchPreferenceCompat>("enableCameraServer")
        if (!hasCameraPermission) {
            prefs.enableCameraServer = false
            enableCameraPref?.isChecked = false
        } else {
            initCameraConfig()
        }

        enableCameraPref?.setOnPreferenceChangeListener { _, newValue ->
            if (manager.enumerateDevices().isEmpty()) {
                enableCameraPref.isChecked = false
                Toast.makeText(requireContext(), "No cameras available", Toast.LENGTH_LONG).show()
            } else {
                if (!hasCameraPermission) {
                    enableCameraPref.isChecked = false
                    requestCameraPermission.launch(Manifest.permission.CAMERA)
                } else {
                    initCameraConfig()
                    if (newValue as Boolean) {
                        startCameraServer()
                    } else {
                        stopCameraServer()
                    }
                }
            }
            true
        }
    }

    // Injects proper camera resolution configs
    private fun initCameraConfig() {
        val cameras = manager.enumerateDevices()
        if (cameras.isEmpty()) {
            return
        }
        if (prefs.selectedCamera == null) {
            // Select first back camera or use whatever camera's available
            val selectedCamera =
                cameras.firstOrNull { it.lensFacing == CameraSelector.LENS_FACING_BACK } ?: cameras.firstOrNull()

            // First resolution that's wider than 1000px
            val selectedResolution = selectedCamera?.sizes?.sortedBy { it.width }?.firstOrNull { it.width >= 1000 } ?: selectedCamera?.sizes?.first()
            prefs.selectedCamera = selectedCamera?.id
            prefs.selectedResolution = selectedResolution?.readableString()
        }

        selectedCameraPref?.apply {
            // Set values and descriptions
            entries = cameras.map { it.describeString() }.toTypedArray()
            entryValues = cameras.map { it.id }.toTypedArray()

            // When camera changes, reset to default resolution
            setOnPreferenceChangeListener { _, newValue ->
                val selectedCam = manager.cameraWithId(newValue as String)
                prefs.selectedResolution = selectedCam?.getRecommendedSize()?.readableString()
                setCameraResolutions(newValue)
                selectedCameraResolution?.value = prefs.selectedResolution
                startCameraServer()
                true
            }
        }
        val currentCam = prefs.selectedCamera
        selectedCameraPref?.value = currentCam!!
        setCameraResolutions(currentCam)
        selectedCameraResolution?.value = prefs.selectedResolution
    }

    private fun setCameraResolutions(selectedCam: String) {
        val camera = manager.cameraWithId(selectedCam)
        selectedCameraResolution?.apply {
            entries = camera?.sizes?.map { it.readableString() }?.toTypedArray()
            entryValues = entries
            setOnPreferenceChangeListener { _, value ->
                prefs.selectedResolution = value as String
                startCameraServer()
                true
            }
        }
    }

    private fun startCameraServer() {
        val activityContext = requireActivity()
        val cameraServiceIntent = Intent(activityContext, CameraService::class.java)

        if (activityContext.isServiceRunning(CameraService::class.java)) {
            activityContext.stopService(cameraServiceIntent)
        }
        activityContext.startService(cameraServiceIntent)
    }

    private fun stopCameraServer() {
        val activityContext = requireActivity()
        val cameraServiceIntent = Intent(activityContext, CameraService::class.java)

        if (activityContext.isServiceRunning(CameraService::class.java)) {
            activityContext.stopService(cameraServiceIntent)
        }
    }
}