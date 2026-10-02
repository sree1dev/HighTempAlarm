package com.hightempalarm

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.BatteryManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import kotlin.math.floor

class TemperatureMonitorService : Service() {

    companion object {

        const val ACTION_START =
            "com.hightempalarm.START"

        const val ACTION_STOP_ALARM =
            "com.hightempalarm.STOP_ALARM"

        const val ACTION_STOP_SERVICE =
            "com.hightempalarm.STOP_SERVICE"

        private const val CHANNEL_ID =
            "temperature_monitor"

        private const val NOTIFICATION_ID =
            1001

        /*
         * This is NOT continuous polling.
         *
         * Android battery broadcasts remain the primary
         * temperature source.
         *
         * This watchdog runs only:
         *
         * - monitoring ON
         * - charger connected
         * - screen OFF
         *
         * It protects against manufacturers delaying
         * battery broadcasts while the screen is asleep.
         */
        private const val WATCHDOG_DELAY_MS =
            60_000L
    }


    private val prefs by lazy {

        getSharedPreferences(
            MainActivity.PREFS_NAME,
            Context.MODE_PRIVATE
        )
    }


    private lateinit var alarmManager:
        AlarmManager

    private lateinit var powerManager:
        PowerManager


    private var receiverRegistered =
        false


    private var plugged =
        false


    private var lastTemperature:
        Float? = null


    private var lastEvaluatedWholeDegree:
        Int? = null


    /*
     * IMPORTANT:
     *
     * When the user manually presses STOP ALARM,
     * we remember that the current overheating event
     * has already been acknowledged.
     *
     * This prevents:
     *
     * STOP ALARM
     *      ↓
     * still hot
     *      ↓
     * another battery broadcast
     *      ↓
     * alarm immediately starts again
     *
     * It is re-armed after:
     *
     * - unplugging
     *
     * OR
     *
     * - temperature cooling at least 1°C below limit
     */
    private var alarmAcknowledged =
        false


    /*
     * --------------------------------------------------------
     * SCREEN-OFF WATCHDOG
     * --------------------------------------------------------
     *
     * One one-shot check.
     *
     * It schedules another one only if the screen
     * remains off and charging continues.
     */

    private val watchdogListener =
        AlarmManager.OnAlarmListener {

            val enabled =
                prefs.getBoolean(
                    MainActivity.KEY_ENABLED,
                    false
                )

            if (!enabled) {

                cancelWatchdog()

                return@OnAlarmListener
            }


            if (
                plugged &&
                !powerManager.isInteractive
            ) {

                readCurrentBatteryState()
                    ?.let {

                        processBatteryIntent(
                            it,
                            fromWatchdog = true
                        )
                    }


                scheduleWatchdogIfNeeded()
            }
        }


    /*
     * --------------------------------------------------------
     * EVENT RECEIVER
     * --------------------------------------------------------
     */

    private val batteryReceiver =
        object : BroadcastReceiver() {

            override fun onReceive(
                context: Context?,
                intent: Intent?
            ) {

                when (intent?.action) {


                    Intent.ACTION_BATTERY_CHANGED -> {

                        processBatteryIntent(
                            intent,
                            fromWatchdog = false
                        )
                    }


                    Intent.ACTION_POWER_CONNECTED -> {

                        plugged =
                            true

                        readCurrentBatteryState()
                            ?.let {

                                processBatteryIntent(
                                    it,
                                    fromWatchdog = false
                                )
                            }


                        scheduleWatchdogIfNeeded()
                    }


                    Intent.ACTION_POWER_DISCONNECTED -> {

                        /*
                         * UNPLUG ALWAYS WINS.
                         *
                         * Immediately stop the alarm.
                         *
                         * Do not perform another temperature
                         * threshold test after this.
                         */

                        handleUnplugged()
                    }


                    Intent.ACTION_SCREEN_OFF -> {

                        scheduleWatchdogIfNeeded()
                    }


                    Intent.ACTION_SCREEN_ON -> {

                        /*
                         * Screen awake:
                         *
                         * battery broadcasts are our normal
                         * event-driven path, so watchdog is
                         * unnecessary.
                         */

                        cancelWatchdog()
                    }
                }
            }
        }


    /*
     * --------------------------------------------------------
     * SERVICE CREATE
     * --------------------------------------------------------
     */

