package com.shenshen.bridge

import java.util.concurrent.CopyOnWriteArrayList

/**
 * 全局连接状态 / 待审批 / 任务列表 / 执行轨迹（进程内单例）。
 * 写入方：BridgeClient。读取方：UI（DshDashboardActivity）、通知、前台服务。
 *
 * v4（P3）新增：
 *  - tasks / activeTaskId / taskState：多任务标签条 + 四态状态栏
 *  - TraceEntry 支持 thinking / tool_call / tool_result / final 四类
 *  - uploadProgress / pendingAttachment：文件分片上传进度与待发送附件
 */
object BridgeState {

    enum class Status { DISCONNECTED, CONNECTING, CONNECTED, RECONNECTING }

    data class PendingApproval(
        val id: String,
        val toolName: String,
        val reason: String,
        val receivedAt: Long,
        val timeoutMs: Long = Config.APPROVAL_TIMEOUT_MS,
        val taskId: String? = null,
        val workspace: String? = null,
        val sessionBrief: String? = null,
    )

    /** 执行轨迹一条：kind = thinking / tool_call / tool_result / final */
    data class TraceEntry(
        val kind: String,
        val brief: String,
        val detail: String,
        val at: Long,
        val ok: Boolean = true,
    )

    /** 电脑端 DSH 的一个工作区（来自 workspace.json 注册表） */
    data class WorkspaceInfo(
        val id: String,
        val title: String,
        val path: String,
        val exists: Boolean = true,
    )

    /** 电脑端正在跑/跑过的任务（服务端 tasksSnapshot） */
    data class TaskInfo(
        val taskId: String,
        val sessionId: String?,
        val workspaceTitle: String?,
        val cwd: String?,
        val prompt: String,
        val state: String,
        val turn: Int,
        val step: Int,
        val startedAt: Long,
        val traceCount: Int,
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

    /** 当前任务四态：idle / running / success / failed / stopped / stopping */
    @Volatile
    var taskState: String = "idle"
        private set

    @Volatile
    var taskReason: String? = null
        private set

    @Volatile
    var turn: Int = 0
        private set

    @Volatile
    var step: Int = 0
        private set

    /** 电脑端任务列表（新→旧） */
    @Volatile
    var tasks: List<TaskInfo> = emptyList()
        private set

    /** 当前手机界面盯着的任务；空 = 最近一个 */
    @Volatile
    var activeTaskId: String? = null
        private set

    @Volatile
    var maxTasks: Int = 2
        private set

    /** 电脑端工作区列表（null 表示还没拉过） */
    @Volatile
    var workspaces: List<WorkspaceInfo>? = null
        private set

    /** 手机上选中的工作区路径（发任务时随 run 一起发；空 = 用服务端默认根目录） */
    @Volatile
    var selectedWorkspace: String? = null
        private set

    /** 上传进度：null = 没有进行中的上传 */
    @Volatile
    var uploadProgress: Pair<Long, Long>? = null
        private set

    /** 上传完成、等待随下一条 run 发送的附件路径 */
    @Volatile
    var pendingAttachment: String? = null
        private set

    @Volatile
    var pendingAttachmentName: String? = null
        private set

    /** 最近一条流式正文（打字机效果用） */
    @Volatile
    var streamText: String = ""
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

    /** 服务端 task-state：running / success / failed / stopped / stopping */
    @Synchronized
    fun setTaskState(state: String, reason: String? = null, turn: Int? = null, step: Int? = null) {
        taskState = state
        taskReason = reason
        if (turn != null) this.turn = turn
        if (step != null) this.step = step
        taskRunning = state == "running" || state == "stopping"
        notifyRefresh()
    }

    @Synchronized
    fun setTasks(list: List<TaskInfo>, active: String? = null) {
        tasks = list
        if (active != null) activeTaskId = active
        if (activeTaskId == null) activeTaskId = list.firstOrNull()?.taskId
        notifyRefresh()
    }

    @Synchronized
    fun setActiveTask(taskId: String?) {
        activeTaskId = taskId
        notifyRefresh()
    }

    @Synchronized
    fun setMaxTasks(n: Int) {
        maxTasks = n
        notifyRefresh()
    }

    @Synchronized
    fun setWorkspaces(list: List<WorkspaceInfo>) {
        workspaces = list
        if (selectedWorkspace == null) {
            selectedWorkspace = list.firstOrNull { it.title.contains("DaiDaiFish", ignoreCase = true) }?.path
                ?: list.firstOrNull()?.path
        }
        notifyRefresh()
    }

    @Synchronized
    fun selectWorkspace(path: String?) {
        selectedWorkspace = path
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
    fun replaceTrace(entries: List<TraceEntry>) {
        synchronized(traceEntries) {
            traceEntries.clear()
            traceEntries.addAll(entries.takeLast(Config.TRACE_MAX))
        }
        notifyRefresh()
    }

    @Synchronized
    fun clearTrace() {
        synchronized(traceEntries) { traceEntries.clear() }
        streamText = ""
        notifyRefresh()
    }

    @Synchronized
    fun appendStreamText(text: String) {
        streamText = (streamText + text).takeLast(4000)
        notifyRefresh()
    }

    @Synchronized
    fun setUploadProgress(received: Long, size: Long) {
        uploadProgress = if (size > 0) received to size else null
        notifyRefresh()
    }

    @Synchronized
    fun clearUploadProgress() {
        uploadProgress = null
        notifyRefresh()
    }

    @Synchronized
    fun setPendingAttachment(path: String?, name: String?) {
        pendingAttachment = path
        pendingAttachmentName = name
        uploadProgress = null
        notifyRefresh()
    }

    @Synchronized
    fun clearPendingAttachment() {
        pendingAttachment = null
        pendingAttachmentName = null
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
