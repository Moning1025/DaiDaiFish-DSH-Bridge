package com.shenshen.bridge

import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log

/**
 * 前台服务：拉起并持有 [BridgeClient] 单例，保证锁屏/后台不被杀。
 * 常驻通知（点它进 DSH 控制台）+ START_STICKY 保活。
 */
class BridgeService : Service() {

    companion object {
        private const val TAG = "BridgeService"

        @Volatile
        var isRunning: Boolean = false
            private set

        /** UI / 通知按钮都可以用它确保服务在跑 */
        fun start(context: Context) {
            runCatching {
                val intent = Intent(context, BridgeService::class.java)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            }.onFailure { Log.w(TAG, "start service failed", it) }
        }
    }

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        ApprovalNotifier.ensureChannels(this)
        BridgeHooks.attachContext(this)
        startForeground(
            ApprovalNotifier.serviceNotificationId(),
            ApprovalNotifier.buildServiceNotification(this, connected = false),
        )
        Log.i(TAG, "service created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (BridgeClient.shared == null) {
            val bridge = BridgeClient()
            bridge.notifierContext = applicationContext
            BridgeState.addListener { status, _ ->
                updateNotification(status == BridgeState.Status.CONNECTED)
            }
            BridgeClient.install(bridge)
            bridge.start()
            Log.i(TAG, "bridge client started")
        }
        return START_STICKY
    }

    override fun onDestroy() {
        Log.i(TAG, "service destroyed")
        BridgeClient.shared?.stop()
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
