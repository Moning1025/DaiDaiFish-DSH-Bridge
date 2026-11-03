package com.shenshen.bridge

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * 通知栏【允许一次】【拒绝】按钮的落地点。
 * 直接回包给电脑端，然后把通知抹掉。
 */
class ApprovalActionReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Config.ACTION_APPROVAL) return
        val id = intent.getStringExtra(Config.EXTRA_ID).orEmpty()
        val decision = intent.getStringExtra(Config.EXTRA_DECISION) ?: Config.DECISION_REJECT
        if (id.isEmpty()) {
            Log.w(TAG, "approval action without id")
            return
        }
        val client = BridgeClient.shared
        val sent = client?.sendApproval(id, decision) ?: false
        Log.i(TAG, "approval action id=$id decision=$decision sent=$sent")
        ApprovalNotifier.cancelApproval(context)
        if (!sent) {
            // 连接不在线：至少别让用户以为点了没反应
            BridgeState.setStatus(BridgeState.Status.DISCONNECTED, "审批回包失败：未连接电脑端")
        }
    }

    private companion object {
        const val TAG = "ApprovalActionReceiver"
    }
}
