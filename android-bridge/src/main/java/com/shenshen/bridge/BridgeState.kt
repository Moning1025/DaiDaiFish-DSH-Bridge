package com.shenshen.bridge

import java.util.concurrent.CopyOnWriteArrayList

/**
 * 全局连接状态 / 待审批 / 任务状态 / 执行轨迹（进程内单例）。
 * 写入方：BridgeClient。读取方：UI（DshDashboardActivity）、通知、前台服务。
 */
object BridgeState {

    enum class Status { DISCONNECTED, CONNECTING, CONNECTED, RECONNECTING }

    data class PendingApproval(
        val id: String,
        val toolName: String,
        val reason: String,
        val receivedAt: Long,
        val timeoutMs: Long = Config.APPROVAL_TIMEOUT_MS,
    )

    /** 执行轨迹一条：kind = thinking / tool_call */
    data class TraceEntry(
        val kind: String,
        val brief: String,
        val detail: String,
        val at: Long,
    )

    /** 老接口：只关心连接状态与待审批（BridgeService 用来刷新常驻通知） */
    fun interface Listener {
        fun onChanged(status: Status, pending: PendingApproval?)
    }

    /** 新接口：任何一个状态变了都通知（Activity 用来整体刷新 UI） */
    fun interface Refreshable {
        fun refresh()
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

    @Volatile
    var lastCwd: String? = null
        private set

    @Volatile
    var taskRunning: Boolean = false
        private set

    private val traceEntries = ArrayList<TraceEntry>()
    private val listeners = CopyOnWriteArrayList<Listener>()
    private val refreshables = CopyOnWriteArrayList<Refreshable>()

    /** 线程安全的轨迹快照（UI 侧直接遍历渲染） */
    fun trace(): List<TraceEntry> = synchronized(traceEntries) { ArrayList(traceEntries) }

    fun addListener(listener: Listener) { listeners += listener }
    fun removeListener(listener: Listener) { listeners -= listener }
    fun addRefreshable(r: Refreshable) { refreshables += r }
    fun removeRefreshable(r: Refreshable) { refreshables -= r }

    @Synchronized
    fun setStatus(next: Status, error: String? = null) {
        status = next
        lastError = error
        notifyAllListeners()
        notifyRefresh()
    }

    @Synchronized
    fun setPendingApproval(pending: PendingApproval) {
        pendingApproval = pending
        notifyAllListeners()
        notifyRefresh()
    }

    @Synchronized
    fun clearPendingApproval() {
        pendingApproval = null
        notifyAllListeners()
        notifyRefresh()
    }

    @Synchronized
    fun setCwd(cwd: String?) {
        if (cwd.isNullOrBlank()) return
        lastCwd = cwd
        notifyRefresh()
    }

    @Synchronized
    fun setTaskRunning(running: Boolean) {
        taskRunning = running
        notifyRefresh()
    }

    @Synchronized
    fun addTrace(entry: TraceEntry) {
        synchronized(traceEntries) {
            traceEntries.add(entry)
            while (traceEntries.size > Config.TRACE_MAX) traceEntries.removeAt(0)
        }
        notifyRefresh()
    }

    @Synchronized
    fun clearTrace() {
        synchronized(traceEntries) { traceEntries.clear() }
        notifyRefresh()
    }

    private fun notifyAllListeners() {
        val snapshot = listeners.toList()
        for (listener in snapshot) {
            runCatching { listener.onChanged(status, pendingApproval) }
        }
    }

    private fun notifyRefresh() {
        val snapshot = refreshables.toList()
        for (r in snapshot) {
            runCatching { r.refresh() }
        }
    }
}
