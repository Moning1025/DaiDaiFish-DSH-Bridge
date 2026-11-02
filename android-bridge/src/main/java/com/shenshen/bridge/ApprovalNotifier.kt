package com.shenshen.bridge

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build

/**
 * 审批请求通知 + 前台服务常驻通知。
 * 只依赖系统 API（无 androidx），方便 kotlinc + d8 直接编成 classes2.dex。
 */
object ApprovalNotifier {

    const val CHANNEL_APPROVAL = "bridge_approval"
    const val CHANNEL_SERVICE = "bridge_service"
    private const val NOTIFY_ID_APPROVAL = 1001
    private const val NOTIFY_ID_SERVICE = 1000

    fun ensureChannels(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_APPROVAL,
                "审批请求",
                NotificationManager.IMPORTANCE_HIGH,
            ).apply { description = "地鼠/桥接服务发来的工具审批请求" },
        )
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_SERVICE,
                "桥接服务",
                NotificationManager.IMPORTANCE_LOW,
            ).apply { description = "保持与电脑端的长连接" },
        )
    }

    fun notifyApproval(context: Context, pending: BridgeState.PendingApproval) {
        ensureChannels(context)
        val builder = baseBuilder(context, CHANNEL_APPROVAL)
            .setContentTitle("呆呆鱼：需要你批准")
            .setContentText("${pending.toolName} · ${pending.reason.take(60)}")
            .setStyle(Notification.BigTextStyle().bigText("${pending.toolName}\n${pending.reason}"))
            .setAutoCancel(true)
            .setOngoing(false)
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        nm.notify(NOTIFY_ID_APPROVAL, builder.build())
    }

    fun buildServiceNotification(context: Context, connected: Boolean): Notification {
        ensureChannels(context)
        return baseBuilder(context, CHANNEL_SERVICE)
            .setContentTitle("呆呆鱼桥接服务")
            .setContentText(if (connected) "已连接电脑端" else "正在连接电脑端…")
            .setOngoing(true)
            .build()
    }

    fun serviceNotificationId(): Int = NOTIFY_ID_SERVICE

    private fun baseBuilder(context: Context, channelId: String): Notification.Builder {
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(context, channelId)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(context)
        }
        builder.setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
        val launch = context.packageManager.getLaunchIntentForPackage(context.packageName)
        if (launch != null) {
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0)
            builder.setContentIntent(PendingIntent.getActivity(context, 0, launch, flags))
        }
        return builder
    }
}
