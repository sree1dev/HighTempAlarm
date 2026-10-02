package com.hightempalarm

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

object AlarmController {

    private const val ALARM_CHANNEL =
        "heat_guard_critical_alarm_v3"

    private const val ALARM_NOTIFICATION_ID =
        2001

    @Volatile
    private var active = false

    private var mediaPlayer: MediaPlayer? = null
    private var overlayView: View? = null
    private var windowManager: WindowManager? = null


    fun isActive(): Boolean {
        return active
    }


    fun start(
        context: Context,
        temperature: Float,
        threshold: Float
    ) {

        if (active) {
            return
        }

        active = true

        /*
         * Start the actual alarm first.
         */
        startSound(context)
        startVibration(context)

        /*
         * IMPORTANT:
         *
         * The notification/full-screen intent is now
         * ALWAYS posted immediately.
         *
         * We no longer depend on a background service
         * directly launching AlarmActivity.
         */
        showAlarmNotification(
            context,
            temperature,
            threshold
        )

        /*
         * If the phone is already unlocked and overlays
         * are allowed, also show our red overlay.
         *
         * The notification remains as a reliable system
         * alarm surface.
         */
        if (Settings.canDrawOverlays(context)) {

            showOverlay(
                context,
                temperature,
                threshold
            )
        }
    }


    fun stop(
        context: Context
    ) {

        active = false

        try {
            mediaPlayer?.stop()
        } catch (_: Exception) {
        }

        try {
            mediaPlayer?.release()
        } catch (_: Exception) {
        }

        mediaPlayer = null

        stopVibration(context)
        removeOverlay()

        try {

            context.getSystemService(
                NotificationManager::class.java
            ).cancel(
                ALARM_NOTIFICATION_ID
            )

        } catch (_: Exception) {
        }
    }


    /*
     * --------------------------------------------------------
     * ALARM NOTIFICATION + FULL-SCREEN INTENT
     * --------------------------------------------------------
     */

    private fun showAlarmNotification(
        context: Context,
        temperature: Float,
        threshold: Float
    ) {

        createAlarmChannel(context)

        val fullScreenIntent =
            Intent(
                context,
                AlarmActivity::class.java
            ).apply {

                putExtra(
                    "temperature",
                    temperature
                )

                putExtra(
                    "threshold",
                    threshold
                )

                flags =
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP
            }


        val fullScreenPendingIntent =
            PendingIntent.getActivity(
                context,
                500,
                fullScreenIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or
                    PendingIntent.FLAG_IMMUTABLE
            )


        /*
         * STOP button directly from notification.
         */
        val stopIntent =
            Intent(
                context,
                TemperatureMonitorService::class.java
            ).apply {

                action =
                    TemperatureMonitorService.ACTION_STOP_ALARM
            }


        val stopPendingIntent =
            PendingIntent.getService(
                context,
                501,
                stopIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or
                    PendingIntent.FLAG_IMMUTABLE
            )


        val notification =
            Notification.Builder(
                context,
                ALARM_CHANNEL
            )
                .setSmallIcon(
                    android.R.drawable.ic_dialog_alert
                )
                .setContentTitle(
                    "Battery temperature warning"
                )
                .setContentText(
                    String.format(
                        "Battery %.1f°C • Limit %.1f°C • Unplug charger",
                        temperature,
                        threshold
                    )
                )
                .setCategory(
                    Notification.CATEGORY_ALARM
                )
                .setVisibility(
                    Notification.VISIBILITY_PUBLIC
                )
                .setPriority(
                    Notification.PRIORITY_MAX
                )
                .setOngoing(true)
                .setAutoCancel(false)
                .setContentIntent(
                    fullScreenPendingIntent
                )
                .setFullScreenIntent(
                    fullScreenPendingIntent,
                    true
                )
                .addAction(
                    android.R.drawable.ic_menu_close_clear_cancel,
                    "STOP ALARM",
                    stopPendingIntent
                )
                .build()


        val manager =
            context.getSystemService(
                NotificationManager::class.java
            )

        manager.notify(
            ALARM_NOTIFICATION_ID,
            notification
        )
    }


    private fun createAlarmChannel(
        context: Context
    ) {

        if (
            Build.VERSION.SDK_INT <
            Build.VERSION_CODES.O
        ) {
            return
        }

        val manager =
            context.getSystemService(
                NotificationManager::class.java
            )

        /*
         * IMPORTANCE_HIGH is required for the
         * urgent/full-screen alarm behavior.
         */
        val channel =
            NotificationChannel(
                ALARM_CHANNEL,
                "Heat Guard temperature alarms",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {

                description =
                    "Urgent battery temperature alarms while charging"

                lockscreenVisibility =
                    Notification.VISIBILITY_PUBLIC

                /*
                 * Heat Guard itself plays the looping alarm.
                 * Don't let the notification channel play a
                 * second sound on top of it.
                 */
                setSound(
                    null,
                    null
                )

                enableVibration(false)

                setShowBadge(false)
            }

        manager.createNotificationChannel(
            channel
        )
    }


    /*
     * --------------------------------------------------------
     * SOUND
     * --------------------------------------------------------
     */

    private fun startSound(
        context: Context
    ) {

        try {

            var uri =
                RingtoneManager.getDefaultUri(
                    RingtoneManager.TYPE_ALARM
                )

            if (uri == null) {

                uri =
                    RingtoneManager.getDefaultUri(
                        RingtoneManager.TYPE_NOTIFICATION
                    )
            }

            val player =
                MediaPlayer()

            player.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(
                        AudioAttributes.USAGE_ALARM
                    )
                    .setContentType(
                        AudioAttributes.CONTENT_TYPE_SONIFICATION
                    )
                    .build()
            )

            player.setDataSource(
                context,
                uri
            )

            player.isLooping = true

            player.prepare()
            player.start()

            mediaPlayer = player

        } catch (_: Exception) {

            mediaPlayer = null
        }
    }


