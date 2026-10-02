package com.hightempalarm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build

class BootReceiver : BroadcastReceiver() {

    override fun onReceive(
        context: Context,
        intent: Intent
    ) {

        if (
            intent.action !=
            Intent.ACTION_BOOT_COMPLETED
        ) {
            return
        }

        val prefs =
            context.getSharedPreferences(
                MainActivity.PREFS_NAME,
                Context.MODE_PRIVATE
            )

        val enabled =
            prefs.getBoolean(
                MainActivity.KEY_ENABLED,
                false
            )

        if (!enabled) {
            return
        }

        val serviceIntent =
            Intent(
                context,
                TemperatureMonitorService::class.java
            ).apply {

                action =
                    TemperatureMonitorService.ACTION_START
            }

        try {

            if (
                Build.VERSION.SDK_INT >=
                Build.VERSION_CODES.O
            ) {

                context.startForegroundService(
                    serviceIntent
                )

            } else {

                context.startService(
                    serviceIntent
                )
            }

        } catch (_: Exception) {
        }
    }
}
