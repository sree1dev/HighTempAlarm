package com.hightempalarm

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {

    private lateinit var currentTemperature: TextView
    private lateinit var chargingStatus: TextView
    private lateinit var thresholdInput: EditText
    private lateinit var monitorStatus: TextView
    private lateinit var monitorButton: Button

    private val prefs by lazy {
        getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    companion object {
        const val PREFS_NAME = "high_temp_alarm_settings"
        const val KEY_THRESHOLD = "threshold"
        const val KEY_ENABLED = "monitoring_enabled"

        const val DEFAULT_THRESHOLD = 42.0f

        const val NOTIFICATION_PERMISSION_REQUEST = 100
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContentView(R.layout.activity_main)

        currentTemperature =
            findViewById(R.id.currentTemperature)

        chargingStatus =
            findViewById(R.id.chargingStatus)

        thresholdInput =
            findViewById(R.id.thresholdInput)

        monitorStatus =
            findViewById(R.id.monitorStatus)

        monitorButton =
            findViewById(R.id.monitorButton)

        val overlayButton: Button =
            findViewById(R.id.overlayButton)

        val savedThreshold =
            prefs.getFloat(
                KEY_THRESHOLD,
                DEFAULT_THRESHOLD
            )

        thresholdInput.setText(
            if (savedThreshold % 1f == 0f) {
                savedThreshold.toInt().toString()
            } else {
                savedThreshold.toString()
            }
        )

        monitorButton.setOnClickListener {

            val currentlyEnabled =
                prefs.getBoolean(
                    KEY_ENABLED,
                    false
                )

            if (currentlyEnabled) {
                stopMonitoring()
            } else {
                startMonitoring()
            }
        }

        /*
         * If monitoring is active and user taps
         * the temperature field:
         *
         * 1. Stop monitoring
         * 2. Enable the field
         * 3. Focus it
         * 4. Open keyboard
         */
        thresholdInput.setOnClickListener {

            val enabled =
                prefs.getBoolean(
                    KEY_ENABLED,
                    false
                )

            if (enabled) {

                stopMonitoring(
                    showToast = false
                )

                thresholdInput.isEnabled = true
                thresholdInput.alpha = 1.0f

                thresholdInput.requestFocus()
                thresholdInput.selectAll()

                thresholdInput.post {

                    val keyboard =
                        getSystemService(
                            Context.INPUT_METHOD_SERVICE
                        ) as InputMethodManager

                    keyboard.showSoftInput(
                        thresholdInput,
                        InputMethodManager.SHOW_IMPLICIT
                    )
                }
            }
        }

        overlayButton.setOnClickListener {
            requestOverlayPermission()
        }

        requestNotificationPermission()

        updateBatteryDisplay()
        updateMonitoringStatus()
    }

    override fun onResume() {
        super.onResume()

        updateBatteryDisplay()
        updateMonitoringStatus()
    }

    private fun startMonitoring() {

        val threshold =
            thresholdInput.text
                .toString()
                .toFloatOrNull()

        if (
            threshold == null ||
            threshold < 20f ||
            threshold > 60f
        ) {

            Toast.makeText(
                this,
                "Enter a temperature between 20°C and 60°C",
                Toast.LENGTH_LONG
            ).show()

            return
        }

        prefs.edit()
            .putFloat(
                KEY_THRESHOLD,
                threshold
            )
            .putBoolean(
                KEY_ENABLED,
                true
            )
            .apply()

        val intent =
            Intent(
                this,
                TemperatureMonitorService::class.java
            ).apply {

                action =
                    TemperatureMonitorService.ACTION_START
            }

        if (
            Build.VERSION.SDK_INT >=
            Build.VERSION_CODES.O
        ) {

            startForegroundService(intent)

        } else {

            startService(intent)
        }

        updateMonitoringStatus()

        Toast.makeText(
            this,
            "Temperature monitoring started",
            Toast.LENGTH_SHORT
        ).show()
    }

    private fun stopMonitoring(
        showToast: Boolean = true
    ) {

        prefs.edit()
            .putBoolean(
                KEY_ENABLED,
                false
            )
            .apply()

        val intent =
            Intent(
                this,
                TemperatureMonitorService::class.java
            ).apply {

                action =
                    TemperatureMonitorService.ACTION_STOP_SERVICE
            }

        try {
            startService(intent)
        } catch (_: Exception) {
        }

        updateMonitoringStatus()

        if (showToast) {

            Toast.makeText(
                this,
                "Temperature monitoring stopped",
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    private fun updateMonitoringStatus() {

        val enabled =
            prefs.getBoolean(
                KEY_ENABLED,
                false
            )

        if (enabled) {

            monitorStatus.text =
                "Monitoring is ON"

            monitorStatus.setTextColor(
                0xFF49D58A.toInt()
            )

            monitorButton.text =
                "STOP MONITORING"

            monitorButton.setBackgroundResource(
                R.drawable.button_stop
            )

            /*
             * Keep visually muted while monitoring,
             * but still allow click detection.
             *
             * Do NOT disable the EditText completely,
             * otherwise Android won't deliver clicks.
             */
            thresholdInput.isFocusable = false
            thresholdInput.isFocusableInTouchMode = false
            thresholdInput.isCursorVisible = false
            thresholdInput.alpha = 0.55f

        } else {

            monitorStatus.text =
                "Monitoring is OFF"

            monitorStatus.setTextColor(
                0xFFFF5A52.toInt()
            )

            monitorButton.text =
                "START MONITORING"

            monitorButton.setBackgroundResource(
                R.drawable.button_start
            )

            thresholdInput.isFocusable = true
            thresholdInput.isFocusableInTouchMode = true
            thresholdInput.isCursorVisible = true
            thresholdInput.alpha = 1.0f
        }
    }

    private fun updateBatteryDisplay() {

        val intent =
            registerReceiver(
                null,
                IntentFilter(
                    Intent.ACTION_BATTERY_CHANGED
                )
            ) ?: return

        val rawTemperature =
            intent.getIntExtra(
                BatteryManager.EXTRA_TEMPERATURE,
                0
            )

        val temperature =
            rawTemperature / 10f

        val plugged =
            intent.getIntExtra(
                BatteryManager.EXTRA_PLUGGED,
                0
            ) != 0

        currentTemperature.text =
            String.format(
                "%.1f °C",
                temperature
            )

        chargingStatus.text =
            if (plugged) {
                "Charger connected"
            } else {
                "Charger disconnected"
            }
    }

    private fun requestOverlayPermission() {

        if (
            Settings.canDrawOverlays(this)
        ) {

            Toast.makeText(
                this,
                "Alarm overlay is already allowed",
                Toast.LENGTH_SHORT
            ).show()

            return
        }

        val intent =
            Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse(
                    "package:$packageName"
                )
            )

        startActivity(intent)
    }

    private fun requestNotificationPermission() {

        if (
            Build.VERSION.SDK_INT >=
            Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(
                Manifest.permission.POST_NOTIFICATIONS
            ) != PackageManager.PERMISSION_GRANTED
        ) {

            requestPermissions(
                arrayOf(
                    Manifest.permission.POST_NOTIFICATIONS
                ),
                NOTIFICATION_PERMISSION_REQUEST
            )
        }
    }
}

