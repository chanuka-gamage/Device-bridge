package com.yourname.devicebridge

import android.Manifest
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

// Config is stored locally on the phone in SharedPreferences - fill these in once.
class MainActivity : AppCompatActivity() {

    private lateinit var prefs: SharedPreferences
    private lateinit var serverUrlInput: EditText
    private lateinit var tokenInput: EditText
    private lateinit var deviceNameInput: EditText
    private lateinit var statusText: TextView

    private val requiredPermissions = mutableListOf(
        Manifest.permission.ACCESS_FINE_LOCATION,
        Manifest.permission.ACCESS_COARSE_LOCATION
    ).apply {
        if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
    }.toTypedArray()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        prefs = getSharedPreferences("device_bridge", MODE_PRIVATE)
        serverUrlInput = findViewById(R.id.serverUrlInput)
        tokenInput = findViewById(R.id.tokenInput)
        deviceNameInput = findViewById(R.id.deviceNameInput)
        statusText = findViewById(R.id.statusText)

        serverUrlInput.setText(prefs.getString("server_url", "https://your-backend.example.com"))
        tokenInput.setText(prefs.getString("token", ""))
        deviceNameInput.setText(prefs.getString("device_name", "My Phone"))

        findViewById<Button>(R.id.startButton).setOnClickListener { startTracking() }
        findViewById<Button>(R.id.stopButton).setOnClickListener { stopTracking() }

        updateStatus()
    }

    private fun saveConfig() {
        prefs.edit()
            .putString("server_url", serverUrlInput.text.toString().trim())
            .putString("token", tokenInput.text.toString().trim())
            .putString("device_name", deviceNameInput.text.toString().trim())
            .apply()
    }

    private fun hasAllPermissions(): Boolean =
        requiredPermissions.all {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }

    private fun startTracking() {
        saveConfig()
        if (!hasAllPermissions()) {
            ActivityCompat.requestPermissions(this, requiredPermissions, 100)
            Toast.makeText(this, "Grant location permission, then tap Start again", Toast.LENGTH_LONG).show()
            return
        }
        // Background location must be requested separately on Android 10+
        if (Build.VERSION.SDK_INT >= 29 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_BACKGROUND_LOCATION)
            != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(
                this, arrayOf(Manifest.permission.ACCESS_BACKGROUND_LOCATION), 101
            )
            Toast.makeText(
                this,
                "Also allow location 'All the time' in the next screen for tracking to work when the app is closed",
                Toast.LENGTH_LONG
            ).show()
        }

        val intent = Intent(this, TrackingService::class.java)
        ContextCompat.startForegroundService(this, intent)
        updateStatus()
    }

    private fun stopTracking() {
        stopService(Intent(this, TrackingService::class.java))
        updateStatus()
    }

    private fun updateStatus() {
        statusText.text = if (TrackingService.isRunning) "Status: tracking active" else "Status: stopped"
    }

    override fun onResume() {
        super.onResume()
        updateStatus()
    }
}