    override fun onCreate() {

        super.onCreate()


        alarmManager =
            getSystemService(
                AlarmManager::class.java
            )


        powerManager =
            getSystemService(
                PowerManager::class.java
            )


        createNotificationChannel()


        startAsForeground(
            "Waiting for battery temperature..."
        )


        registerBatteryReceiver()


        /*
         * Get current battery state immediately.
         */

        readCurrentBatteryState()
            ?.let {

                processBatteryIntent(
                    it,
                    fromWatchdog = false
                )
            }
    }


    /*
     * --------------------------------------------------------
     * COMMANDS
     * --------------------------------------------------------
     */

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int
    ): Int {


        when (intent?.action) {


            /*
             * USER PRESSED STOP ALARM
             */

            ACTION_STOP_ALARM -> {

                /*
                 * Critical fix:
                 *
                 * Remember that this particular hot episode
                 * has been acknowledged.
                 */

                alarmAcknowledged =
                    true


                AlarmController.stop(
                    this
                )


                updateMonitoringNotification()
            }


            /*
             * USER PRESSED STOP MONITORING
             */

            ACTION_STOP_SERVICE -> {

                prefs.edit()
                    .putBoolean(
                        MainActivity.KEY_ENABLED,
                        false
                    )
                    .apply()


                alarmAcknowledged =
                    false


                AlarmController.stop(
                    this
                )


                cancelWatchdog()


                stopForeground(
                    STOP_FOREGROUND_REMOVE
                )


                stopSelf()


                return START_NOT_STICKY
            }


            /*
             * START MONITORING
             */

            ACTION_START,
            null -> {


                val enabled =
                    prefs.getBoolean(
                        MainActivity.KEY_ENABLED,
                        false
                    )


                if (!enabled) {

                    cancelWatchdog()


                    stopForeground(
                        STOP_FOREGROUND_REMOVE
                    )


                    stopSelf()


                    return START_NOT_STICKY
                }


                readCurrentBatteryState()
                    ?.let {

                        processBatteryIntent(
                            it,
                            fromWatchdog = false
                        )
                    }


                scheduleWatchdogIfNeeded()
            }
        }


        /*
         * Android may recreate the service if the
         * process is killed under normal conditions.
         */

        return START_STICKY
    }


    /*
     * --------------------------------------------------------
     * REGISTER RECEIVER
     * --------------------------------------------------------
     */

    private fun registerBatteryReceiver() {

        if (receiverRegistered) {
            return
        }


        val filter =
            IntentFilter().apply {

                addAction(
                    Intent.ACTION_BATTERY_CHANGED
                )

                addAction(
                    Intent.ACTION_POWER_CONNECTED
                )

                addAction(
                    Intent.ACTION_POWER_DISCONNECTED
                )

                addAction(
                    Intent.ACTION_SCREEN_OFF
                )

                addAction(
                    Intent.ACTION_SCREEN_ON
                )
            }


        if (
            Build.VERSION.SDK_INT >=
            Build.VERSION_CODES.TIRAMISU
        ) {

            registerReceiver(
                batteryReceiver,
                filter,
                Context.RECEIVER_NOT_EXPORTED
            )

        } else {

            @Suppress("DEPRECATION")

            registerReceiver(
                batteryReceiver,
                filter
            )
        }


        receiverRegistered =
            true
    }


    /*
     * --------------------------------------------------------
     * READ CURRENT BATTERY STATE
     * --------------------------------------------------------
     *
     * ACTION_BATTERY_CHANGED is sticky.
     *
     * Asking for the sticky intent gives us the latest
     * system battery state without maintaining a
     * continuous polling loop.
     */

    private fun readCurrentBatteryState():
        Intent? {

        return registerReceiver(
            null,
            IntentFilter(
                Intent.ACTION_BATTERY_CHANGED
            )
        )
    }


    /*
     * --------------------------------------------------------
     * PROCESS TEMPERATURE
     * --------------------------------------------------------
     */

    private fun processBatteryIntent(
        intent: Intent,
        fromWatchdog: Boolean
    ) {


        val enabled =
            prefs.getBoolean(
                MainActivity.KEY_ENABLED,
                false
            )


        if (!enabled) {
            return
        }


        val rawTemperature =
            intent.getIntExtra(
                BatteryManager.EXTRA_TEMPERATURE,
                Int.MIN_VALUE
            )


        if (
            rawTemperature ==
            Int.MIN_VALUE
        ) {

            return
        }


        val temperature =
            rawTemperature / 10f


        val isPlugged =
            intent.getIntExtra(
                BatteryManager.EXTRA_PLUGGED,
                0
            ) != 0


        val threshold =
            prefs.getFloat(
                MainActivity.KEY_THRESHOLD,
                MainActivity.DEFAULT_THRESHOLD
            )


        val wasPlugged =
            plugged


        val previousTemperature =
            lastTemperature


        plugged =
            isPlugged


        lastTemperature =
            temperature


        /*
         * ----------------------------------------------------
         * FIRST PRIORITY: CHARGER STATE
         * ----------------------------------------------------
         *
         * Never check temperature before handling unplug.
         *
         * This fixes the brief alarm that happened while
         * unplugging.
         */

        if (!isPlugged) {

            handleUnplugged(
                temperature
            )

            return
        }


        /*
         * New charging session.
         *
         * Allow a new alarm.
         */

        if (
            !wasPlugged &&
            isPlugged
        ) {

            alarmAcknowledged =
                false


            lastEvaluatedWholeDegree =
                null
        }


        /*
         * ----------------------------------------------------
         * LIGHTWEIGHT TEMPERATURE CHANGE DETECTION
         * ----------------------------------------------------
         *
         * Examples:
         *
         * 35.1 -> 35.2
         *      no meaningful evaluation
         *
         * 35.2 -> 35.8
         *      no meaningful evaluation
         *
         * 35.8 -> 36.0
         *      evaluate
         *
         * This avoids doing unnecessary alarm logic for
         * every tiny 0.1°C battery update.
         */

        val wholeDegree =
            floor(
                temperature.toDouble()
            ).toInt()


        val wholeDegreeChanged =

            lastEvaluatedWholeDegree == null ||

            wholeDegree !=
            lastEvaluatedWholeDegree


        val thresholdReached =
            temperature >=
            threshold


        /*
         * Even if the whole degree hasn't changed,
         * NEVER miss the exact configured threshold.
         *
         * Example:
         *
         * threshold = 42.0
         *
         * 41.8 -> 42.0
         *
         * must alarm immediately.
         */

        val thresholdCrossed =

            thresholdReached &&

            (
                previousTemperature == null ||

                previousTemperature <
                threshold
            )


        /*
         * ----------------------------------------------------
         * RE-ARM AFTER COOLING
         * ----------------------------------------------------
         *
         * User silenced the alarm at 42°C.
         *
         * We don't repeatedly annoy them at:
         *
         * 42.1
         * 42.2
         * 42.3
         *
         * The alarm becomes eligible again only after
         * cooling at least 1°C below the limit.
         */

        if (
            alarmAcknowledged &&
            temperature <=
            threshold - 1.0f
        ) {

            alarmAcknowledged =
                false
        }


        /*
         * Actual alarm evaluation happens only when:
         *
         * 1. whole degree changed
         *
         * OR
         *
         * 2. threshold was crossed
         *
         * OR
         *
         * 3. screen-off watchdog is verifying the state
         */

        val shouldEvaluate =

            wholeDegreeChanged ||

            thresholdCrossed ||

            fromWatchdog


        if (shouldEvaluate) {


            lastEvaluatedWholeDegree =
                wholeDegree


            if (
                thresholdReached &&

                !alarmAcknowledged &&

                !AlarmController.isActive()
            ) {

                AlarmController.start(
                    this,
                    temperature,
                    threshold
                )
            }
        }


        /*
         * Don't rebuild the persistent notification for
         * every 0.1°C change either.
         *
         * Update it only when the displayed whole degree
         * changes or charging has just started.
         */

        if (
            wholeDegreeChanged ||
            !wasPlugged
        ) {

            updateMonitoringNotification(
                temperature,
                true
            )
        }


        scheduleWatchdogIfNeeded()
    }


    /*
     * --------------------------------------------------------
     * UNPLUG
     * --------------------------------------------------------
     */

    private fun handleUnplugged(
        temperature: Float? =
            lastTemperature
    ) {


        /*
         * Set unplugged FIRST.
         */

        plugged =
            false


        /*
         * New charging session can alarm again later.
         */

        alarmAcknowledged =
            false


        lastEvaluatedWholeDegree =
            null


        /*
         * Stop alarm before doing anything else.
         */

        AlarmController.stop(
            this
        )


        cancelWatchdog()


        updateMonitoringNotification(
            temperature,
            false
        )
    }


    /*
     * --------------------------------------------------------
     * SCREEN-OFF WATCHDOG
     * --------------------------------------------------------
     */

    private fun scheduleWatchdogIfNeeded() {


        /*
         * Never allow duplicate scheduled checks.
         */

        cancelWatchdog()


        val enabled =
            prefs.getBoolean(
                MainActivity.KEY_ENABLED,
                false
            )


        /*
         * No watchdog unless ALL are true:
         *
         * monitoring ON
         * charger connected
         * screen OFF
         */

        if (
            !enabled ||
            !plugged ||
            powerManager.isInteractive
        ) {

            return
        }


        /*
         * ELAPSED_REALTIME_WAKEUP means Android can wake
         * the CPU for this tiny check while the screen
         * remains off.
         *
         * This is an INEXACT one-shot alarm.
         *
         * We intentionally do NOT request exact-alarm
         * permission.
         */

        alarmManager.set(
            AlarmManager.ELAPSED_REALTIME_WAKEUP,

            SystemClock.elapsedRealtime() +
                WATCHDOG_DELAY_MS,

            "HeatGuard:screenOffTemperatureCheck",

            watchdogListener,

            null
        )
    }


    private fun cancelWatchdog() {

        try {

            alarmManager.cancel(
                watchdogListener
            )

        } catch (_: Exception) {
        }
    }


    /*
     * --------------------------------------------------------
     * MONITORING NOTIFICATION
     * --------------------------------------------------------
     */

    private fun createNotificationChannel() {


        if (
            Build.VERSION.SDK_INT >=
            Build.VERSION_CODES.O
        ) {


            val channel =
                NotificationChannel(
                    CHANNEL_ID,
                    "Heat Guard monitoring",
                    NotificationManager.IMPORTANCE_LOW
                ).apply {

                    description =
                        "Shows when Heat Guard is monitoring charging temperature"


                    setSound(
                        null,
                        null
                    )


                    enableVibration(
                        false
                    )


                    setShowBadge(
                        false
                    )
                }


            getSystemService(
                NotificationManager::class.java
            ).createNotificationChannel(
                channel
            )
        }
    }


    private fun buildNotification(
        text: String
    ): Notification {


        return Notification.Builder(
            this,
            CHANNEL_ID
        )
            .setSmallIcon(
                android.R.drawable.ic_lock_idle_charging
            )
            .setContentTitle(
                "Heat Guard is monitoring"
            )
            .setContentText(
                text
            )
            .setOngoing(
                true
            )
            .setOnlyAlertOnce(
                true
            )
            .setCategory(
                Notification.CATEGORY_SERVICE
            )
            .build()
    }


    private fun startAsForeground(
        text: String
    ) {


        val notification =
            buildNotification(
                text
            )


        if (
            Build.VERSION.SDK_INT >=
            Build.VERSION_CODES.UPSIDE_DOWN_CAKE
        ) {


            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )

        } else {


            startForeground(
                NOTIFICATION_ID,
                notification
            )
        }
    }


    private fun updateMonitoringNotification(
        temperature: Float? =
            lastTemperature,

        isPlugged: Boolean =
            plugged
    ) {


        val threshold =
            prefs.getFloat(
                MainActivity.KEY_THRESHOLD,
                MainActivity.DEFAULT_THRESHOLD
            )


        val text =
            when {


                temperature == null ->

                    "Waiting for battery temperature..."


                isPlugged ->

                    String.format(
                        "%.1f°C • Alert at %.1f°C • Charging",
                        temperature,
                        threshold
                    )


                else ->

                    String.format(
                        "%.1f°C • Waiting for charger",
                        temperature
                    )
            }


        getSystemService(
            NotificationManager::class.java
        ).notify(
            NOTIFICATION_ID,
            buildNotification(
                text
            )
        )
    }


    /*
     * --------------------------------------------------------
     * CLEANUP
     * --------------------------------------------------------
     */

    override fun onDestroy() {


        cancelWatchdog()


        if (receiverRegistered) {


            try {

                unregisterReceiver(
                    batteryReceiver
                )

            } catch (_: Exception) {
            }


            receiverRegistered =
                false
        }


        super.onDestroy()
    }


    override fun onBind(
        intent: Intent?
    ): IBinder? {

        return null
    }
}
