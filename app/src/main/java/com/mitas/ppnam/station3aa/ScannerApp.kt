package com.mitas.ppnam.station3aa

import android.app.Activity
import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Bundle

class ScannerApp : Application() {

    private var currentActivity: Activity? = null

    companion object {
        /** RFID tag that opens Settings from anywhere — never treat it as a badge or source. */
        const val SETTINGS_RFID = "E28011700000021B2F6E9827"
        const val ACTION_RFID = "com.rscja.scanner.action.scanner.RFID"
        const val ACTION_BARCODE = "com.scanner.broadcast"
        const val EXTRA_SCAN_DATA = "data"
    }

    private val rfidShortcutReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != ACTION_RFID) return
            if (intent.getStringExtra(EXTRA_SCAN_DATA) != SETTINGS_RFID) return
            // The Chainway RFID broadcast reaches every station app on the device. Only act
            // while one of this app's screens is resumed (currentActivity is cleared in
            // onActivityPaused): a tag read meant for the app in front must not pull Station 3's
            // Settings over it (audit group (l)).
            val host = currentActivity ?: return
            host.startActivity(Intent(host, SettingsActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
            })
        }
    }

    override fun onCreate() {
        super.onCreate()

        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
            override fun onActivityStarted(activity: Activity) { currentActivity = activity }
            override fun onActivityResumed(activity: Activity) {
                currentActivity = activity
                checkStationStatus(activity)
            }
            override fun onActivityPaused(activity: Activity) { if (currentActivity == activity) currentActivity = null }
            override fun onActivityStopped(activity: Activity) {}
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
            override fun onActivityDestroyed(activity: Activity) {}
        })

        // Register Global RFID Shortcut Receiver
        val rfidFilter = IntentFilter("com.rscja.scanner.action.scanner.RFID")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(rfidShortcutReceiver, rfidFilter, Context.RECEIVER_EXPORTED)
        } else {
            registerReceiver(rfidShortcutReceiver, rfidFilter)
        }

        // Initialize and connect MQTT globally
        val mqtt = MqttManager.getInstance(this)
        mqtt.connect()

        // Heartbeat while signed in, presence refresh on the login screen (desktop contract §3).
        SessionKeeper(this).start()

        // Inactivity auto sign-out with a reason on the login screen (audit static-05).
        SessionGuard.install(this)

        mqtt.addStationStatusListener { online ->
            if (!online) {
                currentActivity?.let { checkStationStatus(it) }
            }
        }
    }

    private fun checkStationStatus(activity: Activity) {
        val mqtt = MqttManager.getInstance(this)
        // The offline overlay lives on MainActivity, which requires a session — with no operator
        // logged in, bouncing LoginActivity (or Settings under it) to MainActivity would just
        // bounce straight back and loop.
        if (OperatorSessionHolder.session == null) return
        if (!mqtt.isStationOnline && activity !is MainActivity) {
            val intent = Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_NEW_TASK
            }
            startActivity(intent)
        }
    }
}
