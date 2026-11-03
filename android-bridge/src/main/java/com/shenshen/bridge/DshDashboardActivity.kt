package com.shenshen.bridge

import android.app.Activity
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

/**
 * DSH 掌上控制台：纯代码绘制（无 XML、无 androidx）。
 *
 * 三块内容：
 *   1. 连接状态（灯 + 文字 + 重连）
 *   2. 待审批（允许一次 / 拒绝）
 *   3. 任务下发（输入框 + 发送 + 停止）+ 可折叠「执行轨迹」
 */
class DshDashboardActivity : Activity(), BridgeState.Refreshable {

    private lateinit var dot: View
    private lateinit var statusText: TextView
    private lateinit var approvalHeader: TextView
    private lateinit var approvalBox: LinearLayout
    private lateinit var traceHeader: TextView
    private lateinit var traceBox: LinearLayout
    private lateinit var taskStateText: TextView
    private lateinit var cwdText: TextView
    private lateinit var promptInput: EditText

    private var traceExpanded = false
    private var lastTraceSig: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        BridgeService.start(this)
        BridgeHooks.attachContext(this)
        setContentView(buildRoot())
        BridgeState.addRefreshable(this)
        refresh()
    }

    override fun onResume() {
        super.onResume()
        BridgeState.addRefreshable(this)
        refresh()
    }

    override fun onPause() {
        BridgeState.removeRefreshable(this)
        super.onPause()
    }

    // ------------------------------------------------------------ UI 构建

    private fun buildRoot(): View {
        val scroll = ScrollView(this).apply {
            setBackgroundColor(COL_BG)
            isFillViewport = true
        }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(20), dp(16), dp(24))
        }
        scroll.addView(root)

        root.addView(text("DSH 控制台", 22f, COL_TITLE, bold = true))
        root.addView(text("手边就是终端 · 审批在手机上点", 12f, COL_DIM).apply {
            setPadding(0, dp(4), 0, dp(18))
        })

        // ---- 连接状态
        val statusRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        dot = View(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(10), dp(10)).apply { rightMargin = dp(8) }
            background = circle(COL_DIM)
        }
        statusText = text("未连接", 14f, COL_TEXT).apply {
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        val reconnectBtn = smallButton("重连", COL_ACCENT) {
            BridgeClient.shared?.reconnect()
                ?: run {
                    BridgeService.start(this)
                    toast("正在启动桥接服务…")
                }
        }
        statusRow.addView(dot)
        statusRow.addView(statusText)
        statusRow.addView(reconnectBtn)
        root.addView(card(statusRow))

        // ---- 待审批
        approvalHeader = text("待审批", 13f, COL_DIM, bold = true).apply {
            setPadding(dp(2), dp(18), 0, dp(8))
        }
        root.addView(approvalHeader)
        approvalBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(approvalBox)

        // ---- 任务下发
        root.addView(text("下发任务", 13f, COL_DIM, bold = true).apply {
            setPadding(dp(2), dp(18), 0, dp(8))
        })
        promptInput = EditText(this).apply {
            hint = "给电脑端的 DSH 说点什么…"
            setHintTextColor(COL_DIM)
            setTextColor(COL_TEXT)
            textSize = 14f
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            minLines = 2
            maxLines = 4
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = roundRect(COL_CARD2, dp(12), COL_STROKE)
        }
        root.addView(promptInput)

        val actionRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(10), 0, 0)
        }
        val sendBtn = primaryButton("发送", COL_ACCENT) { sendPrompt() }
        val stopBtn = primaryButton("停止", COL_DANGER) {
            val ok = BridgeClient.shared?.sendStop() ?: false
            toast(if (ok) "已发送停止指令" else "未连接或服务未就绪")
        }
        val sendLp = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        actionRow.addView(sendBtn, sendLp)
        actionRow.addView(stopBtn, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
            leftMargin = dp(10)
        })
        root.addView(actionRow)

        taskStateText = text("空闲", 12f, COL_DIM).apply { setPadding(dp(2), dp(10), 0, 0) }
        root.addView(taskStateText)

        // ---- 执行轨迹（可折叠）
        traceHeader = text("执行轨迹 (0) ▼", 13f, COL_DIM, bold = true).apply {
            setPadding(dp(2), dp(20), 0, dp(8))
            isClickable = true
            setOnClickListener {
                traceExpanded = !traceExpanded
                traceBox.visibility = if (traceExpanded) View.VISIBLE else View.GONE
                renderTraceHeader()
            }
        }
        root.addView(traceHeader)
        traceBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
        }
        root.addView(traceBox)

        // ---- 工作区
        cwdText = text("工作区：—", 11f, COL_DIM).apply { setPadding(dp(2), dp(20), 0, 0) }
        root.addView(cwdText)

        return scroll
    }

    // ------------------------------------------------------------ 刷新

    override fun refresh() {
        runOnUiThread {
            renderStatus()
            renderApproval()
            renderTaskState()
            renderTraceHeader()
            if (traceExpanded) renderTraceBody()
            cwdText.text = "工作区：" + (BridgeState.lastCwd ?: "—")
        }
    }

    private fun renderStatus() {
        val (color, label) = when (BridgeState.status) {
            BridgeState.Status.CONNECTED -> COL_OK to "已连接电脑端"
            BridgeState.Status.CONNECTING -> COL_WARN to "正在连接…"
            BridgeState.Status.RECONNECTING -> COL_WARN to "重连中…"
            BridgeState.Status.DISCONNECTED -> COL_DANGER to "未连接"
        }
        dot.background = circle(color)
        val err = BridgeState.lastError
        statusText.text = if (err.isNullOrBlank()) label else "$label · $err"
        statusText.setTextColor(if (err.isNullOrBlank()) COL_TEXT else COL_WARN)
    }

    private fun renderApproval() {
        approvalBox.removeAllViews()
        val pending = BridgeState.pendingApproval
        if (pending == null) {
            approvalHeader.text = "待审批"
            approvalBox.addView(text("当前没有待审批请求", 12f, COL_DIM).apply {
                setPadding(dp(2), 0, 0, dp(2))
            })
            return
        }
        approvalHeader.text = "待审批 (1)"
        val cardView = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(12))
            background = roundRect(COL_CARD2, dp(12), COL_WARN)
        }
        cardView.addView(text("${pending.toolName.ifBlank { "工具" }} 请求放行", 14f, COL_TEXT, bold = true))
        if (pending.reason.isNotBlank()) {
            cardView.addView(text(pending.reason, 12f, COL_SUB).apply {
                setPadding(0, dp(6), 0, 0)
            })
        }
        cardView.addView(text("超时 ${pending.timeoutMs / 1000} 秒后自动拒绝", 11f, COL_DIM).apply {
            setPadding(0, dp(6), 0, 0)
        })

        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(10), 0, 0)
        }
        row.addView(primaryButton("允许一次", COL_OK) {
            decide(pending.id, Config.DECISION_ALLOW_ONCE)
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(primaryButton("拒绝", COL_DANGER) {
            decide(pending.id, Config.DECISION_REJECT)
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
            leftMargin = dp(10)
        })
        cardView.addView(row)
        approvalBox.addView(cardView)
    }

    private fun renderTaskState() {
        taskStateText.text = if (BridgeState.taskRunning) "任务执行中…" else "空闲"
        taskStateText.setTextColor(if (BridgeState.taskRunning) COL_WARN else COL_DIM)
    }

    private fun renderTraceHeader() {
        val n = BridgeState.trace().size
        traceHeader.text = "执行轨迹 ($n) " + if (traceExpanded) "▼" else "▶"
    }

    private fun renderTraceBody() {
        val entries = BridgeState.trace()
        val sig = entries.joinToString("|") { it.at.toString() + it.kind }
        if (sig == lastTraceSig && traceBox.childCount == entries.size) return
        lastTraceSig = sig
        traceBox.removeAllViews()
        if (entries.isEmpty()) {
            traceBox.addView(text("暂无轨迹（发出任务后这里会滚动出现）", 12f, COL_DIM))
            return
        }
        for (e in entries) {
            val row = TextView(this).apply {
                text = "▶ " + e.brief
                setTextColor(if (e.kind == "tool_call") COL_ACCENT else COL_SUB)
                textSize = 12f
                setPadding(dp(6), dp(7), dp(6), dp(7))
                isClickable = true
                background = roundRect(COL_CARD, dp(8), null)
                setOnClickListener {
                    if (text.startsWith("▶")) {
                        text = "▼ " + e.brief + "\n" + e.detail
                    } else {
                        text = "▶ " + e.brief
                    }
                }
            }
            traceBox.addView(row, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { bottomMargin = dp(4) })
        }
    }

    // ------------------------------------------------------------ 动作

    private fun sendPrompt() {
        val prompt = promptInput.text?.toString()?.trim().orEmpty()
        if (prompt.isEmpty()) {
            toast("先写点任务内容")
            return
        }
        val client = BridgeClient.shared
        if (client == null) {
            BridgeService.start(this)
            toast("桥接服务未就绪，正在启动…")
            return
        }
        val ok = client.sendRun(prompt)
        if (ok) {
            promptInput.setText("")
            toast("已下发")
        } else {
            toast("未连接电脑端")
        }
    }

    private fun decide(id: String, decision: String) {
        val ok = BridgeClient.shared?.sendApproval(id, decision) ?: false
        if (ok) {
            ApprovalNotifier.cancelApproval(this)
            BridgeState.clearPendingApproval()
        } else {
            toast("回包失败：连接已断")
        }
    }

    // ------------------------------------------------------------ 小工具

    private fun text(value: String, size: Float, color: Int, bold: Boolean = false): TextView {
        return TextView(this).apply {
            text = value
            textSize = size
            setTextColor(color)
            if (bold) setTypeface(typeface, android.graphics.Typeface.BOLD)
        }
    }

    private fun card(child: View): View {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(12))
            background = roundRect(COL_CARD, dp(12), COL_STROKE)
        }
        box.addView(child)
        return box
    }

    private fun smallButton(label: String, color: Int, onClick: () -> Unit): Button {
        return Button(this).apply {
            text = label
            textSize = 12f
            setTextColor(color)
            isAllCaps = false
            background = roundRect(COL_CARD2, dp(18), color)
            setPadding(dp(14), dp(4), dp(14), dp(4))
            minimumWidth = 0
            minWidth = 0
            minimumHeight = 0
            minHeight = 0
            setOnClickListener { onClick() }
        }
    }

    private fun primaryButton(label: String, color: Int, onClick: () -> Unit): Button {
        return Button(this).apply {
            text = label
            textSize = 14f
            setTextColor(Color.WHITE)
            isAllCaps = false
            background = roundRect(color, dp(12), null)
            setPadding(0, dp(10), 0, dp(10))
            minimumHeight = 0
            minHeight = 0
            setOnClickListener { onClick() }
        }
    }

    private fun circle(color: Int): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(color)
    }

    private fun roundRect(fill: Int, radius: Int, stroke: Int?): GradientDrawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = radius.toFloat()
            setColor(fill)
            if (stroke != null) setStroke(dp(1), stroke)
        }
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun toast(msg: String) {
        runCatching { Toast.makeText(this, msg, Toast.LENGTH_SHORT).show() }
    }

    private companion object {
        val COL_BG = 0xFF0E1117.toInt()
        val COL_CARD = 0xFF161B24.toInt()
        val COL_CARD2 = 0xFF1C2230.toInt()
        val COL_STROKE = 0xFF262E3D.toInt()
        val COL_TITLE = 0xFFE9EEF7.toInt()
        val COL_TEXT = 0xFFD6DEEA.toInt()
        val COL_SUB = 0xFF9AA7BC.toInt()
        val COL_DIM = 0xFF6E7A8F.toInt()
        val COL_ACCENT = 0xFF4C8DFF.toInt()
        val COL_OK = 0xFF37D67A.toInt()
        val COL_WARN = 0xFFF2C94C.toInt()
        val COL_DANGER = 0xFFEB5757.toInt()
    }
}
