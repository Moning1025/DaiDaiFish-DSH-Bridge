package com.shenshen.bridge

import java.util.concurrent.CopyOnWriteArrayList

/**
 * 全局连接状态与待审批信息（进程内单例）。
 * 由 [BridgeClient] 写入，UI / 通知 / 服务读取。
 */
object BridgeState {

    enum class Status {
        DISCONNECTED,
        CONNECTING,
        CONNECTED,
        RECONNECTING,
    }

    data class PendingApproval(
        val id: String,
        val toolName: String,
        val reason: String,
        val receivedAt: Long,
    )

    fun interface Listener {
        fun onChanged(status: Status, pending: PendingApproval?)
    }

    @Volatile
    var status: Status = Status.DISCONNECTED
        private set

    @Volatile
    var pendingApproval: PendingApproval? = null
        private set

    @Volatile
    var lastError: String? = null
        private set

    private val listeners = CopyOnWriteArrayList<Listener>()

    fun addListener(listener: Listener) {
        listeners += listener
    }

    fun removeListener(listener: Listener) {
        listeners -= listener
    }

    @Synchronized
    fun setStatus(next: Status, error: String? = null) {
        status = next
        lastError = error
        notifyAllListeners()
    }

    @Synchronized
    fun setPendingApproval(pending: PendingApproval) {
        pendingApproval = pending
        notifyAllListeners()
    }

    @Synchronized
    fun clearPendingApproval() {
        pendingApproval = null
        notifyAllListeners()
    }

    private fun notifyAllListeners() {
        val snapshot = listeners.toList()
        for (listener in snapshot) {
            runCatching { listener.onChanged(status, pendingApproval) }
        }
    }
}
