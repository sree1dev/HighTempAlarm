package com.hightempalarm

import android.app.Activity
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

class AlarmActivity : Activity() {

    override fun onCreate(
        savedInstanceState: Bundle?
    ) {

        super.onCreate(savedInstanceState)

        if (
            Build.VERSION.SDK_INT >=
            Build.VERSION_CODES.O_MR1
        ) {

            setShowWhenLocked(true)
            setTurnScreenOn(true)
        }

        window.addFlags(
            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
                WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD
        )

        setContentView(
            R.layout.activity_alarm
        )

        updateAlarmData(intent)

        val root =
            findViewById<LinearLayout>(
                R.id.alarmRoot
            )

        val stopButton =
            findViewById<Button>(
                R.id.stopAlarmButton
            )

        root.setOnClickListener {
            stopAlarm()
        }

        stopButton.setOnClickListener {
            stopAlarm()
        }
    }

    override fun onNewIntent(
        intent: Intent
    ) {

        super.onNewIntent(intent)

        setIntent(intent)

        updateAlarmData(intent)
    }

    private fun updateAlarmData(
        intent: Intent
    ) {

        val temperature =
            intent.getFloatExtra(
                "temperature",
                0f
            )

        val threshold =
            intent.getFloatExtra(
                "threshold",
                0f
            )

        findViewById<TextView>(
            R.id.alarmTemperature
        ).text =
            String.format(
                "%.1f °C",
                temperature
            )

        findViewById<TextView>(
            R.id.alarmThreshold
        ).text =
            String.format(
                "Limit: %.1f °C",
                threshold
            )
    }

    private fun stopAlarm() {

        val stopIntent =
            Intent(
                this,
                TemperatureMonitorService::class.java
            ).apply {

                action =
                    TemperatureMonitorService.ACTION_STOP_ALARM
            }

        try {

            startService(
                stopIntent
            )

        } catch (_: Exception) {
        }

        finishAndRemoveTask()
    }
}
