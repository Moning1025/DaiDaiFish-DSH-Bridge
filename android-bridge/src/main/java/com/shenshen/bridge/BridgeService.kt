package com.shenshen.bridge

import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.util.Log

/**
 * 前台服务：拉起并持有 [BridgeClient]，保证锁屏/后台不被杀。
 * 常驻通知 + START_STICKY 保活。
 */
class BridgeService : Service() {

    companion object {
        private const val TAG = "BridgeService"

        @Volatile
        var isRunning: Boolean = false
            private set
    }

    private var client: BridgeClient? = null

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        ApprovalNotifier.ensureChannels(this)
        startForeground(
            ApprovalNotifier.serviceNotificationId(),
            ApprovalNotifier.buildServiceNotification(this, connected = false),
        )
        Log.i(TAG, "service created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (client == null) {
            val bridge = BridgeClient()
            bridge.notifierContext = applicationContext
            BridgeState.addListener { status, _ ->
                updateNotification(status == BridgeState.Status.CONNECTED)
            }
            bridge.start()
            client = bridge
            Log.i(TAG, "bridge client started")
        }
        return START_STICKY
    }

    override fun onDestroy() {
        Log.i(TAG, "service destroyed")
        client?.stop()
        client = null
        isRunning = false
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun updateNotification(connected: Boolean) {
        runCatching {
            val nm = getSystemService(android.app.NotificationManager::class.java) ?: return
            nm.notify(
                ApprovalNotifier.serviceNotificationId(),
                ApprovalNotifier.buildServiceNotification(this, connected),
            )
        }.onFailure { Log.w(TAG, "update notification failed", it) }
    }
}