    /*
     * --------------------------------------------------------
     * VIBRATION
     * --------------------------------------------------------
     */

    private fun startVibration(
        context: Context
    ) {

        val pattern =
            longArrayOf(
                0,
                700,
                300,
                700,
                300
            )

        try {

            if (Build.VERSION.SDK_INT >= 31) {

                val manager =
                    context.getSystemService(
                        VibratorManager::class.java
                    )

                manager.defaultVibrator.vibrate(
                    VibrationEffect.createWaveform(
                        pattern,
                        0
                    )
                )

            } else {

                @Suppress("DEPRECATION")

                val vibrator =
                    context.getSystemService(
                        Context.VIBRATOR_SERVICE
                    ) as Vibrator

                vibrator.vibrate(
                    VibrationEffect.createWaveform(
                        pattern,
                        0
                    )
                )
            }

        } catch (_: Exception) {
        }
    }


    private fun stopVibration(
        context: Context
    ) {

        try {

            if (Build.VERSION.SDK_INT >= 31) {

                context.getSystemService(
                    VibratorManager::class.java
                ).cancel()

            } else {

                @Suppress("DEPRECATION")

                val vibrator =
                    context.getSystemService(
                        Context.VIBRATOR_SERVICE
                    ) as Vibrator

                vibrator.cancel()
            }

        } catch (_: Exception) {
        }
    }


    /*
     * --------------------------------------------------------
     * OVERLAY
     * --------------------------------------------------------
     */

    private fun showOverlay(
        context: Context,
        temperature: Float,
        threshold: Float
    ) {

        if (overlayView != null) {
            return
        }

        try {

            windowManager =
                context.getSystemService(
                    Context.WINDOW_SERVICE
                ) as WindowManager


            val density =
                context.resources
                    .displayMetrics
                    .density


            val container =
                LinearLayout(context).apply {

                    orientation =
                        LinearLayout.VERTICAL

                    gravity =
                        Gravity.CENTER

                    isClickable = true
                    isFocusable = true

                    setPadding(
                        (22 * density).toInt(),
                        (22 * density).toInt(),
                        (22 * density).toInt(),
                        (22 * density).toInt()
                    )

                    setBackgroundColor(
                        Color.rgb(
                            183,
                            28,
                            28
                        )
                    )

                    setOnClickListener {
                        stop(context)
                    }
                }


            val title =
                TextView(context).apply {

                    text =
                        "BATTERY TOO HOT"

                    setTextColor(
                        Color.WHITE
                    )

                    textSize = 24f

                    gravity =
                        Gravity.CENTER

                    isClickable = false
                }


            val temp =
                TextView(context).apply {

                    text =
                        String.format(
                            "%.1f °C",
                            temperature
                        )

                    setTextColor(
                        Color.WHITE
                    )

                    textSize = 44f

                    gravity =
                        Gravity.CENTER

                    isClickable = false
                }


            val limit =
                TextView(context).apply {

                    text =
                        String.format(
                            "Limit: %.1f °C",
                            threshold
                        )

                    setTextColor(
                        Color.WHITE
                    )

                    textSize = 16f

                    gravity =
                        Gravity.CENTER

                    isClickable = false
                }


            val unplug =
                TextView(context).apply {

                    text =
                        "UNPLUG THE CHARGER"

                    setTextColor(
                        Color.WHITE
                    )

                    textSize = 18f

                    gravity =
                        Gravity.CENTER

                    setPadding(
                        0,
                        (12 * density).toInt(),
                        0,
                        0
                    )

                    isClickable = false
                }


            val hint =
                TextView(context).apply {

                    text =
                        "Tap anywhere to stop"

                    setTextColor(
                        Color.WHITE
                    )

                    alpha = 0.75f

                    textSize = 13f

                    gravity =
                        Gravity.CENTER

                    setPadding(
                        0,
                        (8 * density).toInt(),
                        0,
                        (12 * density).toInt()
                    )

                    isClickable = false
                }


            val button =
                Button(context).apply {

                    text =
                        "STOP ALARM"

                    textSize = 17f

                    setOnClickListener {
                        stop(context)
                    }
                }


            container.addView(title)
            container.addView(temp)
            container.addView(limit)
            container.addView(unplug)
            container.addView(hint)
            container.addView(button)


            val params =
                WindowManager.LayoutParams(
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                    WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON,
                    PixelFormat.TRANSLUCENT
                )


            params.gravity =
                Gravity.TOP


            overlayView =
                container


            windowManager?.addView(
                container,
                params
            )

        } catch (_: Exception) {

            overlayView = null
        }
    }


    private fun removeOverlay() {

        val view =
            overlayView ?: return

        try {

            windowManager?.removeView(
                view
            )

        } catch (_: Exception) {
        }

        overlayView = null
        windowManager = null
    }
}
