package com.shenshen.bridge

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build

/**
 * 审批请求通知（带【允许一次】【拒绝】按钮）+ 前台服务常驻通知。
 * 只依赖系统 API（无 androidx），方便 kotlinc + d8 直接编成 classes2.dex。
 */
object ApprovalNotifier {

    const val CHANNEL_APPROVAL = "bridge_approval"
    const val CHANNEL_SERVICE = "bridge_service"

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

    /** 收到审批请求：弹带两个按钮的通知，点按钮即回包并消失。 */
    fun notifyApproval(context: Context, pending: BridgeState.PendingApproval) {
        ensureChannels(context)
        val reasonLine = pending.reason.replace('\n', ' ').ifBlank { "（未提供原因）" }
        val builder = baseBuilder(context, CHANNEL_APPROVAL)
            .setContentTitle("DSH 审批 · ${pending.toolName.ifBlank { "工具" }}")
            .setContentText(reasonLine.take(80))
            .setStyle(Notification.BigTextStyle().bigText(reasonLine))
            .setAutoCancel(true)
            .setOngoing(false)
            .setTimeoutAfter(pending.timeoutMs + 3_000L)
            .addAction(
                android.R.drawable.checkbox_on_background,
                "允许一次",
                decisionIntent(context, pending.id, Config.DECISION_ALLOW_ONCE),
            )
            .addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                "拒绝",
                decisionIntent(context, pending.id, Config.DECISION_REJECT),
            )

        val dashboard = dashboardIntent(context)
        if (dashboard != null) builder.setContentIntent(dashboard)

        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        runCatching { nm.notify(Config.NOTIFY_ID_APPROVAL, builder.build()) }
    }

    /** 审批结束（超时/已处理）→ 抹掉通知 */
    fun cancelApproval(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        runCatching { nm.cancel(Config.NOTIFY_ID_APPROVAL) }
    }

    fun buildServiceNotification(context: Context, connected: Boolean): Notification {
        ensureChannels(context)
        val builder = baseBuilder(context, CHANNEL_SERVICE)
            .setContentTitle("呆呆鱼桥接服务")
            .setContentText(if (connected) "已连接电脑端" else "正在连接电脑端…")
            .setOngoing(true)
        val dashboard = dashboardIntent(context)
        if (dashboard != null) builder.setContentIntent(dashboard)
        return builder.build()
    }

    fun serviceNotificationId(): Int = Config.NOTIFY_ID_SERVICE

    private fun decisionIntent(context: Context, id: String, decision: String): PendingIntent {
        val intent = Intent(context, ApprovalActionReceiver::class.java).apply {
            action = Config.ACTION_APPROVAL
            putExtra(Config.EXTRA_ID, id)
            putExtra(Config.EXTRA_DECISION, decision)
        }
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or
            (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0)
        // requestCode 用 id 的哈希 + 决策区分，避免两个按钮拿到同一个 PendingIntent
        return PendingIntent.getBroadcast(context, (id + decision).hashCode(), intent, flags)
    }

    private fun dashboardIntent(context: Context): PendingIntent? {
        return runCatching {
            val intent = Intent(context, DshDashboardActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            }
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0)
            PendingIntent.getActivity(context, 2, intent, flags)
        }.getOrNull()
    }

    private fun baseBuilder(context: Context, channelId: String): Notification.Builder {
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(context, channelId)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(context)
        }
        builder.setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
        return builder
    }
}
